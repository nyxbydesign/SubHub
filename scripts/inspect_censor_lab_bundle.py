#!/usr/bin/env python3
"""Validate and unpack a user-shared SubHub Censor Lab bundle.

The generated ``trace.log`` is directly consumable by ``analyze_censor_trace.ps1``. The script
never extracts unknown paths and refuses to merge into an existing output directory.
"""

from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil
import zipfile


ALLOWED_ENTRIES = {
    "manifest.json",
    "trace.ndjson",
    "README.txt",
    "screen-recording.mp4",
}
ENTRY_LIMITS = {
    "manifest.json": 1 * 1024 * 1024,
    "trace.ndjson": 64 * 1024 * 1024,
    "README.txt": 1 * 1024 * 1024,
    "screen-recording.mp4": 2 * 1024 * 1024 * 1024,
}


def inspect_bundle(path: Path) -> tuple[dict, list[dict], set[str]]:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        unknown = names - ALLOWED_ENTRIES
        if unknown:
            raise ValueError(f"Unexpected bundle entries: {sorted(unknown)}")
        required = {"manifest.json", "trace.ndjson", "README.txt"}
        if not required.issubset(names):
            raise ValueError(f"Missing bundle entries: {sorted(required - names)}")
        for info in archive.infolist():
            if info.file_size > ENTRY_LIMITS[info.filename]:
                raise ValueError(f"Bundle entry is too large: {info.filename}")
            if (info.filename != "screen-recording.mp4" and info.compress_size > 0
                    and info.file_size / info.compress_size > 200):
                raise ValueError(f"Bundle entry has an unsafe compression ratio: {info.filename}")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("schemaVersion") != 1:
            raise ValueError("Unsupported Censor Lab schema")
        has_video = "screen-recording.mp4" in names
        recording = manifest.get("recording")
        if isinstance(recording, dict) and "inAppScreenRecording" in recording:
            privacy = manifest.get("privacy", {})
            if bool(manifest.get("videoAttached")) != has_video:
                raise ValueError("Manifest videoAttached does not match the bundle")
            if bool(recording.get("inAppScreenRecording")) != has_video:
                raise ValueError("Manifest recording state does not match the bundle")
            if bool(privacy.get("pixelCapture")) != has_video:
                raise ValueError("Manifest pixel-capture state does not match the bundle")
            expected_kind = "mediaprojection-display" if has_video else "none"
            if recording.get("videoKind") != expected_kind:
                raise ValueError("Manifest video kind does not match the bundle")
        events = []
        for line_number, raw in enumerate(
                archive.read("trace.ndjson").decode("utf-8").splitlines(), start=1):
            if not raw.strip():
                continue
            event = json.loads(raw)
            required_event = {"sequence", "elapsedNanos", "wallMillis", "tag", "message"}
            if not required_event.issubset(event):
                raise ValueError(f"Trace line {line_number} is incomplete")
            events.append(event)
        if manifest.get("eventCount") != len(events):
            raise ValueError(
                f"Manifest event count {manifest.get('eventCount')} != trace count {len(events)}")
        return manifest, events, names


def render_log(events: list[dict]) -> str:
    lines = []
    for event in events:
        instant = datetime.fromtimestamp(event["wallMillis"] / 1_000.0)
        timestamp = instant.strftime("%m-%d %H:%M:%S.") + f"{instant.microsecond // 1000:03d}"
        lines.append(f"{timestamp} {event['tag']}: {event['message']}")
    return "\n".join(lines) + ("\n" if lines else "")


def extract_bundle(path: Path, output: Path, manifest: dict,
                   events: list[dict], names: set[str]) -> None:
    if output.exists():
        raise FileExistsError(f"Output already exists: {output}")
    output.mkdir(parents=True)
    with zipfile.ZipFile(path) as archive:
        for name in sorted(names):
            destination = output / name
            with archive.open(name) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target, length=64 * 1024)
    (output / "trace.log").write_text(render_log(events), encoding="utf-8")
    markers = [
        {
            "elapsedNanos": event["elapsedNanos"],
            "relativeMs": round(
                (event["elapsedNanos"] - manifest["startedElapsedNanos"]) / 1_000_000.0, 3),
            "message": event["message"],
        }
        for event in events
        if event["tag"] == "CensorLab"
    ]
    (output / "sync-markers.json").write_text(
        json.dumps(markers, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--extract-dir", type=Path)
    args = parser.parse_args()
    manifest, events, names = inspect_bundle(args.bundle)
    if args.extract_dir:
        extract_bundle(args.bundle, args.extract_dir, manifest, events, names)
    summary = {
        "sessionId": manifest["sessionId"],
        "events": len(events),
        "droppedEvents": manifest.get("droppedEvents", 0),
        "videoAttached": "screen-recording.mp4" in names,
        "recording": manifest.get("recording", {}),
        "durationMs": round(
            (manifest["stoppedElapsedNanos"] - manifest["startedElapsedNanos"])
            / 1_000_000.0, 3),
        "syncMarkers": sum(event["tag"] == "CensorLab" for event in events),
    }
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
