"""Verify a one-shot no-new-screenshot fixture; not a performance or visual acceptance gate."""
import json
import re
import sys
from pathlib import Path
from analyze_capture_spans import analyze as analyze_spans

GAP = re.compile(r"CAPTURE_GAP phase=(start|end) nowMs=(\d+) untilMs=(\d+)")
DISPATCH = re.compile(r"CAPTURE_SPAN id=(\d+) stage=dispatch uptimeMs=(\d+) requestAgeMs=(\d+)")
CALLBACK = re.compile(r"CAPTURE_SPAN id=(\d+) stage=callback-success uptimeMs=(\d+) requestAgeMs=(\d+)")
HOLD = re.compile(r"SPATIAL_CACHE_HOLD id=(\d+)")
GESTURE = re.compile(r"GAP_GESTURE_(START|END) id=(\d+)")


def analyze(text):
    raw = 0
    gaps, dispatches, callbacks, holds = [], [], [], []
    starts, ends = [], []
    invalid = 0
    for index, line in enumerate(text.splitlines()):
        if "CAPTURE_GAP " in line:
            raw += 1
            match = GAP.fullmatch(line[line.index("CAPTURE_GAP "):].strip())
            if not match:
                invalid += 1
                continue
            gaps.append((match[1], int(match[2]), int(match[3]), index))
        if "CAPTURE_SPAN " in line:
            record = line[line.index("CAPTURE_SPAN "):].strip()
            dispatch, callback = DISPATCH.fullmatch(record), CALLBACK.fullmatch(record)
            if dispatch:
                dispatches.append((int(dispatch[1]), int(dispatch[2])))
            if callback:
                callbacks.append(int(callback[1]))
        if "SPATIAL_CACHE_HOLD " in line:
            match = HOLD.fullmatch(line[line.index("SPATIAL_CACHE_HOLD "):].strip())
            if match:
                holds.append(index)
            else:
                invalid += 1
        if "GAP_GESTURE_" in line:
            match = GESTURE.fullmatch(line[line.index("GAP_GESTURE_"):].strip())
            if match:
                (starts if match[1] == "START" else ends).append(int(match[2]))
            else:
                invalid += 1
    valid = len(gaps) == 2 and gaps[0][0] == "start" and gaps[1][0] == "end"
    if valid:
        start, end = gaps
        valid = start[2] - start[1] == 5000 and end[2] == start[2] and end[1] >= start[2]
    inside = sum(start[1] <= time < end[1] for _, time in dispatches) if valid else None
    resumed_ids = {identity for identity, time in dispatches if time >= end[1]} if valid else set()
    resumed = sum(identity in resumed_ids for identity in callbacks)
    span = analyze_spans(text.splitlines())
    complete = (raw == 2 and valid and invalid == 0 and span["malformedRecords"] == 0
                and inside == 0 and resumed > 0 and starts == list(range(10)) and ends == starts)
    return dict(rawGapRecords=raw, parsedGapRecords=len(gaps), invalidRecords=invalid,
                malformedCaptureRecords=span["malformedRecords"], complete=bool(complete),
                observedGapMs=end[1] - start[1] if valid else None,
                dispatchesDuringGap=inside, freshCallbacksAfterGap=resumed,
                holdRecordsBetweenGapMarkers=sum(start[3] < index < end[3] for index in holds) if valid else 0,
                gestures=len(starts), visualAcceptance=False)


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
