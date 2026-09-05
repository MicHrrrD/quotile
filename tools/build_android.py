#!/usr/bin/env python3
"""Build Quotile on your own Windows, macOS or Linux machine (Python 3.9+).

Prerequisites: an installed Android SDK (API 36 + Build Tools 35.0.0), accepted
SDK licenses, and JDK 17-23. Android Studio normally provides the JDK and SDK.
This script never installs an SDK or accepts SDK licenses for you. It downloads
the pinned official Gradle distribution only if no matching local Gradle exists.
Gradle itself downloads Android build dependencies on the first online build.

Examples (run from the extracted Quotile project):
    python tools/build_android.py --check
    python tools/build_android.py
    python tools/build_android.py --sdk /path/to/Android/Sdk --java-home /path/to/jdk
    python tools/build_android.py --gradle /path/to/gradle-8.11.1/bin/gradle --offline

Android Studio can also open the android/ directory and build an APK through its
Build menu; Python is optional. Set its Gradle distribution to local 8.11.1 if
the IDE asks, and Gradle JDK to 17 or 21. See the project README for full steps.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GRADLE_VERSION = "8.11.1"
GRADLE_DIR = "gradle-" + GRADLE_VERSION
GRADLE_URL = "https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip"
# Gradle's published binary ZIP checksum: https://gradle.org/release-checksums/
GRADLE_SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
BUILD_TOOLS = "35.0.0"
APK_NAME = "Quotile-0.3.5.apk"
WINDOWS = os.name == "nt"


def binary(directory: Path, name: str) -> Path:
    return directory / "bin" / (name + ".exe" if WINDOWS else name)


def java_candidates(explicit: str | None) -> list[Path]:
    if explicit:
        return [Path(explicit).expanduser()]
    candidates = [Path(os.environ[key]).expanduser() for key in ("JAVA_HOME", "STUDIO_JDK") if os.environ.get(key)]
    home = Path.home()
    candidates += [Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"),
                   Path("/opt/android-studio/jbr"), Path("/usr/local/android-studio/jbr"),
                   home / "android-studio/jbr"]
    for key in ("ProgramFiles", "ProgramW6432", "LOCALAPPDATA"):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]) / "Android/Android Studio/jbr")
    candidates += list((home / ".jdks").glob("*"))
    candidates += list(Path("/usr/lib/jvm").glob("*"))
    candidates += [p / "Contents/Home" for p in Path("/Library/Java/JavaVirtualMachines").glob("*")]
    java_on_path = shutil.which("java")
    if java_on_path:
        candidates.append(Path(java_on_path).resolve().parent.parent)
    return candidates


def find_java(explicit: str | None) -> Path:
    rejected = []
    for candidate in java_candidates(explicit):
        java = binary(candidate, "java")
        if not java.is_file() or not binary(candidate, "javac").is_file():
            continue
        try:
            process = subprocess.run([str(java), "-version"], capture_output=True,
                                     text=True, timeout=15, encoding="utf-8", errors="replace")
            match = re.search(r'version\s+"(\d+)', process.stdout + process.stderr)
            if process.returncode == 0 and match and 17 <= int(match[1]) <= 23:
                return candidate.resolve()
            rejected.append(str(candidate))
        except (OSError, subprocess.TimeoutExpired):
            continue
    suffix = " Unsupported JDKs: " + ", ".join(rejected) if rejected else ""
    raise RuntimeError("JDK 17-23 not found (JDK 17 or Android Studio JBR 21 recommended). "
                       "Set --java-home or JAVA_HOME to the JDK directory containing bin/java and bin/javac." + suffix)


def local_sdk(project: Path) -> Path | None:
    properties = project / "android/local.properties"
    if not properties.is_file():
        return None
    for line in properties.read_text(encoding="utf-8").splitlines():
        match = re.match(r"\s*sdk\.dir\s*[=:]\s*(.*?)\s*$", line)
        if match:
            # Java properties escape Windows backslashes, drive colons and spaces.
            decoded = re.sub(r"\\([\\:= ])", r"\1", match[1])
            path = Path(decoded).expanduser()
            return path if path.is_absolute() else properties.parent / path
    return None


def find_sdk(explicit: str | None, project: Path) -> Path:
    if explicit:
        candidates = [Path(explicit).expanduser()]
    else:
        candidates = [Path(os.environ[key]).expanduser() for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(key)]
        configured = local_sdk(project)
        if configured:
            candidates.append(configured)
        home = Path.home()
        candidates += [home / "Android/Sdk", home / "Library/Android/sdk"]
        if os.environ.get("LOCALAPPDATA"):
            candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    missing_descriptions = []
    for candidate in candidates:
        if not candidate.is_dir():
            continue
        missing = []
        if not (candidate / "platforms/android-36/android.jar").is_file():
            missing.append("Android SDK Platform 36")
        build_tools = candidate / "build-tools" / BUILD_TOOLS
        if not (build_tools / ("aapt2.exe" if WINDOWS else "aapt2")).is_file():
            missing.append("Android SDK Build-Tools " + BUILD_TOOLS)
        license_file = candidate / "licenses/android-sdk-license"
        if not license_file.is_file() or not license_file.read_text(encoding="utf-8").strip():
            missing.append("accepted Android SDK license")
        if not missing:
            return candidate.resolve()
        missing_descriptions.append(str(candidate) + ": " + ", ".join(missing))
    suffix = "\n" + "\n".join(missing_descriptions) if missing_descriptions else ""
    raise RuntimeError("A complete Android SDK was not found. In Android Studio > SDK Manager, install "
                       "Android SDK Platform 36 and Android SDK Build-Tools 35.0.0, and review/accept their licenses. "
                       "Then set --sdk or ANDROID_HOME. The script will not install SDKs or accept licenses." + suffix)


def gradle_command(path: Path, args: list[str], env: dict[str, str]) -> list[str]:
    # On Windows, invoking GradleMain directly avoids shell/cmd quoting of user paths.
    if WINDOWS and path.suffix.lower() in (".bat", ".cmd"):
        library = path.resolve().parent.parent / "lib"
        if library.is_dir():
            return [str(binary(Path(env["JAVA_HOME"]), "java")), "-classpath", str(library / "*"),
                    "org.gradle.launcher.GradleMain", *args]
    return [str(path), *args]


def matching_gradle(path: Path, env: dict[str, str]) -> bool:
    path = path.resolve()
    # Require an installed distribution, so a wrapper/shim cannot bootstrap a
    # download during a supposedly offline prerequisite check.
    library = path.parent.parent / "lib"
    if not path.is_file() or not (library / f"gradle-core-{GRADLE_VERSION}.jar").is_file():
        return False
    try:
        process = subprocess.run(gradle_command(path, ["--version"], env), env=env, capture_output=True,
                                 text=True, timeout=30, encoding="utf-8", errors="replace")
        return process.returncode == 0 and re.search(r"(?m)^Gradle 8\.11\.1\s*$", process.stdout) is not None
    except (OSError, subprocess.TimeoutExpired):
        return False


def distribution_url_allowed(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port not in (None, 443):
        return False
    if parsed.hostname in ("downloads.gradle.org", "services.gradle.org"):
        return parsed.path == f"/distributions/{GRADLE_DIR}-bin.zip"
    if parsed.hostname == "github.com":
        return parsed.path == "/gradle/gradle-distributions/releases/download/v8.11.1/gradle-8.11.1-bin.zip"
    # GitHub's official release asset redirect. Every returned byte still has to
    # match the pinned Gradle checksum before extraction or execution.
    return parsed.hostname == "release-assets.githubusercontent.com"


class OfficialRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if not distribution_url_allowed(newurl):
            raise RuntimeError("Refusing an unexpected Gradle download redirect: " + urlparse(newurl).netloc)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def safe_extract(archive: Path, destination: Path) -> None:
    """Extract only the expected Gradle directory; reject traversal and symlinks."""
    with zipfile.ZipFile(archive) as zipped:
        total = 0
        for item in zipped.infolist():
            name = item.filename
            part = PurePosixPath(name)
            mode = (item.external_attr >> 16) & 0xFFFF
            if ("\\" in name or ":" in name or part.is_absolute() or ".." in part.parts
                    or not part.parts or part.parts[0] != GRADLE_DIR or stat.S_ISLNK(mode)):
                raise RuntimeError("Unsafe path in Gradle ZIP: " + name)
            target = destination.joinpath(*part.parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError("Gradle ZIP path escapes destination: " + name)
            total += item.file_size
            if total > 1024 * 1024 * 1024:
                raise RuntimeError("Gradle ZIP expands beyond the 1 GiB extraction limit.")
        for item in zipped.infolist():
            target = destination.joinpath(*PurePosixPath(item.filename).parts)
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open(item) as source, target.open("wb") as sink:
                shutil.copyfileobj(source, sink)
        if not WINDOWS:
            (destination / GRADLE_DIR / "bin/gradle").chmod(0o755)


def download_gradle(cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="quotile-gradle-", dir=cache) as temporary:
        temp = Path(temporary)
        archive = temp / "gradle.zip"
        digest = hashlib.sha256()
        request = Request(GRADLE_URL, headers={"User-Agent": "Quotile-build/0.1.0"})
        opener = build_opener(OfficialRedirects())  # Default HTTPS certificate validation stays enabled.
        print("Downloading official Gradle 8.11.1; checking its pinned SHA-256...", flush=True)
        with opener.open(request, timeout=60) as response, archive.open("wb") as output:
            if not distribution_url_allowed(response.geturl()):
                raise RuntimeError("Unexpected Gradle download source.")
            count = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                count += len(chunk)
                if count > 256 * 1024 * 1024:
                    raise RuntimeError("Gradle ZIP exceeds the 256 MiB download limit.")
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != GRADLE_SHA256:
            raise RuntimeError("Gradle ZIP SHA-256 mismatch; archive was discarded and will not be executed.")
        extracted = temp / "extracted"
        extracted.mkdir()
        safe_extract(archive, extracted)
        destination = cache / GRADLE_DIR
        if destination.exists():
            raise RuntimeError("Gradle cache directory already exists but was not usable. "
                               "Choose an empty --cache directory or provide --gradle.")
        shutil.move(str(extracted / GRADLE_DIR), str(destination))
    return destination / "bin" / ("gradle.bat" if WINDOWS else "gradle")


def find_gradle(explicit: str | None, cache: Path, env: dict[str, str], allow_download: bool) -> Path:
    if explicit:
        candidate = Path(explicit).expanduser().resolve()
        if candidate.is_dir():
            candidate = candidate / "bin" / ("gradle.bat" if WINDOWS else "gradle")
        if matching_gradle(candidate, env):
            return candidate
        raise RuntimeError("--gradle must point to a working Gradle 8.11.1 executable or installation.")
    candidates = [cache / GRADLE_DIR / "bin" / ("gradle.bat" if WINDOWS else "gradle")]
    if os.environ.get("GRADLE_HOME"):
        candidates.append(Path(os.environ["GRADLE_HOME"]) / "bin" / ("gradle.bat" if WINDOWS else "gradle"))
    located = shutil.which("gradle")
    if located:
        candidates.append(Path(located))
    for candidate in candidates:
        if matching_gradle(candidate, env):
            return candidate.resolve()
    if not allow_download:
        raise RuntimeError("Gradle 8.11.1 is not installed. A normal online build can download the pinned official "
                           "distribution, or supply --gradle. --check and --offline never download it.")
    installed = download_gradle(cache)
    if not matching_gradle(installed, env):
        raise RuntimeError("The verified Gradle distribution could not run with the selected JDK.")
    return installed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java-home", help="JDK 17-23 home directory")
    parser.add_argument("--sdk", help="Android SDK directory containing platforms/android-36")
    parser.add_argument("--gradle", help="Existing Gradle 8.11.1 executable or installation directory")
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/quotile-build", help="Gradle download cache")
    parser.add_argument("--check", action="store_true", help="Check prerequisites only; no downloads or APK build")
    parser.add_argument("--offline", action="store_true", help="Disable downloads (requires cached Gradle and build dependencies)")
    args = parser.parse_args()
    try:
        jdk = find_java(args.java_home)
        sdk = find_sdk(args.sdk, ROOT)
        configured_sdk = local_sdk(ROOT)
        if configured_sdk and configured_sdk.resolve() != sdk:
            raise RuntimeError("android/local.properties points to a different SDK: " + str(configured_sdk)
                               + ". Update its sdk.dir in Android Studio or choose that same path with --sdk.")
        env = os.environ.copy()
        env["JAVA_HOME"] = str(jdk)
        env["ANDROID_HOME"] = str(sdk)
        env["ANDROID_SDK_ROOT"] = str(sdk)
        env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
        print("JDK: " + str(jdk))
        print("Android SDK: " + str(sdk))
        gradle = find_gradle(args.gradle, args.cache.expanduser().resolve(), env, not (args.offline or args.check))
        print("Gradle: " + str(gradle))
        if args.check:
            print("Build prerequisites found. No APK has been built.")
            return 0
        verify = subprocess.run([sys.executable, str(ROOT / "tools/verify_source.py"), "--java", str(binary(jdk, "java"))], env=env)
        if verify.returncode:
            raise RuntimeError("Source checks failed; APK build was not started.")
        arguments = ["--no-daemon", "--console=plain", "-p", str(ROOT / "android"),
                     "-Dorg.gradle.java.home=" + str(jdk), "-Pandroid.builder.sdkDownload=false", ":app:assembleDebug"]
        if args.offline:
            arguments.insert(0, "--offline")
        result = subprocess.run(gradle_command(gradle, arguments, env), env=env, cwd=ROOT / "android")
        if result.returncode:
            raise RuntimeError("Gradle build failed. No new APK is being reported; inspect the Gradle errors above.")
        apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
        if not apk.is_file() or apk.stat().st_size == 0:
            raise RuntimeError("Gradle finished, but the expected nonempty app-debug.apk was not found.")
        with zipfile.ZipFile(apk) as package:
            required = {"AndroidManifest.xml", "classes.dex", "resources.arsc"}
            if not required.issubset(package.namelist()):
                raise RuntimeError("The output does not contain the expected Android APK entries.")
        output = ROOT / "dist" / APK_NAME
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, output)
        print("APK built successfully: " + str(output))
        print("Debug-signed build: keep the same debug keystore for updates. APKs built with a different key "
              "cannot update this installation; uninstalling clears the app's saved settings.")
        return 0
    except (OSError, RuntimeError, zipfile.BadZipFile, ValueError) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
