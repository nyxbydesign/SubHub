#!/usr/bin/env python3
"""Generate SubHub's catalog from the pinned Unicode Emoji 18.0 emoji-test.txt."""

import argparse
import gzip
import hashlib
from pathlib import Path
import re

SHA256 = "8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a"
GROUPS = (
    "Smileys & Emotion", "People & Body", "Component", "Animals & Nature",
    "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags",
)
COUNTS = (172, 2430, 9, 161, 132, 221, 85, 268, 224, 270)
ENTRY = re.compile(r"([0-9A-F ]+)\s*;\s*(fully-qualified|component)\s+# \S+ E[\d.]+ (.+)")


def catalog(data: bytes) -> bytes:
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("Expected the pinned Unicode Emoji 18.0 emoji-test.txt")
    groups: dict[str, list[str]] = {}
    seen: set[str] = set()
    for line in data.decode("utf-8").splitlines():
        if line.startswith("# group: "):
            group = line.removeprefix("# group: ")
            groups[group] = []
        elif match := ENTRY.fullmatch(line):
            emoji = "".join(chr(int(cp, 16)) for cp in match[1].split())
            if emoji in seen:
                raise ValueError(f"Duplicate sequence {match[1]}")
            seen.add(emoji)
            groups[group].append(f"{emoji}\t{match[3]}")
    if tuple(groups) != GROUPS or tuple(map(len, groups.values())) != COUNTS:
        raise ValueError("Unicode catalog groups or count changed")
    rows = [line for group, entries in groups.items() for line in ("#" + group, *entries)]
    return ("\n".join(rows) + "\n").encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with args.output.open("wb") as destination:
        with gzip.GzipFile(fileobj=destination, mode="wb", filename="", mtime=0) as stream:
            stream.write(catalog(args.source.read_bytes()))


if __name__ == "__main__":
    main()
