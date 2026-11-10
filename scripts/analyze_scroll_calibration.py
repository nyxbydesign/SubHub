#!/usr/bin/env python3
"""Merge a Censor Lab MediaProjection video with Accessibility motion telemetry.

The output is a shadow calibration report. It never changes application behavior and never writes
raw pixels, text, URLs, package names, or Accessibility identifiers. OpenCV is intentionally an
optional local-analysis dependency; the Android application does not ship it.
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import re
import statistics
import tempfile
import zipfile
from pathlib import Path
from typing import Iterable, Sequence

try:
    import cv2  # type: ignore
    import numpy as np  # type: ignore
except ImportError as error:  # pragma: no cover - exercised by the CLI environment
    raise SystemExit(
        "OpenCV is required for video analysis. Install opencv-python-headless in an "
        "isolated environment or pass its target directory through PYTHONPATH."
    ) from error


SCROLL_FIELD_RE = re.compile(r"(?P<key>[A-Za-z][A-Za-z0-9_]*)=(?P<value>\S+)")
MARKER_RE = re.compile(r"MARKER (SYNC_(?P<phase>START|STOP)_UI_VISIBLE)")


def percentile(values: Sequence[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100.0
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return float(ordered[lower])
    fraction = position - lower
    return float(ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction)


def robust_median(values: Sequence[float]) -> float:
    return float(statistics.median(values)) if values else 0.0


def read_bundle(bundle: Path, directory: Path) -> tuple[Path, Path, Path]:
    with zipfile.ZipFile(bundle) as archive:
        wanted = {"manifest.json", "trace.ndjson", "screen-recording.mp4"}
        names = set(archive.namelist())
        missing = wanted - names
        if missing:
            raise ValueError(f"Bundle is missing: {', '.join(sorted(missing))}")
        for name in wanted:
            archive.extract(name, directory)
    return (
        directory / "manifest.json",
        directory / "trace.ndjson",
        directory / "screen-recording.mp4",
    )


def parse_scroll_message(message: str, relative_seconds: float) -> dict | None:
    if not message.startswith("SCROLL_EVENT "):
        return None
    fields = {match.group("key"): match.group("value")
              for match in SCROLL_FIELD_RE.finditer(message)}
    required = ("id", "source", "rawDx", "rawDy", "dx", "dy", "evidence",
                "amplified")
    missing = [key for key in required if key not in fields]
    if missing:
        raise ValueError(f"SCROLL_EVENT missing fields: {', '.join(missing)}")
    source_uptime = int(fields["sourceUptimeMs"]) \
        if "sourceUptimeMs" in fields else None
    received_uptime = int(fields["receivedUptimeMs"]) \
        if "receivedUptimeMs" in fields else None
    source_relative = relative_seconds
    if source_uptime is not None and received_uptime is not None:
        source_relative += (source_uptime - received_uptime) / 1000.0
    scroll_id = int(fields["id"])
    touch_id = int(fields.get("touchId", "0"))
    return {
        "time": relative_seconds,
        "fit_time": source_relative,
        "scroll_id": scroll_id,
        "gesture": touch_id if touch_id > 0 else scroll_id,
        "source": fields["source"],
        "raw_dx": int(fields["rawDx"]),
        "raw_dy": int(fields["rawDy"]),
        "dx": int(fields["dx"]),
        "dy": int(fields["dy"]),
        "evidence": fields["evidence"],
        "amplified": fields["amplified"] == "true",
        "source_uptime_ms": source_uptime,
        "received_uptime_ms": received_uptime,
        "surface": fields.get("surfaceToken", "unknown"),
        "surface_confidence": int(fields.get("surfaceConfidence", "0")),
        "observed_surface_confidence": int(
            fields.get("observedSurfaceConfidence", fields.get("surfaceConfidence", "0"))),
        "surface_cacheable": fields.get("surfaceCacheable") == "true",
        "surface_decision": fields.get("surfaceDecision", "UNKNOWN"),
        "motion_token": fields.get("motionToken", "unknown"),
        "document": int(fields.get("documentEpoch", "0")),
        "touch_id": touch_id,
        "touch_active": fields.get("touchActive") == "true",
    }


def parse_touch_message(message: str, relative_seconds: float) -> dict | None:
    if not message.startswith("CALIBRATION_TOUCH "):
        return None
    fields = {match.group("key"): match.group("value")
              for match in SCROLL_FIELD_RE.finditer(message)}
    required = ("id", "phase", "sourceUptimeMs", "receivedUptimeMs")
    missing = [key for key in required if key not in fields]
    if missing:
        raise ValueError(f"CALIBRATION_TOUCH missing fields: {', '.join(missing)}")
    source_uptime = int(fields["sourceUptimeMs"])
    received_uptime = int(fields["receivedUptimeMs"])
    return {
        "time": relative_seconds,
        "fit_time": relative_seconds + (source_uptime - received_uptime) / 1000.0,
        "gesture": int(fields["id"]),
        "phase": fields["phase"],
        "source_uptime_ms": source_uptime,
        "received_uptime_ms": received_uptime,
        "event_age_ms": int(fields.get("eventAgeMs", "0")),
        "duration_ms": int(fields.get("durationMs", "0")),
    }


def parse_calibration_boxes(value: str, tracked: bool) -> list[dict]:
    if not value:
        return []
    boxes: list[dict] = []
    expected = 5 if tracked else 4
    for encoded in value.split(";"):
        values = encoded.split(",")
        if len(values) != expected:
            raise ValueError("CALIBRATION_SCENE box has invalid field count")
        numbers = [int(item) for item in values]
        if numbers[2] <= 0 or numbers[3] <= 0:
            raise ValueError("CALIBRATION_SCENE box has invalid dimensions")
        box = {"x": numbers[0], "y": numbers[1], "width": numbers[2],
               "height": numbers[3]}
        if tracked:
            box["trackId"] = numbers[4]
        boxes.append(box)
    return boxes


def parse_scene_message(message: str, relative_seconds: float) -> dict | None:
    if not message.startswith("CALIBRATION_SCENE "):
        return None
    if " liveBoxes=" not in message or " cachedBoxes=" not in message:
        raise ValueError("CALIBRATION_SCENE missing box payloads")
    before_cached, cached_boxes = message.split(" cachedBoxes=", 1)
    before_live, live_boxes = before_cached.split(" liveBoxes=", 1)
    fields = {match.group("key"): match.group("value")
              for match in SCROLL_FIELD_RE.finditer(before_live)}
    fields["liveBoxes"] = live_boxes
    fields["cachedBoxes"] = cached_boxes
    required = ("id", "size", "trackCamera", "currentCamera", "encodedLive",
                "encodedCached", "liveBoxes", "cachedBoxes")
    missing = [key for key in required if key not in fields]
    if missing:
        raise ValueError(f"CALIBRATION_SCENE missing fields: {', '.join(missing)}")
    size = fields["size"].split("x")
    track_camera = fields["trackCamera"].split(",")
    current_camera = fields["currentCamera"].split(",")
    if len(size) != 2 or len(track_camera) != 2 or len(current_camera) != 2:
        raise ValueError("CALIBRATION_SCENE geometry has invalid dimensions")
    if int(size[0]) <= 0 or int(size[1]) <= 0:
        raise ValueError("CALIBRATION_SCENE viewport has invalid dimensions")
    live = parse_calibration_boxes(fields["liveBoxes"], True)
    cached = parse_calibration_boxes(fields["cachedBoxes"], False)
    if len(live) != int(fields["encodedLive"]) \
            or len(cached) != int(fields["encodedCached"]):
        raise ValueError("CALIBRATION_SCENE encoded count mismatch")
    return {
        "time": relative_seconds,
        "id": fields["id"],
        "width": int(size[0]),
        "height": int(size[1]),
        "track_camera_x": int(track_camera[0]),
        "track_camera_y": int(track_camera[1]),
        "current_camera_x": int(current_camera[0]),
        "current_camera_y": int(current_camera[1]),
        "live": live,
        "cached": cached,
    }


def read_trace(path: Path, video_started_elapsed_nanos: int) -> dict:
    scrolls: list[dict] = []
    touches: list[dict] = []
    markers: dict[str, float] = {}
    scenes: list[dict] = []
    scene_parse_errors = 0
    draws = 0
    invalid_lines = 0
    records = 0
    sequence_monotonic = True
    elapsed_monotonic = True
    uptime_monotonic = True
    duplicate_sequences = 0
    seen_sequences: set[int] = set()
    previous_sequence = -1
    previous_elapsed = -1
    previous_uptime = -1
    privacy_violations = 0
    with path.open("r", encoding="utf-8") as stream:
        for raw_line in stream:
            try:
                event = json.loads(raw_line)
            except json.JSONDecodeError:
                invalid_lines += 1
                continue
            records += 1
            sequence = int(event.get("sequence", -1))
            observed_uptime = int(event.get("observedUptimeMillis", -1))
            if sequence in seen_sequences:
                duplicate_sequences += 1
            seen_sequences.add(sequence)
            sequence_monotonic &= sequence > previous_sequence
            elapsed_nanos = int(event.get("elapsedNanos", 0))
            elapsed_monotonic &= elapsed_nanos >= previous_elapsed
            if observed_uptime >= 0:
                uptime_monotonic &= observed_uptime >= previous_uptime
                previous_uptime = observed_uptime
            previous_sequence = sequence
            previous_elapsed = elapsed_nanos
            message = str(event.get("message", ""))
            lowered = message.lower()
            if "http://" in lowered or "https://" in lowered:
                privacy_violations += 1
            relative_seconds = (elapsed_nanos - video_started_elapsed_nanos) / 1e9
            try:
                scroll = parse_scroll_message(message, relative_seconds)
            except (TypeError, ValueError):
                invalid_lines += 1
                scroll = None
            if scroll is not None:
                scrolls.append(scroll)
            try:
                touch = parse_touch_message(message, relative_seconds)
            except (TypeError, ValueError):
                invalid_lines += 1
                touch = None
            if touch is not None:
                touches.append(touch)
            try:
                scene = parse_scene_message(message, relative_seconds)
            except (IndexError, TypeError, ValueError):
                scene_parse_errors += 1
                scene = None
            if scene is not None:
                scenes.append(scene)
            marker = MARKER_RE.search(message)
            if marker:
                markers[marker.group("phase").lower()] = relative_seconds
            if event.get("tag") == "CensorMotion" and message.startswith("DRAW"):
                draws += 1
    scrolls.sort(key=lambda item: item["time"])
    return {
        "scrolls": scrolls,
        "touches": touches,
        "scenes": scenes,
        "markers": markers,
        "sceneRecords": len(scenes),
        "sceneParseErrors": scene_parse_errors,
        "drawRecords": draws,
        "invalidLines": invalid_lines,
        "validation": {
            "records": records,
            "sequenceMonotonic": sequence_monotonic,
            "elapsedMonotonic": elapsed_monotonic,
            "uptimeMonotonic": uptime_monotonic,
            "duplicateSequences": duplicate_sequences,
            "privacyViolations": privacy_violations,
        },
    }


def marker_score(frame) -> float:
    """Score a wide, uniform, saturated rectangle such as the existing LAB sync card."""
    height, width = frame.shape[:2]
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    saturation = hsv[:, :, 1]
    value = hsv[:, :, 2]
    mask = ((saturation > 90) & (value > 55)).astype(np.uint8) * 255
    count, _, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    best = 0.0
    for index in range(1, count):
        x, y, component_width, component_height, area = stats[index]
        if component_width < width * 0.55:
            continue
        if component_height < height * 0.025 or component_height > height * 0.24:
            continue
        fill = area / max(1.0, component_width * component_height)
        best = max(best, fill * component_width / width)
    return best


def censor_mask(frame):
    blue, green, red = cv2.split(frame)
    neon = (
        (red > 145) & (blue > 55) & (green < 155)
        & ((red.astype(np.int16) - green.astype(np.int16)) > 45)
    ).astype(np.uint8) * 255
    neon = cv2.dilate(neon, np.ones((7, 7), np.uint8), iterations=1)
    filled = np.zeros(neon.shape, dtype=np.uint8)
    contours, _ = cv2.findContours(neon, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for contour in contours:
        x, y, width, height = cv2.boundingRect(contour)
        if width < 22 or height < 22 or width * height < 900:
            continue
        pad = 8
        cv2.rectangle(filled, (max(0, x - pad), max(0, y - pad)),
                      (min(filled.shape[1] - 1, x + width + pad),
                       min(filled.shape[0] - 1, y + height + pad)), 255, -1)
    return filled


def estimate_flow(previous_gray, current_gray, previous_mask, current_mask,
                  display_scale_x: float, display_scale_y: float) -> dict:
    usable = cv2.bitwise_not(cv2.bitwise_or(previous_mask, current_mask))
    points = cv2.goodFeaturesToTrack(
        previous_gray,
        maxCorners=360,
        qualityLevel=0.012,
        minDistance=5,
        blockSize=5,
        mask=usable,
        useHarrisDetector=False,
    )
    if points is None or len(points) < 24:
        return {"accepted": False, "reason": "low-texture", "features": 0}
    moved, status, errors = cv2.calcOpticalFlowPyrLK(
        previous_gray,
        current_gray,
        points,
        None,
        winSize=(21, 21),
        maxLevel=3,
        criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 24, 0.01),
    )
    if moved is None or status is None:
        return {"accepted": False, "reason": "flow-failed", "features": 0}
    valid = status.reshape(-1) == 1
    if errors is not None:
        valid &= errors.reshape(-1) < 30.0
    source = points.reshape(-1, 2)[valid]
    target = moved.reshape(-1, 2)[valid]
    if len(target):
        target_x = np.clip(np.round(target[:, 0]).astype(np.int32),
                           0, current_mask.shape[1] - 1)
        target_y = np.clip(np.round(target[:, 1]).astype(np.int32),
                           0, current_mask.shape[0] - 1)
        unmasked = current_mask[target_y, target_x] == 0
        source = source[unmasked]
        target = target[unmasked]
    if len(source) < 24:
        return {"accepted": False, "reason": "few-tracks", "features": int(len(source))}
    vectors = target - source
    median = np.median(vectors, axis=0)
    residuals = np.linalg.norm(vectors - median, axis=1)
    residual_median = float(np.median(residuals))
    threshold = max(1.25, residual_median * 3.5)
    inliers = residuals <= threshold
    if int(np.count_nonzero(inliers)) < 18:
        return {"accepted": False, "reason": "few-inliers", "features": int(len(source))}
    source = source[inliers]
    vectors = vectors[inliers]
    median = np.median(vectors, axis=0)
    residuals = np.linalg.norm(vectors - median, axis=1)
    image_height = previous_gray.shape[0]
    band_vectors: list[np.ndarray] = []
    for band in range(4):
        top = image_height * band / 4.0
        bottom = image_height * (band + 1) / 4.0
        selected = (source[:, 1] >= top) & (source[:, 1] < bottom)
        if int(np.count_nonzero(selected)) >= 5:
            band_vectors.append(np.median(vectors[selected], axis=0))
    magnitude = float(np.linalg.norm(median))
    agreement_limit = max(1.75, magnitude * 0.28)
    agreeing = sum(
        1 for vector in band_vectors
        if float(np.linalg.norm(vector - median)) <= agreement_limit
    )
    band_consensus = agreeing / 4.0
    inlier_fraction = len(vectors) / max(1, len(points))
    texture_coverage = min(1.0, len(points) / 180.0)
    residual_score = math.exp(-robust_median(residuals.tolist()) / 3.0)
    confidence = max(0.0, min(1.0,
        inlier_fraction * texture_coverage * band_consensus * residual_score * 2.2))
    accepted = agreeing >= 3 and confidence >= 0.34
    return {
        "accepted": accepted,
        "reason": "translation" if accepted else "mixed-or-reflow",
        "dx": float(median[0] * display_scale_x),
        "dy": float(median[1] * display_scale_y),
        "features": int(len(points)),
        "inliers": int(len(vectors)),
        "inlierFraction": float(inlier_fraction),
        "coveredBands": int(len(band_vectors)),
        "agreeingBands": int(agreeing),
        "residualMedianPx": float(robust_median(residuals.tolist())),
        "residualP95Px": percentile(residuals.tolist(), 95),
        "confidence": confidence,
    }


def analyze_video(path: Path, display_width: int, display_height: int,
                  sample_stride: int = 1) -> dict:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise ValueError(f"Could not open video: {path}")
    fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
    video_width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    video_height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    if video_width <= 0 or video_height <= 0:
        raise ValueError("Video has no usable dimensions")
    scale_x = display_width / video_width
    scale_y = display_height / video_height
    samples: list[dict] = []
    marker_candidates: list[dict] = []
    previous = None
    previous_mask = None
    previous_time = None
    last_time = 0.0
    frame_index = -1
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        frame_index += 1
        if frame_index % max(1, sample_stride) != 0:
            continue
        pts_ms = float(capture.get(cv2.CAP_PROP_POS_MSEC) or 0.0)
        time_seconds = pts_ms / 1000.0 if pts_ms > 0 else (
            frame_index / fps if fps > 0 else frame_index / 60.0)
        last_time = max(last_time, time_seconds)
        score = marker_score(frame)
        if score >= 0.48:
            marker_candidates.append({"frame": frame_index, "time": time_seconds,
                                      "score": score})
        analysis_width = 216
        analysis_height = max(96, round(frame.shape[0] * analysis_width / frame.shape[1]))
        resized = cv2.resize(frame, (analysis_width, analysis_height),
                             interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        mask = cv2.resize(censor_mask(frame), (analysis_width, analysis_height),
                          interpolation=cv2.INTER_NEAREST)
        # Fixed system/browser bars carry little page motion and can dominate global flow.
        top = round(gray.shape[0] * 0.10)
        bottom = round(gray.shape[0] * 0.94)
        left = round(gray.shape[1] * 0.02)
        right = round(gray.shape[1] * 0.98)
        gray = gray[top:bottom, left:right]
        mask = mask[top:bottom, left:right]
        if previous is not None and previous_mask is not None and previous_time is not None:
            interval_ms = (time_seconds - previous_time) * 1000.0
            if interval_ms <= 0.0 or interval_ms > 100.0:
                flow = {
                    "accepted": False,
                    "reason": "timing-gap",
                    "features": 0,
                }
            else:
                flow = estimate_flow(
                    previous, gray, previous_mask, mask,
                    display_width / analysis_width,
                    display_height / analysis_height,
                )
            flow.update({
                "frame": frame_index,
                "time": time_seconds,
                "intervalMs": interval_ms,
            })
            samples.append(flow)
        previous = gray
        previous_mask = mask
        previous_time = time_seconds
    capture.release()
    return {
        "fps": fps,
        "videoWidth": video_width,
        "videoHeight": video_height,
        "frameCount": frame_index + 1,
        "durationSeconds": last_time,
        "ptsSource": "opencv-pos-msec",
        "markerCandidates": marker_candidates,
        "samples": samples,
    }


def marker_runs(candidates: Sequence[dict], maximum_gap_seconds: float = 0.25) -> list[list[dict]]:
    ordered = sorted(candidates, key=lambda item: float(item["time"]))
    runs: list[list[dict]] = []
    for candidate in ordered:
        if (not runs or float(candidate["time"])
                - float(runs[-1][-1]["time"]) > maximum_gap_seconds):
            runs.append([candidate])
        else:
            runs[-1].append(candidate)
    return runs


def marker_anchor(candidates: Sequence[dict], phase: str, duration: float) -> float | None:
    if phase == "start":
        near = [item for item in candidates if item["time"] <= min(4.0, duration * 0.25)]
        runs = marker_runs(near)
        return float(runs[0][0]["time"]) if runs else None
    near = [item for item in candidates if item["time"] >= max(0.0, duration - 4.0)]
    runs = marker_runs(near)
    # Both trace markers denote UI-card onset. Pair the stop trace with the rising edge of the
    # final saturated run, never the last frame of the one-second shutdown hold.
    return float(runs[-1][0]["time"]) if runs else None


def align_video_times(video: dict, trace_markers: dict) -> dict:
    duration = float(video["durationSeconds"])
    start_video = marker_anchor(video["markerCandidates"], "start", duration)
    stop_video = marker_anchor(video["markerCandidates"], "stop", duration)
    start_trace = trace_markers.get("start")
    stop_trace = trace_markers.get("stop")
    if (start_video is not None and stop_video is not None
            and start_trace is not None and stop_trace is not None
            and stop_video - start_video > 0.5):
        slope = (stop_trace - start_trace) / (stop_video - start_video)
        offset = start_trace - slope * start_video
        strength = "two-marker"
    elif start_video is not None and start_trace is not None:
        slope = 1.0
        offset = start_trace - start_video
        strength = "start-marker-only"
    else:
        slope = 1.0
        offset = 0.0
        strength = "manifest-video-start-only"
    for sample in video["samples"]:
        sample["traceTime"] = offset + slope * sample["time"]
    drift_seconds = abs(slope - 1.0) * max(0.0, duration)
    frame_seconds = 1.0 / max(1.0, float(video.get("fps", 0.0)))
    drift_limit = max(0.025, frame_seconds * 1.5)
    eligible = strength == "two-marker" and drift_seconds <= drift_limit
    return {
        "strength": strength,
        "eligible": eligible,
        "offsetSeconds": offset,
        "slope": slope,
        "clockDriftSeconds": drift_seconds,
        "clockDriftLimitSeconds": drift_limit,
        "startVideoSeconds": start_video,
        "stopVideoSeconds": stop_video,
        "startTraceSeconds": start_trace,
        "stopTraceSeconds": stop_trace,
    }


def cumulative_series(items: Iterable[dict], x_key: str, y_key: str,
                      time_key: str) -> tuple[list[float], list[float], list[float]]:
    times: list[float] = []
    xs: list[float] = []
    ys: list[float] = []
    x = 0.0
    y = 0.0
    ordered = sorted(items, key=lambda value: value.get(time_key, value.get("time", 0.0)))
    for item in ordered:
        x += float(item.get(x_key, 0.0))
        y += float(item.get(y_key, 0.0))
        times.append(float(item.get(time_key, item.get("time", 0.0))))
        xs.append(x)
        ys.append(y)
    return times, xs, ys


def sample_step(times: Sequence[float], values: Sequence[float], at: float) -> float:
    index = bisect.bisect_right(times, at) - 1
    return float(values[index]) if index >= 0 else 0.0


def build_pairs(visual: Sequence[dict], events: Sequence[dict], latency: float,
                window_seconds: float = 0.12) -> list[dict]:
    accepted = sorted(
        [item for item in visual if item.get("accepted")
         and item.get("confidence", 0.0) >= 0.34],
        key=lambda item: item["traceTime"],
    )
    if not accepted or not events:
        return []
    visual_times, visual_x, visual_y = cumulative_series(
        accepted, "dx", "dy", "traceTime")
    # A presentation controller cannot react until Accessibility has delivered the callback.
    # Keep source/fit time for event-truth diagnostics, but never let it make a live model
    # consume information before receipt.
    event_times, event_x, event_y = cumulative_series(events, "dx", "dy", "time")
    pairs: list[dict] = []
    for index, time_value in enumerate(visual_times):
        start = time_value - window_seconds
        vx = visual_x[index] - sample_step(visual_times, visual_x, start)
        vy = visual_y[index] - sample_step(visual_times, visual_y, start)
        event_end = time_value - latency
        event_start = start - latency
        ex = sample_step(event_times, event_x, event_end) - sample_step(
            event_times, event_x, event_start)
        ey = sample_step(event_times, event_y, event_end) - sample_step(
            event_times, event_y, event_start)
        if abs(ex) + abs(ey) < 1.0 and abs(vx) + abs(vy) < 1.0:
            continue
        segment = int(accepted[index].get("gesture", max(0.0, time_value) / 0.75))
        pairs.append({"time": time_value, "vx": vx, "vy": vy, "ex": ex, "ey": ey,
                      "train": segment % 2 == 0})
    return pairs


def fit_gain(pairs: Sequence[dict], train: bool) -> float:
    selected = [pair for pair in pairs if pair["train"] == train]
    numerator = sum(pair["ex"] * pair["vx"] + pair["ey"] * pair["vy"]
                    for pair in selected)
    denominator = sum(pair["ex"] ** 2 + pair["ey"] ** 2 for pair in selected)
    if denominator <= 1e-6:
        return 1.0
    return max(0.5, min(2.0, numerator / denominator))


def residuals(pairs: Sequence[dict], gain: float, train: bool) -> list[float]:
    return [
        math.hypot(pair["vx"] - gain * pair["ex"],
                   pair["vy"] - gain * pair["ey"])
        for pair in pairs if pair["train"] == train
    ]


def fit_profile(
        visual: Sequence[dict],
        events: Sequence[dict],
        promotion_allowed: bool = False) -> dict | None:
    best = None
    for latency_step in range(0, 31):
        latency = latency_step * 0.010
        for tau in (0.005, 0.015, 0.025, 0.040, 0.060, 0.080, 0.120, 0.160, 0.220):
            pairs = build_kernel_pairs(visual, events, latency, tau)
            train_pairs = [pair for pair in pairs if pair["train"]]
            eval_pairs = [pair for pair in pairs if not pair["train"]]
            if len(train_pairs) < 12 or len(eval_pairs) < 8:
                continue
            gain = fit_gain(pairs, True)
            train_residuals = residuals(pairs, gain, True)
            score = (percentile(train_residuals, 50) or 0.0) + 0.35 * (
                percentile(train_residuals, 90) or 0.0)
            candidate = {"latencySeconds": latency, "tauSeconds": tau,
                         "gain": gain, "pairs": pairs, "score": score}
            if best is None or score < best["score"]:
                best = candidate
    if best is None:
        return None
    pairs = best.pop("pairs")
    trained_eval = residuals(pairs, best["gain"], False)
    baseline_pairs = build_kernel_pairs(visual, events, 0.0, 0.005)
    baseline_eval = residuals(baseline_pairs, 1.0, False)
    trained_p95 = percentile(trained_eval, 95)
    baseline_p95 = percentile(baseline_eval, 95)
    improvement = None
    if trained_p95 is not None and baseline_p95 and baseline_p95 > 1e-6:
        improvement = 1.0 - trained_p95 / baseline_p95
    return {
        "model": "causal-exponential-event-kernel-v1",
        "latencyMs": round(best["latencySeconds"] * 1000.0, 3),
        "tauMs": round(best["tauSeconds"] * 1000.0, 3),
        "gain": round(best["gain"], 6),
        "trainScore": round(best["score"], 4),
        "trainPairs": sum(1 for pair in pairs if pair["train"]),
        "heldOutPairs": sum(1 for pair in pairs if not pair["train"]),
        "baselineHeldOutResidualPx": {
            "p50": percentile(baseline_eval, 50), "p95": baseline_p95,
            "max": max(baseline_eval) if baseline_eval else None,
        },
        "trainedHeldOutResidualPx": {
            "p50": percentile(trained_eval, 50), "p95": trained_p95,
            "max": max(trained_eval) if trained_eval else None,
        },
        "heldOutP95Improvement": improvement,
        "promotionEligible": bool(promotion_allowed
                                  and improvement is not None and improvement >= 0.30
                                  and len(trained_eval) >= 8
                                  and (trained_p95 or float("inf")) <= 24.0),
        "promotionBlockedReasons": [] if promotion_allowed else [
            "input-alignment-or-validation-ineligible"
        ],
    }


def causal_exponential_cdf(value: float) -> float:
    return 0.0 if value <= 0.0 else 1.0 - math.exp(-value)


def build_kernel_pairs(visual: Sequence[dict], events: Sequence[dict], latency: float,
                       tau: float) -> list[dict]:
    accepted = sorted(
        [item for item in visual if item.get("accepted")
         and item.get("confidence", 0.0) >= 0.34],
        key=lambda item: item["traceTime"],
    )
    ordered_events = sorted(events, key=lambda item: item["time"])
    event_times = [float(item["time"]) + latency
                   for item in ordered_events]
    if not accepted or not ordered_events:
        return []
    safe_tau = max(0.005, tau)
    pairs: list[dict] = []
    for sample in accepted:
        end = float(sample["traceTime"])
        interval = max(0.004, min(0.100, float(sample.get("intervalMs", 16.667)) / 1000.0))
        start = end - interval
        lower = bisect.bisect_left(event_times, start - safe_tau * 8.0)
        # A presentation model can only consume an event after its receipt timestamp.
        # Never let a symmetric kernel leak a future callback into an earlier video frame.
        upper = bisect.bisect_right(event_times, end)
        ex = 0.0
        ey = 0.0
        for index in range(lower, upper):
            center = event_times[index]
            fraction = causal_exponential_cdf((end - center) / safe_tau) \
                - causal_exponential_cdf((start - center) / safe_tau)
            ex += float(ordered_events[index].get("dx", 0.0)) * fraction
            ey += float(ordered_events[index].get("dy", 0.0)) * fraction
        vx = float(sample.get("dx", 0.0))
        vy = float(sample.get("dy", 0.0))
        if abs(ex) + abs(ey) < 0.25 and abs(vx) + abs(vy) < 0.25:
            continue
        gesture = int(sample.get("gesture", 0))
        pairs.append({"time": end, "vx": vx, "vy": vy, "ex": ex, "ey": ey,
                      "train": gesture % 2 == 0})
    return pairs


def fit_profile_legacy(visual: Sequence[dict], events: Sequence[dict]) -> dict | None:
    """Retained only for comparing the original step-window prototype."""
    best = None
    for step in range(-50, 71):
        latency = step * 0.005
        pairs = build_pairs(visual, events, latency)
        train_pairs = [pair for pair in pairs if pair["train"]]
        eval_pairs = [pair for pair in pairs if not pair["train"]]
        if len(train_pairs) < 12 or len(eval_pairs) < 8:
            continue
        gain = fit_gain(pairs, True)
        train_residuals = residuals(pairs, gain, True)
        score = (percentile(train_residuals, 50) or 0.0) + 0.35 * (
            percentile(train_residuals, 90) or 0.0)
        candidate = {"latencySeconds": latency, "gain": gain, "pairs": pairs,
                     "score": score}
        if best is None or score < best["score"]:
            best = candidate
    if best is None:
        return None
    pairs = best.pop("pairs")
    trained_eval = residuals(pairs, best["gain"], False)
    baseline_pairs = build_pairs(visual, events, 0.0)
    baseline_eval = residuals(baseline_pairs, 1.0, False)
    trained_p95 = percentile(trained_eval, 95)
    baseline_p95 = percentile(baseline_eval, 95)
    improvement = None
    if trained_p95 is not None and baseline_p95 and baseline_p95 > 1e-6:
        improvement = 1.0 - trained_p95 / baseline_p95
    return {
        "model": "step-window-v0",
        "latencyMs": round(best["latencySeconds"] * 1000.0, 3),
        "gain": round(best["gain"], 6),
        "trainScore": round(best["score"], 4),
        "trainPairs": sum(1 for pair in pairs if pair["train"]),
        "heldOutPairs": sum(1 for pair in pairs if not pair["train"]),
        "baselineHeldOutResidualPx": {
            "p50": percentile(baseline_eval, 50), "p95": baseline_p95,
            "max": max(baseline_eval) if baseline_eval else None,
        },
        "trainedHeldOutResidualPx": {
            "p50": percentile(trained_eval, 50), "p95": trained_p95,
            "max": max(trained_eval) if trained_eval else None,
        },
        "heldOutP95Improvement": improvement,
        "promotionEligible": bool(improvement is not None and improvement >= 0.30
                                  and len(trained_eval) >= 8),
    }


def visual_near_events(visual: Sequence[dict], events: Sequence[dict]) -> list[dict]:
    """Assign teacher samples only to the event burst/surface that could have produced them."""
    if not events:
        return []
    ordered_events = sorted(events, key=lambda item: item["time"])
    event_times = [float(item["time"]) for item in ordered_events]
    selected: list[dict] = []
    for sample in visual:
        time_value = float(sample.get("traceTime", -1e9))
        index = bisect.bisect_right(event_times, time_value) - 1
        before = ordered_events[index] if index >= 0 else None
        after = ordered_events[index + 1] if index + 1 < len(ordered_events) else None
        before_gap = time_value - float(before["time"]) \
            if before else float("inf")
        after_gap = float(after["time"]) - time_value \
            if after else float("inf")
        # Include onset just before a delivered event and the visual tail of a fling, but reject
        # unrelated app transitions and long idle/video spans.
        if before_gap > 1.20 and after_gap > 0.25:
            continue
        owner = before if before_gap <= 1.20 else after
        if owner is None:
            continue
        mapped = dict(sample)
        mapped["gesture"] = int(owner.get("gesture", 0))
        selected.append(mapped)
    return selected


def qualify_translation_gestures(visual: Sequence[dict], events: Sequence[dict]) -> dict:
    selected = visual_near_events(visual, events)
    event_groups: dict[int, list[dict]] = {}
    visual_groups: dict[int, list[dict]] = {}
    for event in events:
        event_groups.setdefault(int(event.get("gesture", 0)), []).append(event)
    for sample in selected:
        visual_groups.setdefault(int(sample.get("gesture", 0)), []).append(sample)
    accepted_ids: list[int] = []
    metrics: list[dict] = []
    for gesture, gesture_events in sorted(event_groups.items()):
        gesture_visual = visual_groups.get(gesture, [])
        event_dx = sum(float(item.get("dx", 0.0)) for item in gesture_events)
        event_dy = sum(float(item.get("dy", 0.0)) for item in gesture_events)
        event_abs = sum(abs(float(item.get("dx", 0.0))) + abs(float(item.get("dy", 0.0)))
                        for item in gesture_events)
        visual_dx = sum(float(item.get("dx", 0.0)) for item in gesture_visual)
        visual_dy = sum(float(item.get("dy", 0.0)) for item in gesture_visual)
        event_net = event_dy if abs(event_dy) >= abs(event_dx) else event_dx
        visual_net = visual_dy if abs(event_dy) >= abs(event_dx) else visual_dx
        coherent = abs(event_net) >= max(64.0, event_abs * 0.25)
        same_direction = event_net * visual_net > 0.0
        ratio = abs(visual_net) / max(1.0, abs(event_net))
        accepted = (len(gesture_visual) >= 4 and event_abs >= 80.0 and coherent
                    and same_direction and 0.35 <= ratio <= 2.75)
        if accepted:
            accepted_ids.append(gesture)
        metrics.append({
            "gesture": gesture,
            "events": len(gesture_events),
            "visualSamples": len(gesture_visual),
            "eventNetPx": event_net,
            "visualNetPx": visual_net,
            "visualEventRatio": ratio,
            "accepted": accepted,
            "reason": "translation" if accepted else (
                "jitter-or-reversal" if not coherent else
                "direction-mismatch" if not same_direction else
                "unsupported-ratio-or-samples"),
        })
    accepted_set = set(accepted_ids)
    return {
        "events": [item for item in events if int(item.get("gesture", 0)) in accepted_set],
        "visual": [item for item in selected if int(item.get("gesture", 0)) in accepted_set],
        "acceptedGestureIds": accepted_ids,
        "metrics": metrics,
    }


def trusted_scroll_event(event: dict) -> bool:
    return bool(event.get("surface_cacheable")) \
        and int(event.get("surface_confidence", 0)) >= 2 \
        and str(event.get("surface", "unknown")) != "unknown"


def validate_inputs(manifest: dict, video: dict, trace: dict, alignment: dict) -> dict:
    reasons: list[str] = []
    trace_validation = trace["validation"]
    expected_events = int(manifest.get("eventCount", -1))
    dropped_events = int(manifest.get("droppedEvents", -1))
    if expected_events != trace_validation["records"]:
        reasons.append("manifest-trace-count-mismatch")
    if dropped_events != 0:
        reasons.append("trace-events-dropped")
    if (trace["invalidLines"] or not trace_validation["sequenceMonotonic"]
            or not trace_validation["elapsedMonotonic"]
            or not trace_validation["uptimeMonotonic"]
            or trace_validation["duplicateSequences"]):
        reasons.append("trace-integrity-failed")
    if trace_validation["privacyViolations"]:
        reasons.append("trace-privacy-failed")
    if not alignment.get("eligible", False):
        reasons.append("video-trace-alignment-ineligible")
    starts = [item for item in trace["touches"] if item["phase"] == "start"]
    ends = [item for item in trace["touches"] if item["phase"] == "end"]
    start_by_id = {int(item["gesture"]): item for item in starts}
    end_by_id = {int(item["gesture"]): item for item in ends}
    unique_touch_ids = len(start_by_id) == len(starts) == len(end_by_id) == len(ends)
    ordered_touch_pairs = unique_touch_ids and all(
        touch_id > 0 and touch_id in end_by_id
        and float(end_by_id[touch_id]["fit_time"]) >= float(start["fit_time"])
        for touch_id, start in start_by_id.items()
    )
    if not starts or not ordered_touch_pairs:
        reasons.append("touch-boundaries-missing-or-unbalanced")
    trusted_events = [item for item in trace["scrolls"] if trusted_scroll_event(item)]
    trusted_moving = [item for item in trusted_events
                      if abs(int(item.get("dx", 0))) + abs(int(item.get("dy", 0))) > 0]
    if len(trusted_moving) < 24:
        reasons.append("insufficient-stable-surface-events")
    trusted_with_touch = [item for item in trusted_moving
                          if int(item.get("touch_id", 0)) in start_by_id]
    touch_coverage = len(trusted_with_touch) / max(1, len(trusted_moving))
    if trusted_moving and touch_coverage < 1.0:
        reasons.append("trusted-scroll-touch-coverage-incomplete")
    if trace.get("sceneParseErrors", 0):
        reasons.append("calibration-scene-parse-failed")
    appearance = manifest.get("capture", {}).get("overlayAppearance", {})
    border_color = int(appearance.get("borderColor", 0)) & 0xFFFFFFFF
    red = (border_color >> 16) & 0xFF
    green = (border_color >> 8) & 0xFF
    blue = border_color & 0xFF
    color_mask_eligible = bool(appearance.get("showBorder")) \
        and red > 145 and blue > 55 and green < 155 and red - green > 45
    if not color_mask_eligible:
        reasons.append("overlay-mask-appearance-unverified")
    intervals = [float(item.get("intervalMs", 0.0)) for item in video["samples"]
                 if 0.0 < float(item.get("intervalMs", 0.0)) <= 100.0]
    interval_p50 = percentile(intervals, 50)
    sampled_fps = 1000.0 / interval_p50 if interval_p50 else 0.0
    if sampled_fps < 50.0:
        reasons.append("teacher-sampling-below-50fps")
    moving = [item for item in video["samples"]
              if math.hypot(float(item.get("dx", 0.0)),
                            float(item.get("dy", 0.0))) >= 1.0]
    consensus = [item for item in moving if item.get("accepted")
                 and int(item.get("agreeingBands", 0)) >= 3]
    consensus_rate = len(consensus) / max(1, len(moving))
    if consensus_rate < 0.90:
        reasons.append("flow-consensus-below-90-percent")
    promotion_reasons = list(reasons)
    # A single Chromium calibration can validate a student, but cannot establish the documented
    # static/video/reflow or cross-refresh safety gates needed to affect normal App Mode.
    promotion_reasons.extend([
        "static-video-reflow-controls-not-provided",
        "cross-refresh-device-validation-not-provided",
    ])
    return {
        "evaluationEligible": not reasons,
        "promotionEligible": not promotion_reasons,
        "evaluationBlockedReasons": reasons,
        "promotionBlockedReasons": promotion_reasons,
        "sampledFpsP50": sampled_fps,
        "flowConsensusRate": consensus_rate,
        "trustedScrollEvents": len(trusted_events),
        "trustedMovingScrollEvents": len(trusted_moving),
        "touchStarts": len(starts),
        "touchEnds": len(ends),
        "trustedScrollTouchCoverage": touch_coverage,
        "overlayMaskStrategy": "neon-border-fill" if color_mask_eligible
                else "numeric-scenes-parsed-but-not-applied",
        "trace": trace_validation,
    }


def build_models(video: dict, trace: dict, promotion_allowed: bool = False) -> dict:
    accepted = [sample for sample in video["samples"] if sample.get("accepted")]
    trusted_events = [event for event in trace["scrolls"] if trusted_scroll_event(event)]
    surfaces: dict[str, list[dict]] = {}
    for event in trusted_events:
        key = f"{event['surface']}:{event.get('document', 0)}"
        surfaces.setdefault(key, []).append(event)
    models: dict[str, dict] = {}
    qualified = qualify_translation_gestures(accepted, trusted_events)
    models["gestureQualification"] = {
        "acceptedGestureIds": qualified["acceptedGestureIds"],
        "metrics": qualified["metrics"],
    }
    overall = fit_profile(
        qualified["visual"], qualified["events"], promotion_allowed)
    if overall is not None:
        models["deviceBaseline"] = overall
    per_surface = {}
    for token, events in surfaces.items():
        surface_qualified = qualify_translation_gestures(accepted, events)
        model = fit_profile(
            surface_qualified["visual"], surface_qualified["events"], promotion_allowed)
        if model is not None:
            model["acceptedGestureIds"] = surface_qualified["acceptedGestureIds"]
            per_surface[token] = model
    models["surfaces"] = per_surface
    return models


def summarize(
        video: dict,
        trace: dict,
        alignment: dict,
        validation: dict,
        models: dict) -> dict:
    accepted = [sample for sample in video["samples"] if sample.get("accepted")]
    confidences = [float(sample.get("confidence", 0.0)) for sample in accepted]
    intervals = [float(sample.get("intervalMs", 0.0)) for sample in video["samples"]]
    return {
        "schemaVersion": 1,
        "kind": "scroll-calibration-shadow-report",
        "video": {key: video[key] for key in (
            "fps", "videoWidth", "videoHeight", "frameCount", "durationSeconds",
            "ptsSource")},
        "alignment": alignment,
        "validation": validation,
        "trace": {
            "scrollEvents": len(trace["scrolls"]),
            "touchRecords": len(trace["touches"]),
            "surfaces": len({item["surface"] for item in trace["scrolls"]}),
            "sceneRecords": trace["sceneRecords"],
            "drawRecords": trace["drawRecords"],
            "invalidLines": trace["invalidLines"],
        },
        "flow": {
            "samples": len(video["samples"]),
            "accepted": len(accepted),
            "acceptanceRate": len(accepted) / max(1, len(video["samples"])),
            "confidenceP50": percentile(confidences, 50),
            "confidenceP95": percentile(confidences, 95),
            "intervalMsP50": percentile(intervals, 50),
            "intervalMsP95": percentile(intervals, 95),
        },
        "models": models,
        "privacy": {
            "rawPixelsStored": False,
            "textStored": False,
            "urlsStored": False,
            "packageNamesStored": False,
            "surfaceTokens": "session-salted",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--video", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples-output", type=Path)
    parser.add_argument("--flow-input", type=Path,
                        help="Reuse a previously emitted visual-flow NDJSON file")
    parser.add_argument("--sample-stride", type=int, default=1)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="subhub-calibration-") as temporary:
        if args.bundle:
            manifest_path, trace_path, video_path = read_bundle(
                args.bundle, Path(temporary))
        else:
            if not (args.manifest and args.trace and args.video):
                parser.error("Pass --bundle or all of --manifest, --trace, and --video")
            manifest_path, trace_path, video_path = args.manifest, args.trace, args.video
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        recording = manifest.get("recording", {})
        video_start = int(recording.get("startedElapsedNanos", 0))
        if video_start <= 0:
            raise ValueError("Manifest has no MediaProjection video start timestamp")
        device = manifest.get("device", {})
        trace = read_trace(trace_path, video_start)
        if args.flow_input:
            samples = [json.loads(line) for line in args.flow_input.read_text(
                encoding="utf-8").splitlines() if line.strip()]
            for sample in samples:
                interval_ms = float(sample.get("intervalMs", 0.0))
                if interval_ms <= 0.0 or interval_ms > 100.0:
                    sample["accepted"] = False
                    sample["reason"] = "timing-gap"
            probe = cv2.VideoCapture(str(video_path))
            fps = float(probe.get(cv2.CAP_PROP_FPS) or recording.get("frameRate", 0.0))
            video_width = int(probe.get(cv2.CAP_PROP_FRAME_WIDTH)
                              or recording.get("width", 0))
            video_height = int(probe.get(cv2.CAP_PROP_FRAME_HEIGHT)
                               or recording.get("height", 0))
            frame_count = int(probe.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
            probe.release()
            video = {
                "fps": fps,
                "videoWidth": video_width,
                "videoHeight": video_height,
                "frameCount": frame_count,
                "durationSeconds": max((float(item.get("time", 0.0))
                                        for item in samples), default=0.0),
                "ptsSource": "cached-opencv-pos-msec",
                "markerCandidates": [],
                "samples": samples,
            }
            alignment = {"strength": "prealigned-flow-input", "offsetSeconds": None,
                         "slope": None}
        else:
            video = analyze_video(
                video_path,
                int(device.get("displayWidth", recording.get("width", 1))),
                int(device.get("displayHeight", recording.get("height", 1))),
                max(1, args.sample_stride),
            )
            alignment = align_video_times(video, trace["markers"])
        validation = validate_inputs(manifest, video, trace, alignment)
        models = build_models(video, trace, validation["promotionEligible"])
        report = summarize(video, trace, alignment, validation, models)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if args.samples_output:
            args.samples_output.parent.mkdir(parents=True, exist_ok=True)
            with args.samples_output.open("w", encoding="utf-8") as output:
                for sample in video["samples"]:
                    output.write(json.dumps(sample, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
