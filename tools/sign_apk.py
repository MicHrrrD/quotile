#!/usr/bin/env python3
"""Sign an already tested APK with an existing, privately retained keystore.

Use the official apksigner.jar included in the build artifact. This helper never
creates signing keys or prints passwords. Keep the keystore and its password
file outside the source repository and retain both for subsequent versions.

Example:
    python tools/sign_apk.py --input dist/Quotile-0.3.0.apk \\
        --output /private-delivery/Quotile-0.3.0.apk \\
        --apksigner dist/apksigner.jar --keystore /private/quotile.p12 \\
        --password-file /private/quotile-password.txt --alias quotile

The key password must be the same as the keystore password (normal for PKCS12).
The output must not already exist. JDK 17+ supplies java and keytool.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


def private_file(path: Path, description: str) -> None:
    if not path.is_file():
        raise RuntimeError(description + " is not an existing file.")
    if os.name != "nt" and path.stat().st_mode & 0o077:
        raise RuntimeError(description + " must only be accessible by its owner (chmod 600).")


def signing_entry(name: str) -> bool:
    parts = name.upper().split("/")
    return len(parts) == 2 and parts[0] == "META-INF" and (
        parts[1] == "MANIFEST.MF" or parts[1].endswith((".SF", ".RSA", ".DSA", ".EC")))


def payload(path: Path) -> dict[str, str]:
    """Hash every non-signature ZIP entry, preserving all app resources/code."""
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("APK contains duplicate ZIP entries.")
        if not {"AndroidManifest.xml", "classes.dex", "resources.arsc"}.issubset(names):
            raise RuntimeError("APK is missing required Android entries.")
        if package.testzip() is not None:
            raise RuntimeError("APK ZIP entry CRC check failed.")
        return {name: hashlib.sha256(package.read(name)).hexdigest()
                for name in names if not signing_entry(name)}


def run(command: list[str], description: str) -> bytes:
    result = subprocess.run(command, capture_output=True, timeout=120, check=False)
    if result.returncode:
        # Do not echo a subprocess command or diagnostics containing private paths
        # or keystore information. The name and return code identify the step.
        raise RuntimeError(f"{description} failed (exit {result.returncode}).")
    return result.stdout


def binary(name: str, java_home: Path | None) -> str:
    if java_home:
        candidate = java_home / "bin" / (name + ".exe" if os.name == "nt" else name)
        if candidate.is_file():
            return str(candidate)
    else:
        found = shutil.which(name)
        if found:
            return found
    raise RuntimeError("JDK executable not found: " + name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    try:
        source, output = args.input.resolve(), args.output.resolve()
        if source == output or output.exists():
            raise RuntimeError("Choose a new output path; existing APKs will not be overwritten.")
        if not args.apksigner.is_file():
            raise RuntimeError("Official apksigner.jar is missing.")
        private_file(args.keystore, "Keystore")
        private_file(args.password_file, "Password file")
        secret = args.password_file.read_bytes().rstrip(b"\r\n")
        if not secret or len(secret) > 4096 or any(c in secret for c in (b"\n", b"\r", b"\0")):
            raise RuntimeError("Password file must contain one nonempty UTF-8 line.")
        secret.decode("utf-8")
        before = payload(source)
        java = binary("java", args.java_home)
        keytool = binary("keytool", args.java_home)
        output.parent.mkdir(parents=True, exist_ok=True)
        # Directory mode 0700 and file mode 0600 protect the transient password
        # from other local users; only its path is passed to subprocesses.
        with tempfile.TemporaryDirectory(prefix="quotile-sign-", dir=output.parent) as temporary:
            directory = Path(temporary)
            password = directory / "password.txt"
            with password.open("xb") as sink:
                os.chmod(password, 0o600)
                sink.write(secret + b"\n")
            certificate = run([keytool, "-exportcert", "-keystore", str(args.keystore.resolve()),
                               "-alias", args.alias, "-storepass:file", str(password)], "Certificate export")
            expected = hashlib.sha256(certificate).hexdigest()
            signed = directory / "signed.apk"
            signer = [java, "-jar", str(args.apksigner.resolve())]
            run([*signer, "sign", "--ks", str(args.keystore.resolve()), "--ks-key-alias", args.alias,
                 "--ks-pass", "file:" + str(password), "--key-pass", "file:" + str(password),
                 "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                 "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                 "--out", str(signed), str(source)], "APK signing")
            report = run([*signer, "verify", "--verbose", "--print-certs", str(signed)], "APK signature verification").decode("utf-8")
            match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", report)
            if not match or match[1].lower() != expected:
                raise RuntimeError("Signed APK certificate does not match the retained keystore.")
            if "Verified using v2 scheme (APK Signature Scheme v2): true" not in report:
                raise RuntimeError("APK v2 signature verification did not pass.")
            if before != payload(signed):
                raise RuntimeError("Signing changed the app payload; no APK was delivered.")
            # Exclusive creation prevents accidental overwrites even if the
            # output appears after the initial check.
            with signed.open("rb") as source_file, output.open("xb") as destination:
                shutil.copyfileobj(source_file, destination)
        print("Signed APK: " + str(output))
        print("APK SHA-256: " + hashlib.sha256(output.read_bytes()).hexdigest())
        print("Certificate SHA-256: " + expected)
        print("Verified APK signature, retained certificate, ZIP CRCs, and unchanged app payload.")
        return 0
    except (OSError, RuntimeError, UnicodeError, zipfile.BadZipFile, subprocess.TimeoutExpired) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
