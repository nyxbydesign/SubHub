#!/usr/bin/env python3
"""Inspect peer-sync APK metadata and native packaging before guest installation."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def metadata(text, expected):
    lines = text.splitlines()
    packages = [line for line in lines if line.startswith("package: ")]
    if len(packages) != 1:
        raise ValueError("Missing or duplicate package metadata")
    fields = re.findall(r"(\w+)='([^']*)'", packages[0])
    data = dict(fields)
    if len(data) != len(fields) or data.get("name") != expected:
        raise ValueError("Unexpected package metadata")
    code = data.get("versionCode")
    if code in (None, "") and expected == "com.subhub.app.test":
        version = 0  # Android PackageParser's absent manifest versionCode default.
        declared = False
    elif code is not None and re.fullmatch(r"[0-9]+", code):
        version = int(code)
        declared = True
    else:
        raise ValueError("Malformed version code")
    if version > 2_100_000_000 or (expected == "com.subhub.app" and version <= 0):
        raise ValueError("Invalid product version code")
    sdk = [line for line in lines if line.startswith(("sdkVersion:", "minSdkVersion:"))]
    if len(sdk) != 1 or not re.fullmatch(r"(?:sdkVersion|minSdkVersion):'\d+'", sdk[0]):
        raise ValueError("Missing or duplicate minimum SDK")
    return {"package": expected, "versionCode": version, "versionDeclared": declared,
            "versionName": data.get("versionName", ""),
            "minSdk": int(sdk[0].split("'")[1])}


def elf_alignment(data):
    if data[:4] != b"\x7fELF" or data[5] != 1 or data[4] not in (1, 2):
        raise ValueError("Unsupported native library format")
    wide = data[4] == 2
    offset = struct.unpack_from("<Q" if wide else "<I", data, 32 if wide else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if wide else 42)
    alignments = []
    for index in range(count):
        entry = offset + index * size
        if struct.unpack_from("<I", data, entry)[0] == 1:
            alignments.append(struct.unpack_from("<Q" if wide else "<I", data, entry + (48 if wide else 28))[0])
    if not alignments:
        raise ValueError("Native library lacks load segments")
    return alignments


def inspect(apk, expected, aapt, native=False):
    result = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True, encoding="utf-8", check=True)
    parsed = metadata(result.stdout, expected)
    if parsed["minSdk"] > 26:
        raise ValueError("Minimum Android version increased")
    report = {**parsed, "path": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    if native:
        inventory = {}
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if name.startswith("lib/") and name.endswith(("/libiroh_ffi.so", "/libjnidispatch.so")):
                    data = archive.read(name)
                    inventory[name] = {"sha256": hashlib.sha256(data).hexdigest(), "alignment": elf_alignment(data)}
            report["nativeAbiInventoryPassed"] = all(
                {name.split("/")[1] for name in inventory if name.endswith("/" + library)} == ABIS
                for library in ("libiroh_ffi.so", "libjnidispatch.so")
            )
        report["native"] = inventory
        report["nativePageAlignmentPassed"] = all(all(value >= 16384 for value in item["alignment"]) for item in inventory.values())
    return report


def parser_fixtures():
    for ending in ("\n", "\r\n"):
        text = ending.join(["package: name='com.subhub.app' versionCode='123' versionName='0.1.0'", "minSdkVersion:'26'"])
        assert metadata(text, "com.subhub.app")["versionCode"] == 123
        test = ending.join(["package: name='com.subhub.app.test' versionCode='' versionName=''", "sdkVersion:'26'"])
        assert metadata(test, "com.subhub.app.test")["versionCode"] == 0
        assert not metadata(test, "com.subhub.app.test")["versionDeclared"]
        for bad in (text.replace("'123'", "'-1'"), text.replace("'123'", "'1.5'"), text + ending + "sdkVersion:'26'", text + ending + text):
            try:
                metadata(bad, "com.subhub.app")
            except ValueError:
                pass
            else:
                raise AssertionError("Accepted malformed metadata")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path)
    parser.add_argument("--test", type=Path)
    parser.add_argument("--aapt", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    parser_fixtures()
    if args.self_test:
        print("Metadata fixtures passed for LF and CRLF")
        return 0
    if not all((args.app, args.test, args.aapt)):
        parser.error("--app, --test and --aapt are required")
    report = {"app": inspect(args.app, "com.subhub.app", args.aapt, native=True),
              "test": inspect(args.test, "com.subhub.app.test", args.aapt)}
    encoded = json.dumps(report, indent=2)
    if args.output:
        if args.output.exists():
            raise FileExistsError("Evidence output already exists")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if report["app"]["nativePageAlignmentPassed"] and report["app"]["nativeAbiInventoryPassed"] else 2


if __name__ == "__main__":
    sys.exit(main())
