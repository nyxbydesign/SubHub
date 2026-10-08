"""Opt-in source correction counts; tracker updates are not proof of visible improvement."""
import json
import re
import statistics
import sys
from pathlib import Path

RECORD = re.compile(r"SOURCE_TRACK v=1 id=(\d+) enabled=(true|false) corrected=(\d+) extraDy=(-?\d+)")
RECORD_V2 = re.compile(r"SOURCE_TRACK v=2 id=(\d+) enabled=(true|false) corrected=(\d+) "
                       r"global=(\d+) local=(\d+) pairs=(\d+) maxAbsExtraDy=(\d+) cpuUs=(\d+)")


def analyze(text):
    raw = 0
    rows = []
    for line in text.splitlines():
        if "SOURCE_TRACK " not in line:
            continue
        raw += 1
        record = line[line.index("SOURCE_TRACK "):].strip()
        match = RECORD.fullmatch(record)
        if match is None:
            match = RECORD_V2.fullmatch(record)
            if match is None:
                continue
            identity, enabled, corrected, global_count, local_count, pairs, maximum, cpu = match.groups()
            corrected, global_count, local_count, pairs, maximum, cpu = map(int,
                    (corrected, global_count, local_count, pairs, maximum, cpu))
            if (corrected != global_count + local_count or local_count > pairs or pairs > 17
                    or pairs == 17 and local_count != 0 or (corrected == 0) != (maximum == 0)
                    or enabled == "false" and (corrected != 0 or pairs != 0)):
                continue
            rows.append(dict(id=int(identity), enabled=enabled == "true", corrected=corrected,
                             extraDy=None, maxAbsExtraDy=maximum, globalTracks=global_count,
                             localTracks=local_count, pairs=pairs, cpuUs=cpu))
            continue
        identity, enabled, corrected, dy = match.groups()
        corrected, dy = int(corrected), int(dy)
        if (enabled == "false" and corrected != 0) or ((corrected == 0) != (dy == 0)):
            continue
        rows.append(dict(id=int(identity), enabled=enabled == "true", corrected=corrected, extraDy=dy,
                         maxAbsExtraDy=abs(dy), globalTracks=None, localTracks=None, pairs=None, cpuUs=None))
    typed = [row for row in rows if row["cpuUs"] is not None]
    classified = bool(rows) and len(typed) == len(rows)
    return dict(rawRecords=raw, parsedRecords=len(rows), complete=raw > 0 and raw == len(rows),
                correctedUpdates=sum(row["corrected"] > 0 for row in rows),
                correctedTracks=sum(row["corrected"] for row in rows),
                maxAbsExtraDy=max((row["maxAbsExtraDy"] for row in rows), default=0),
                typedRecords=len(typed), classificationComplete=classified,
                globalTracks=sum(row["globalTracks"] for row in typed) if classified else None,
                localTracks=sum(row["localTracks"] for row in typed) if classified else None,
                localUpdates=sum(row["localTracks"] > 0 for row in typed) if classified else None,
                budgetExceededUpdates=sum(row["pairs"] == 17 for row in typed) if classified else None,
                correctionCpuMedianUs=statistics.median(row["cpuUs"] for row in typed) if classified else None,
                correctionCpuMaxUs=max((row["cpuUs"] for row in typed), default=None) if classified else None,
                visualAcceptance=False, records=rows)


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
