"""Validate event-cache write admission records independently from cache query counts."""
import json
import re
import sys
from pathlib import Path

RECORD = re.compile(r"WORLD_CACHE_QUERY source=\S+ entries=(\d+) inserted=(\d+) updated=(\d+) "
                    r"evicted=(\d+) viewportReset=(true|false) candidates=(\d+) camera=-?\d+,-?\d+ "
                    r"documentEpoch=\d+(?: cacheWriteAccepted=(true|false) sourceGeneration=(\d+))?")


def analyze(text):
    raw = 0
    statuses = []
    for line in text.splitlines():
        if "WORLD_CACHE_QUERY " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("WORLD_CACHE_QUERY "):].strip())
        if match is None:
            continue
        if match[7] == "false" and (any(int(match[index]) for index in (2, 3, 4)) or match[5] == "true"):
            continue
        statuses.append(None if match[7] is None else match[7] == "true")
    known = bool(statuses) and all(status is not None for status in statuses)
    return dict(rawRecords=raw, parsedRecords=len(statuses), complete=raw > 0 and raw == len(statuses),
                writeStatusKnown=known, skippedWrites=statuses.count(False) if known else None,
                admittedWrites=statuses.count(True) if known else None)


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
