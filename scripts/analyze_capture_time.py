"""Report screenshot timestamp provenance separately from event-phase ordering."""
import json
import re
import sys
from pathlib import Path

RECORD = re.compile(r"CAPTURE_TIME id=(\d+) kind=(WINDOW_CLIENT_RECEIPT|DISPLAY_SERVER_COMPLETION|VERIFIED_PIXEL_CAPTURE) "
                    r"requestMs=(\d+) reportedMs=(\d+) callbackMs=(\d+) valid=(true|false) pixelTimeKnown=(true|false)")


def parse(text):
    raw = 0
    records = []
    for line in text.splitlines():
        if "CAPTURE_TIME " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("CAPTURE_TIME "):].strip())
        if not match:
            continue
        identity, request, reported, callback = (int(match[i]) for i in (1, 3, 4, 5))
        valid, known = match[6] == "true", match[7] == "true"
        if identity != request or valid != (request <= reported <= callback):
            continue
        if known != (valid and match[2] == "VERIFIED_PIXEL_CAPTURE"):
            continue
        records.append(dict(id=identity, kind=match[2], requestMs=request, reportedMs=reported,
                            callbackMs=callback, valid=valid, pixelTimeKnown=known))
    return dict(rawRecords=raw, parsedRecords=len(records), complete=raw > 0 and raw == len(records),
                exactPixelTimes=sum(r["pixelTimeKnown"] for r in records), records=records,
                captureAgeMetric="request-to-publication-not-pixel-age")


if __name__ == "__main__":
    result = parse(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
