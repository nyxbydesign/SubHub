"""Strict native-geometry diagnostic; missing/ambiguous nodes are never motion samples."""
import json
import re
import sys
from pathlib import Path

RECORD = re.compile(r"CHROME_GEOMETRY sample=(\d+) id=([A-Za-z0-9_]+) startMs=(\d+) endMs=(\d+) "
                    r"status=(ok|missing|ambiguous) visible=(true|false) rect=(-?\d+),(-?\d+),(-?\d+),(-?\d+)")


def parse(text):
    raw = 0
    records = []
    for line in text.splitlines():
        if "CHROME_GEOMETRY " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("CHROME_GEOMETRY "):].strip())
        if not match:
            continue
        start, end = int(match[3]), int(match[4])
        if end < start:
            continue
        rect = [int(match[i]) for i in range(7, 11)]
        usable = match[5] == "ok" and match[6] == "true" and rect[2] > rect[0] and rect[3] > rect[1]
        records.append(dict(sample=int(match[1]), id=match[2], startMs=start, endMs=end,
                            status=match[5], visible=match[6] == "true", rect=rect, usable=usable))
    groups = {}
    for record in records:
        if record["usable"]:
            groups.setdefault(record["id"], []).append(record)
    summary = {key: dict(samples=len(group), minTop=min(r["rect"][1] for r in group),
                         maxTop=max(r["rect"][1] for r in group),
                         minBottom=min(r["rect"][3] for r in group),
                         maxBottom=max(r["rect"][3] for r in group),
                         maxReadMs=max(r["endMs"] - r["startMs"] for r in group))
               for key, group in groups.items()}
    return dict(rawRecords=raw, parsedRecords=len(records), complete=raw > 0 and raw == len(records),
                usableRecords=sum(r["usable"] for r in records), summary=summary, records=records,
                cameraAuthority=False)


if __name__ == "__main__":
    result = parse(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
