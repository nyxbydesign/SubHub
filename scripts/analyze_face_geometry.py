"""Face-only source/tracker coordinate diagnostics; no absolute image or display-time truth."""
import json
import math
import re
import statistics
import sys
from pathlib import Path

RECORD = re.compile(r"FACE_GEOMETRY v=[12] id=(\d+) source=(\d+)x(\d+) viewport=(\d+)x(\d+) "
                    r"cameras=(-?\d+(?:,-?\d+){5}) obsTotal=(\d+) obsEncoded=(\d+) observations=([\d,|\-]+) "
                    r"tracksTotal=(\d+) tracksEncoded=(\d+) tracks=([\d,|\-]+)"
                    r"(?: spatial=(-|\d+,\d+,-?\d+,\d+,-?\d+(?:\.\d+)?))?")


def tuples(text, length):
    if text == "-":
        return []
    rows = [list(map(int, row.split(","))) for row in text.split("|")]
    if any(len(row) != length for row in rows):
        raise ValueError("Invalid tuple")
    return rows


def analyze(text):
    raw = parsed = truncated = 0
    differences, fresh_differences, rows = [], [], []
    for line in text.splitlines():
        if "FACE_GEOMETRY " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("FACE_GEOMETRY "):].strip())
        if not match:
            continue
        if ("v=2 " in match[0]) != (match[13] is not None):
            continue
        identity, width, height, viewport_width, viewport_height = map(int, match.groups()[:5])
        cameras = list(map(int, match[6].split(",")))
        total_obs, encoded_obs, total_tracks, encoded_tracks = map(int, (match[7], match[8], match[10], match[11]))
        try:
            obs, tracks = tuples(match[9], 6), tuples(match[12], 12)
            spatial = None
            if match[13] not in (None, "-"):
                values = match[13].split(",")
                spatial = list(map(int, values[:4])) + [float(values[4])]
                if not math.isfinite(spatial[4]) or spatial[3] < 1:
                    continue
        except ValueError:
            continue
        if min(width, height, viewport_width, viewport_height) <= 0:
            continue
        if len(obs) != encoded_obs or len(tracks) != encoded_tracks or max(encoded_obs, encoded_tracks) > 8:
            continue
        if total_obs < encoded_obs or total_tracks < encoded_tracks:
            continue
        if any(o[0] not in (1, 2) or not 0 <= o[1] <= 4 or min(o[4:]) < 0 for o in obs):
            continue
        if any(t[0] < 1 or t[1] not in (1, 2) or t[2] < 0 or not 0 <= t[3] <= 4
               or min(t[6:8] + t[10:12]) < 0 for t in tracks):
            continue
        parsed += 1
        if total_obs != encoded_obs or total_tracks != encoded_tracks:
            truncated += 1
        for track in tracks:
            difference = (track[9] + track[11] / 2) - (track[5] + track[7] / 2)
            differences.append(abs(difference))
            if track[2] == 0 and track[3] == 0:
                fresh_differences.append(abs(difference))
        rows.append(dict(id=identity, source=[width, height], viewport=[viewport_width, viewport_height],
                         cameras=cameras, observations=obs, tracks=tracks, spatial=spatial))
    return dict(rawRecords=raw, parsedRecords=parsed, truncatedRecords=truncated,
                complete=raw > 0 and raw == parsed and truncated == 0,
                freshTracks=len(fresh_differences),
                freshRawToFilteredYMedianPx=statistics.median(fresh_differences) if fresh_differences else None,
                freshRawToFilteredYMaxPx=max(fresh_differences) if fresh_differences else None,
                allRawToFilteredYMaxPx=max(differences) if differences else None,
                multipleFaceTrackFrames=sum(len(row["tracks"]) > 1 for row in rows),
                singleFaceMotionCandidates=motion_candidates(rows) if raw == parsed and truncated == 0 else [],
                pixelTimeKnown=False, absoluteAlignmentMeasured=False, records=rows)


def motion_candidates(rows):
    """Adjacent single-face comparisons, NOT a claim that both faces have the same identity.

    Keep unknown, nonmonotonic, horizontal and map/scope/geometry changes as hard boundaries.
    A face can move independently of the registered body crop; residuals expose that too.
    """
    result = []
    for previous, current in zip(rows, rows[1:]):
        p, c = previous["spatial"], current["spatial"]
        if (p is None or c is None or p[:4] != c[:4] or current["id"] <= previous["id"]
                or previous["source"] != current["source"] or previous["viewport"] != current["viewport"]
                or previous["cameras"][0] != current["cameras"][0]
                or len(previous["observations"]) != 1 or len(current["observations"]) != 1):
            continue
        a, b = previous["observations"][0], current["observations"][0]
        if a[:2] != b[:2] or a[1] != 0:
            continue
        face_dy = b[3] + b[5] / 2 - a[3] - a[5] / 2
        image_dy = p[4] - c[4]
        event_dy = ((previous["cameras"][1] - current["cameras"][1])
                    * current["source"][1] / current["viewport"][1])
        result.append(dict(previousId=previous["id"], id=current["id"], faceDy=face_dy,
                           imageDy=image_dy, eventDy=event_dy,
                           faceMinusImageDy=face_dy-image_dy,
                           faceMinusEventDy=face_dy-event_dy))
    return result


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
