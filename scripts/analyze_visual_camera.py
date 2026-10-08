"""Strict diagnostic reader; proposed corrections are not rendered or accuracy-validated."""
import json
import re
import sys
from pathlib import Path

RECORD = re.compile(
    r"ROW_CAMERA previousMs=(-?\d+) currentMs=(\d+) scopeValid=(true|false) "
    r"accepted=(true|false) uncertain=(true|false) horizontal=(true|false) "
    r"frameMilliY=(-?\d+) eventFrameMilliY=(-?\d+) correctionMilliY=(-?\d+) cameraMilliY=(-?\d+)")


def parse(text):
    raw = 0
    records = []
    for line in text.splitlines():
        if "ROW_CAMERA " not in line:
            continue
        raw += 1
        payload = line[line.index("ROW_CAMERA "):].strip()
        provenance = re.findall(r" pixelTimeKnown=(true|false)", payload)
        if len(provenance) > 1:
            continue
        pixel_known = provenance[0] == "true" if provenance else None
        payload = re.sub(r" pixelTimeKnown=(true|false)", "", payload)
        match = RECORD.fullmatch(payload)
        if not match:
            continue
        previous, current = int(match[1]), int(match[2])
        scope, accepted, uncertain, horizontal = (match[i] == "true" for i in range(3, 7))
        if accepted and (not scope or horizontal or pixel_known is False
                         or previous < 0 or not 0 < current - previous <= 750):
            continue
        frame, event_frame, correction, camera = (int(match[i]) / 1000 for i in range(7, 11))
        if not accepted and correction != 0:
            continue
        records.append(dict(previousMs=previous, currentMs=current, scopeValid=scope,
                            accepted=accepted, uncertain=uncertain, horizontal=horizontal,
                            pixelTimeKnown=pixel_known,
                            frameY=frame, eventFrameY=event_frame, correctionY=correction, cameraY=camera))
    return dict(rawRecords=raw, parsedRecords=len(records), complete=raw > 0 and raw == len(records),
                acceptedRecords=sum(r["accepted"] for r in records),
                uncertainRecords=sum(r["uncertain"] for r in records), records=records,
                provenanceComplete=bool(records) and all(r["pixelTimeKnown"] is not None for r in records),
                renderingAuthority=False, accuracyValidated=False)


if __name__ == "__main__":
    result = parse(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
