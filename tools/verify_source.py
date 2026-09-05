#!/usr/bin/env python3
"""Offline source checks. This is NOT an Android SDK build or a device test."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
RESOURCE_REF = re.compile(r"@(?P<create>\+)?(?:(?P<package>[\w.]+):)?(?P<type>[\w]+)/(?P<name>[\w.]+)")
JAVA_RESOURCE_REF = re.compile(r"(?<![\w.])R\.([A-Za-z_]\w*)\.([A-Za-z_]\w*)")


def check(project: Path, java: str | None) -> dict:
    src = project / "android/app/src/main"
    res = src / "res"
    java_root = src / "java"
    problems: list[str] = []
    details: list[str] = []
    documents: dict[Path, ET.Element] = {}
    resources: set[tuple[str, str]] = set()
    xml_files = sorted(res.rglob("*.xml")) + [src / "AndroidManifest.xml"]
    for file in xml_files:
        try:
            documents[file] = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as exc:
            problems.append(f"{file.relative_to(project)}: {exc}")
    for file in sorted(res.rglob("*")):
        if not file.is_file():
            continue
        kind = file.relative_to(res).parts[0].split("-", 1)[0]
        if kind != "values":
            name = file.name.removesuffix(".9.png").split(".", 1)[0]
            resources.add((kind, name))
        root = documents.get(file)
        if root is None:
            continue
        if kind == "values":
            for element in root:
                name = element.get("name")
                if name:
                    item_kind = element.get("type", element.tag)
                    if item_kind in ("string-array", "integer-array"):
                        item_kind = "array"
                    resources.add((item_kind, name))
        for element in root.iter():
            for value in element.attrib.values():
                for match in RESOURCE_REF.finditer(value):
                    if match["create"] and not match["package"]:
                        resources.add((match["type"], match["name"]))
    for file, root in documents.items():
        for element in root.iter():
            values = list(element.attrib.values()) + ([element.text] if element.text else [])
            for value in values:
                for match in RESOURCE_REF.finditer(value):
                    if match["package"] in ("android", "dev.mich.quotile"):
                        if match["package"] == "android":
                            continue
                    elif match["package"]:
                        problems.append(f"{file.name}: unsupported external resource {match[0]}")
                        continue
                    if (match["type"], match["name"]) not in resources:
                        problems.append(f"{file.relative_to(project)}: missing {match[0]}")
    java_files = sorted(java_root.rglob("*.java"))
    for file in java_files:
        text = file.read_text(encoding="utf-8")
        for kind, name in JAVA_RESOURCE_REF.findall(text):
            if (kind, name) not in resources:
                problems.append(f"{file.name}: missing R.{kind}.{name}")
    details.append(f"Parsed {len(documents)}/{len(xml_files)} XML files; checked local resource references.")

    build = (project / "android/app/build.gradle").read_text(encoding="utf-8")
    namespace_match = re.search(r"\bnamespace\s+['\"]([^'\"]+)['\"]", build)
    namespace = namespace_match[1] if namespace_match else ""
    if not namespace:
        problems.append("No namespace found in app/build.gradle.")
    for field, expected in (("compileSdk", 36), ("targetSdk", 36), ("minSdk", 31)):
        match = re.search(rf"\b{field}\s+(\d+)\b", build)
        if not match or int(match[1]) != expected:
            problems.append(f"Expected {field} {expected}.")
    for field in ("sourceCompatibility", "targetCompatibility"):
        if not re.search(rf"\b{field}\s+JavaVersion.VERSION_17\b", build):
            problems.append(f"Expected Java 17 {field}.")
    root_build = (project / "android/build.gradle").read_text(encoding="utf-8")
    if not re.search(r"id\s+['\"]com\.android\.application['\"]\s+version\s+['\"]8\.10\.1['\"]", root_build):
        problems.append("Expected Android Gradle Plugin 8.10.1.")
    details.append("Checked AGP 8.10.1, Java 17, min SDK 31 and compile/target SDK 36 declarations.")

    def source_exists(name: str) -> bool:
        full = namespace + name if name.startswith(".") else name
        if "." not in full:
            full = namespace + "." + full
        path = java_root.joinpath(*full.split(".")).with_suffix(".java")
        if not path.is_file():
            problems.append(f"Manifest component source missing: {full}")
            return False
        return True

    manifest = documents.get(src / "AndroidManifest.xml")
    component_count = 0
    providers: dict[str, str] = {}
    if manifest is not None:
        app = manifest.find("application")
        if app is None:
            problems.append("Manifest has no application.")
        else:
            if app.get(ANDROID_NS + "name"):
                source_exists(app.get(ANDROID_NS + "name", ""))
            for element in app:
                if element.tag not in ("activity", "activity-alias", "receiver", "service", "provider"):
                    continue
                component_count += 1
                attribute = "targetActivity" if element.tag == "activity-alias" else "name"
                name = element.get(ANDROID_NS + attribute, "")
                if not name:
                    problems.append(f"Manifest {element.tag} missing {attribute}.")
                else:
                    source_exists(name)
                if element.find("intent-filter") is not None and element.get(ANDROID_NS + "exported") not in ("true", "false"):
                    problems.append(f"Manifest {name}: intent filter needs explicit exported.")
                for metadata in element.findall("meta-data"):
                    if metadata.get(ANDROID_NS + "name") == "android.appwidget.provider":
                        providers[name] = metadata.get(ANDROID_NS + "resource", "")
    expected = {".SlimWidgetProvider": ("@xml/widget_slim", 1), ".DetailWidgetProvider": ("@xml/widget_detail", 2)}
    for component, (reference, rows) in expected.items():
        actual = providers.get(component, providers.get(namespace + component))
        if actual != reference:
            problems.append(f"{component} must use {reference}.")
        widget_path = res / "xml" / (reference.split("/", 1)[1] + ".xml")
        widget = documents.get(widget_path)
        if widget is None:
            problems.append(f"Missing or malformed {widget_path.name}.")
            continue
        if widget.tag != "appwidget-provider":
            problems.append(f"{widget_path.name}: expected appwidget-provider.")
        for attribute, value in (("targetCellWidth", "5"), ("targetCellHeight", str(rows))):
            if widget.get(ANDROID_NS + attribute) != value:
                problems.append(f"{widget_path.name}: expected {attribute}={value}.")
        if set(widget.get(ANDROID_NS + "resizeMode", "").split("|")) != {"horizontal", "vertical"}:
            problems.append(f"{widget_path.name}: both resize directions are required.")
        for axis in ("Width", "Height"):
            dimensions = []
            for attribute in ("minResize" + axis, "min" + axis, "maxResize" + axis):
                value = widget.get(ANDROID_NS + attribute, "")
                if not re.fullmatch(r"\d+(?:\.\d+)?dp", value):
                    problems.append(f"{widget_path.name}: positive dp {attribute} required.")
                    dimensions = []
                    break
                dimensions.append(float(value[:-2]))
            if dimensions and not (0 < dimensions[0] <= dimensions[1] <= dimensions[2]):
                problems.append(f"{widget_path.name}: resize bounds are inconsistent for {axis.lower()}.")
        configure = widget.get(ANDROID_NS + "configure", "")
        if not configure:
            problems.append(f"{widget_path.name}: missing configure activity.")
        else:
            source_exists(configure)
        details.append(f"Checked {widget_path.name}: height x width {rows} x 5 and horizontal/vertical resizing.")
    details.append(f"Checked {component_count} manifest components against source files.")

    java_result = "not run"
    if not java:
        problems.append("Java not found. Set JAVA_HOME or use --java PATH; Java syntax was not checked.")
    else:
        try:
            process = subprocess.run([java, str(project / "tools/JavaSyntaxCheck.java"), str(java_root)],
                                     capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
            java_result = (process.stdout + process.stderr).strip()
            if process.returncode:
                problems.append("Java syntax check failed: " + java_result)
            else:
                details.append(java_result)
        except (OSError, subprocess.TimeoutExpired) as exc:
            problems.append(f"Could not run Java syntax check: {exc}")
    return {"status": "passed" if not problems else "failed", "checks": details,
            "errors": problems, "java_files": len(java_files), "xml_files": len(xml_files),
            "android_sdk_build_performed": False, "device_tests_performed": False,
            "limitations": "Static XML/resource/metadata checks and Java parse only. Android API type checking, resource linking, APK packaging and device behavior require a real Android SDK build and device tests."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=ROOT)
    parser.add_argument("--java", help="Path to Java 17+ java executable with jdk.compiler")
    parser.add_argument("--report", type=Path, help="Optional JSON report destination")
    args = parser.parse_args()
    java = args.java
    if not java and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            java = str(candidate)
    java = java or shutil.which("java")
    try:
        result = check(args.project.resolve(), java)
    except (OSError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    for detail in result["checks"]:
        print(detail)
    for error in result["errors"]:
        print("FAIL: " + error, file=sys.stderr)
    print(result["limitations"])
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
