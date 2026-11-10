"""Independent phase-correlation comparison on clean image pairs, not a display timing gate."""
import argparse
import json
import statistics
from pathlib import Path
import cv2
import numpy as np


def phase_displacement(first, second):
    if first.shape != second.shape or first.ndim != 2:
        raise ValueError("Mismatched source geometry")
    if float(np.std(first)) < 1 or float(np.std(second)) < 1:
        return dict(supported=False, dx=0, dy=0, response=0)
    height, width = first.shape
    window = cv2.createHanningWindow((width, height), cv2.CV_32F)
    (dx, dy), response = cv2.phaseCorrelate(first.astype(np.float32), second.astype(np.float32), window)
    finite = np.isfinite([dx, dy, response]).all()
    return dict(supported=bool(finite and response >= .3 and abs(dx) < 1),
                dx=float(dx), dy=float(dy), response=float(response))


def compare(replay, source_root):
    if replay["schema"] != 1 or replay["rawRows"] != replay["parsedRows"]:
        raise ValueError("Incomplete source replay")
    records = []
    differences = []
    for row in replay["records"]:
        first = cv2.imread(str(source_root / f"frame-{row['previousMs']}.png"), cv2.IMREAD_GRAYSCALE)
        second = cv2.imread(str(source_root / f"frame-{row['currentMs']}.png"), cv2.IMREAD_GRAYSCALE)
        if first is None or second is None:
            raise ValueError("Missing source image")
        top, bottom = first.shape[0] // 5, first.shape[0] * 19 // 20
        estimate = phase_displacement(first[top:bottom], second[top:bottom])
        record = dict(row, phase=estimate)
        if row["accepted"] and estimate["supported"]:
            difference = row["refinedSourceDy"] - estimate["dy"] * row["sourceScale"]
            record["differenceSourcePx"] = difference
            differences.append(abs(difference))
        records.append(record)
    return dict(records=records, comparedPairs=len(differences),
                medianAbsDifferenceSourcePx=statistics.median(differences) if differences else None,
                maxAbsDifferenceSourcePx=max(differences) if differences else None,
                groundTruth=False, renderingAuthority=False)


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("replay", type=Path)
    cli.add_argument("source_root", type=Path)
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    result = compare(json.loads(args.replay.read_text(encoding="utf-8-sig")), args.source_root)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "records"}, indent=2))
