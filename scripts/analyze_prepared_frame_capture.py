"""Validate prepared-frame capture status records without inspecting image contents."""
import re

RECORD = re.compile(r"ROW_FRAME timestampMs=(\d+) status=(saved|dropped|failed) width=(\d+) height=(\d+)")


def parse(text):
    raw = 0
    records = []
    for line in text.splitlines():
        if "ROW_FRAME " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("ROW_FRAME "):].strip())
        if match and 0 < int(match[3]) <= 512 and 0 < int(match[4]) <= 512:
            records.append(dict(timestampMs=int(match[1]), status=match[2],
                                width=int(match[3]), height=int(match[4])))
    saved = [r["timestampMs"] for r in records if r["status"] == "saved"]
    return dict(rawRecords=raw, parsedRecords=len(records), saved=len(saved),
                dropped=sum(r["status"] == "dropped" for r in records),
                failed=sum(r["status"] == "failed" for r in records),
                complete=raw > 0 and raw == len(records) and len(saved) == len(set(saved)), records=records)
