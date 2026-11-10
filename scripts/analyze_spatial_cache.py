"""Strict numeric-only spatial-cache diagnostics. Not a visual acceptance gate."""
import json
import re
import statistics
import sys
from pathlib import Path

FRAME = re.compile(r"SPATIAL_CACHE_FRAME id=(\d+) status=(BASELINE|REGISTERED|UNMATCHED|NO_MOTION_HINT|INVALID|STALE) known=(true|false) cpuUs=(\d+)")
QUERY = re.compile(r"SPATIAL_CACHE_QUERY id=(\d+) entries=(\d+) inserted=(\d+) candidates=(\d+)")
HOLD = re.compile(r"SPATIAL_CACHE_HOLD id=(\d+)")
WRITE = re.compile(r"SPATIAL_CACHE_WRITE id=(\d+) known=(true|false) input=(\d+) crop=(\d+) unconfirmed=(\d+) faces=(\d+) faceCrop=(\d+)")
APPLIED = re.compile(r"SPATIAL_CACHE_APPLIED kind=(scene|event) input=(\d+) admitted=(\d+)")


def analyze(text):
    raw = parsed = 0
    frames, queries, holds, writes, applications = [], [], [], [], []
    for line in text.splitlines():
        if "SPATIAL_CACHE_" not in line:
            continue
        raw += 1
        record = line[line.index("SPATIAL_CACHE_"):].strip()
        frame, query = FRAME.fullmatch(record), QUERY.fullmatch(record)
        if frame:
            identity, status, known, cpu = frame.groups()
            if (known == "true") != (status in ("BASELINE", "REGISTERED")):
                continue
            frames.append(dict(id=int(identity), status=status, known=known == "true", cpuUs=int(cpu)))
        elif query:
            identity, entries, inserted, candidates = map(int, query.groups())
            if candidates > 24 or entries > 2048 or inserted > entries:
                continue
            queries.append(dict(id=identity, entries=entries, inserted=inserted, candidates=candidates))
        elif HOLD.fullmatch(record):
            holds.append(int(HOLD.fullmatch(record)[1]))
        elif WRITE.fullmatch(record):
            match = WRITE.fullmatch(record)
            identity, known, count, crop, unconfirmed, faces, face_crop = match.groups()
            count, crop, unconfirmed, faces, face_crop = map(int, (count, crop, unconfirmed, faces, face_crop))
            if crop + unconfirmed > count or faces > count or face_crop > min(faces, crop):
                continue
            if known == "false" and count != 0:
                continue
            writes.append(dict(input=count, crop=crop, unconfirmed=unconfirmed, faces=faces, faceCrop=face_crop))
        elif APPLIED.fullmatch(record):
            match = APPLIED.fullmatch(record)
            count, admitted = int(match[2]), int(match[3])
            if admitted > count:
                continue
            applications.append(dict(input=count, admitted=admitted))
        else:
            continue
        parsed += 1
    cpu = [frame["cpuUs"] for frame in frames]
    return dict(rawRecords=raw, parsedRecords=parsed, complete=raw > 0 and raw == parsed,
                frames=len(frames), knownFrames=sum(frame["known"] for frame in frames),
                cpuMedianUs=statistics.median(cpu) if cpu else None, cpuMaxUs=max(cpu) if cpu else None,
                queries=len(queries), queriesWithRegions=sum(query["candidates"] > 0 for query in queries),
                inserted=sum(query["inserted"] for query in queries),
                maxEntries=max([query["entries"] for query in queries], default=0),
                holdEvents=len(holds), writes=len(writes),
                observedFaces=sum(row["faces"] for row in writes),
                cropRejectedFaces=sum(row["faceCrop"] for row in writes),
                cropRejected=sum(row["crop"] for row in writes),
                unconfirmed=sum(row["unconfirmed"] for row in writes),
                applications=len(applications),
                applicationsWithCache=sum(row["admitted"] > 0 for row in applications),
                inputButNoAdmission=sum(row["input"] > 0 and row["admitted"] == 0 for row in applications),
                visualAcceptance=False)


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
