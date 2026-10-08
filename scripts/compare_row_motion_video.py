"""Compare accepted shadow row displacements with timestamp-matched recorded page motion."""
import argparse
import bisect
import json
import re
import statistics
from pathlib import Path
import cv2
from analyze_row_motion import parse as parse_rows
from analyze_scroll_alignment import detect_censors, page_motion
from screenrecord_timestamps import parse as parse_timestamps


def accumulate_motion(edges, first, last):
    selected = [edges.get(index) for index in range(first + 1, last + 1)]
    if not selected or any(value is None or value[0] is None for value in selected):
        return None, 0
    return sum(value[0] for value in selected), min(value[1] for value in selected)


def compare(video, trace):
    text = trace.read_text(encoding="utf-8-sig")
    rows = parse_rows(text)
    if not rows["complete"] or not rows["rawRecords"]:
        raise ValueError("Incomplete row parsing")
    timing = parse_timestamps(video.read_bytes())
    offsets = []
    for line in text.splitlines():
        clock = re.search(r"^\s*(\d+\.\d+) .*CAPTURE_SPAN .* uptimeMs=(\d+)", line)
        if clock:
            offsets.append(round(float(clock[1]) * 1_000_000_000) - int(clock[2]) * 1_000_000)
    if len(offsets) < 10:
        raise ValueError("Insufficient paired trace clocks")
    clock_offset = int(statistics.median(offsets))
    clock_spread_ms = (max(offsets) - min(offsets)) / 1_000_000
    times = [(epoch - clock_offset) / 1_000_000 for epoch in timing["epochNs"]]
    selected = set()
    required_edges = set()
    pairs = []
    for row in rows["records"]:
        if not row["accepted"]:
            continue
        indexes = []
        errors = []
        for target in (row["previousMs"], row["currentMs"]):
            insertion = bisect.bisect_left(times, target)
            choices = [i for i in (insertion - 1, insertion) if 0 <= i < len(times)]
            index = min(choices, key=lambda i: abs(times[i] - target))
            indexes.append(index)
            errors.append(abs(times[index] - target))
        entry = dict(row, videoFrameIndexes=indexes, timestampErrorsMs=errors,
                     opticalMeasured=False)
        pairs.append(entry)
        if clock_spread_ms > 20 or max(errors) > 30 or indexes[0] >= indexes[1]:
            entry["reason"] = "timestamp-alignment-uncertain"
        else:
            selected.update(range(indexes[0], indexes[1] + 1))
            required_edges.update(range(indexes[0] + 1, indexes[1] + 1))
    capture = cv2.VideoCapture(str(video))
    edges = {}
    previous = None
    previous_index = -1
    count = 0
    video_height = 0
    scale = 1.0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        if count in selected:
            height, width = frame.shape[:2]
            video_height = height
            scale = 480 / width
            top, bottom = height // 5, height * 19 // 20
            crop = cv2.resize(frame[top:bottom], (480, round((bottom - top) * scale)))
            mask, _ = detect_censors(crop, round(top * scale))
            current = (cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY), mask)
            if count in required_edges and previous_index == count - 1:
                edges[count] = page_motion(previous[0], current[0], previous[1], current[1])
            previous, previous_index = current, count
        count += 1
    capture.release()
    if count != timing["frameCount"]:
        raise ValueError("Decoded video / timestamp frame-count mismatch")
    # All rows in this run must use one source geometry before applying a pixel scale.
    heights = {int(m[1]) for m in re.finditer(r"ROW_MOTION .*? sourceHeight=(\d+)", text)}
    if len(heights) != 1:
        raise ValueError("Mixed or missing source geometry")
    source_height = heights.pop()
    for pair in pairs:
        if "reason" in pair:
            continue
        dy, features = accumulate_motion(edges, *pair["videoFrameIndexes"])
        if dy is None:
            pair["reason"] = "insufficient-optical-features"
            continue
        source_dy = dy / scale * source_height / video_height
        pair.update(opticalMeasured=True, opticalSourceDy=source_dy, minimumOpticalFeatures=features,
                    residualSourcePx=pair["sourceDy"] - source_dy)
    return dict(decodedFrames=count, timestampFrames=timing["frameCount"],
                clockPairCount=len(offsets), clockSpreadMs=clock_spread_ms,
                rawRowRecords=rows["rawRecords"], parsedRowRecords=rows["parsedRecords"],
                acceptedPairs=len(pairs), opticalPairs=sum(p["opticalMeasured"] for p in pairs),
                pairs=pairs, opticalMethod="sum-of-adjacent-frame-flow", releaseGateEligible=False,
                limitations=["Nearest recorded frames can differ from screenshot pixels by up to 30ms.",
                             "Global feature flow can mix moving page and fixed chrome.",
                             "Recording overhead and occluded features limit accuracy."])


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("video", type=Path)
    cli.add_argument("trace", type=Path)
    cli.add_argument("--output", required=True, type=Path)
    args = cli.parse_args()
    result = compare(args.video, args.trace)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "pairs"}, indent=2))
