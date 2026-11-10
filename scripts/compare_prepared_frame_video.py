"""Locate clean prepared images in nearby recorded display frames; never assume clock equality."""
import argparse
import json
import re
import statistics
from pathlib import Path
import cv2
import numpy as np
from analyze_prepared_frame_capture import parse as parse_frames
from analyze_scroll_alignment import detect_censors
from screenrecord_timestamps import parse as parse_timestamps


def match_score(source, candidate, mask):
    if source.shape != candidate.shape or source.shape != mask.shape:
        raise ValueError("Mismatched image geometry")
    # Background-only white areas must not win a match. Score visible edges/content from either
    # image, excluding censor interiors/borders detected before downscaling the recording.
    feature = (np.abs(cv2.Sobel(source, cv2.CV_32F, 0, 1, ksize=3)) > 12)
    feature |= np.abs(cv2.Sobel(candidate, cv2.CV_32F, 0, 1, ksize=3)) > 12
    usable = feature & (mask == 0)
    support = int(np.count_nonzero(usable))
    if support < 200:
        return None, support
    error = np.minimum(np.abs(source.astype(np.float32) - candidate.astype(np.float32)), 64)
    return float(np.mean(error[usable])), support


def compare(video, source_root, trace):
    text = trace.read_text(encoding="utf-8-sig")
    statuses = parse_frames(text)
    if not statuses["complete"] or statuses["failed"] or statuses["dropped"]:
        raise ValueError("Incomplete private frame capture")
    files = sorted(source_root.glob("frame-*.png"))
    saved = {r["timestampMs"] for r in statuses["records"] if r["status"] == "saved"}
    timestamps = {int(path.stem.removeprefix("frame-")) for path in files}
    if timestamps != saved or len(files) != len(saved) or len(files) > 64:
        raise ValueError("Saved-frame/file parity failure")
    metadata = parse_timestamps(video.read_bytes())
    offsets = []
    for line in text.splitlines():
        clock = re.search(r"^\s*(\d+\.\d+) .*CAPTURE_SPAN .* uptimeMs=(\d+)", line)
        if clock:
            offsets.append(round(float(clock[1]) * 1e9) - int(clock[2]) * 1_000_000)
    if len(offsets) < 10:
        raise ValueError("Insufficient trace clocks")
    offset = statistics.median(offsets)
    times = [(value - offset) / 1e6 for value in metadata["epochNs"]]
    sources = {}
    for path in files:
        source = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if source is None:
            raise ValueError("Undecodable source frame")
        sources[int(path.stem.removeprefix("frame-"))] = source
    shapes = {source.shape for source in sources.values()}
    if len(shapes) != 1:
        raise ValueError("Mixed source geometry")
    height, width = shapes.pop()
    top, bottom = height // 5, height * 19 // 20
    candidates = {timestamp: [] for timestamp in sources}
    capture = cv2.VideoCapture(str(video))
    count = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        if count >= len(times):
            raise ValueError("Extra decoded frames")
        targets = [timestamp for timestamp in sources if abs(timestamp - times[count]) <= 250]
        if targets:
            mask, _ = detect_censors(frame, 0)
            mask = cv2.resize(mask, (width, height), interpolation=cv2.INTER_NEAREST)[top:bottom]
            mask = cv2.dilate(mask, np.ones((3, 3), np.uint8))
            gray = cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (width, height))[top:bottom]
            for timestamp in targets:
                score, support = match_score(sources[timestamp][top:bottom], gray, mask)
                if score is not None:
                    candidates[timestamp].append(dict(frame=count, timeMs=times[count],
                                                      offsetMs=times[count] - timestamp,
                                                      score=score, support=support))
        count += 1
    capture.release()
    if count != metadata["frameCount"]:
        raise ValueError("Video/timing count mismatch")
    records = []
    for timestamp, matches in candidates.items():
        ranked = sorted(matches, key=lambda entry: entry["score"])
        if not ranked:
            records.append(dict(timestampMs=timestamp, matched=False))
            continue
        near = min(matches, key=lambda entry: abs(entry["offsetMs"]))
        best = ranked[0]
        plateau = [entry for entry in ranked if entry["score"] <= best["score"] + .5]
        records.append(dict(timestampMs=timestamp, matched=True, best=best, nearest=near,
                            nearMinusBestScore=near["score"] - best["score"],
                            plateauMinOffsetMs=min(entry["offsetMs"] for entry in plateau),
                            plateauMaxOffsetMs=max(entry["offsetMs"] for entry in plateau),
                            topCandidates=ranked[:5]))
    return dict(savedFrames=len(saved), decodedFrames=count, timingFrames=metadata["frameCount"],
                clockSpreadMs=(max(offsets) - min(offsets)) / 1e6, records=records,
                limitations=["Photometric matching is diagnostic, not proof of compositor latency.",
                             "Occlusion, compression and repeated patterns can produce ambiguous minima.",
                             "Only a bounded +/-250ms search; boundary matches are not exact offsets."])


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("video", type=Path)
    cli.add_argument("source_root", type=Path)
    cli.add_argument("trace", type=Path)
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    result = compare(args.video, args.source_root, args.trace)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "records"}, indent=2))
