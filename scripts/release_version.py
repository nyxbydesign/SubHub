#!/usr/bin/env python3
"""Read, validate, or update SubHub's single release-version source."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = ROOT / "version.properties"
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$")
CI_CODE_STRIDE = 100_000
MAX_ANDROID_CODE = 2_100_000_000


def read_version() -> tuple[int, str]:
    values: dict[str, str] = {}
    for line in VERSION_FILE.read_text(encoding="utf-8").splitlines():
        if line and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    try:
        code = int(values["VERSION_CODE"])
        name = values["VERSION_NAME"]
    except (KeyError, ValueError) as exc:
        raise SystemExit(f"Invalid {VERSION_FILE.name}: {exc}") from exc
    if not 1 <= code <= MAX_ANDROID_CODE:
        raise SystemExit("VERSION_CODE must be a valid positive Android integer")
    if not SEMVER.fullmatch(name):
        raise SystemExit("VERSION_NAME must be semantic versioning, for example 0.2.0")
    return code, name


def write_version(code: int, name: str) -> None:
    VERSION_FILE.write_text(
        f"VERSION_CODE={code}\nVERSION_NAME={name}\n",
        encoding="utf-8",
        newline="\n",
    )


def build_version(code: int, name: str, run_number: int | None = None,
                  dev: bool = False) -> tuple[int, str]:
    """Reserve a source-version bucket, then order all CI channels within it."""
    if run_number is None:
        if dev:
            raise ValueError("Development builds require a CI run number")
        return code, name
    if not 1 <= run_number < CI_CODE_STRIDE:
        raise ValueError("CI run number exhausted its version bucket; migrate the code scheme")
    effective = code * CI_CODE_STRIDE + run_number
    if effective > MAX_ANDROID_CODE:
        raise ValueError("CI version code exceeds Android's supported limit")
    if not SEMVER.fullmatch(name) or "-" in name:
        raise ValueError("CI builds require a stable semantic base version")
    return effective, f"{name}-dev.{run_number}" if dev else name


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Require an exact v<versionName> release tag")
    parser.add_argument("--set-version", help="Write a new semantic VERSION_NAME")
    parser.add_argument("--version-code", type=int, help="VERSION_CODE used with --set-version")
    parser.add_argument("--github-output", type=Path, help="Append name/code values for Actions")
    parser.add_argument("--ci-run-number", type=int, help="Release workflow sequence shared by both channels")
    parser.add_argument("--dev-build", action="store_true", help="Append the unique development suffix")
    args = parser.parse_args()

    old_code, old_name = read_version()
    if args.set_version:
        if not SEMVER.fullmatch(args.set_version):
            parser.error("--set-version must be semantic versioning")
        next_code = args.version_code if args.version_code is not None else old_code + 1
        if next_code <= old_code:
            parser.error("the new VERSION_CODE must be greater than the current code")
        write_version(next_code, args.set_version)

    code, name = read_version()
    if args.tag and args.tag != f"v{name}":
        raise SystemExit(f"Tag {args.tag!r} does not match VERSION_NAME {name!r}; expected v{name}")
    try:
        code, name = build_version(code, name, args.ci_run_number, args.dev_build)
    except ValueError as exception:
        parser.error(str(exception))

    if args.github_output:
        with args.github_output.open("a", encoding="utf-8", newline="\n") as output:
            output.write(f"version_code={code}\nversion_name={name}\nrelease_tag=v{name}\n")
    print(f"SubHub {name} (versionCode {code})")


if __name__ == "__main__":
    main()
