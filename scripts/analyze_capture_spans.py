"""Numeric-only capture critical-path report; not an end-to-end acceptance gate."""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

EVENT = re.compile(r"\b(CAPTURE_SPAN|CAPTURE_PREPARE)\s+(.*)")
FIELD = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)")
ORDER = ("accepted", "dispatch", "callback-success", "prepare-start", "prepare-end",
         "scene-begun", "callback-exit")
PUBLICATION_ORDER = ("fast-ready", "geometry-ready", "publication-post", "publication-main",
                     "publication-tick")
PAIRS = {
    "preflightMs": ("accepted", "dispatch"),
    "dispatchToCallbackMs": ("dispatch", "callback-success"),
    "callbackToPrepareMs": ("callback-success", "prepare-start"),
    "prepareMs": ("prepare-start", "prepare-end"),
    "prepareToSceneMs": ("prepare-end", "scene-begun"),
    "callbackWorkMs": ("callback-success", "callback-exit"),
    "fastToGeometryMs": ("fast-ready", "geometry-ready"),
    "publicationQueueMs": ("publication-post", "publication-main"),
    "publicationMainToTickMs": ("publication-main", "publication-tick"),
}


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    def percentile(fraction):
        p = (len(values) - 1) * fraction
        i = int(p)
        return round(values[i] + (values[min(i + 1, len(values) - 1)] - values[i]) * (p - i), 3)
    return {"count": len(values), "p50": percentile(.5), "p95": percentile(.95),
            "max": max(values)}


def analyze(lines, start=None, end=None):
    if bool(start) != bool(end) or (start and start == end):
        raise ValueError("Supply distinct start and end markers together.")
    if start:
        starts = [i for i, line in enumerate(lines) if start in line]
        ends = [i for i, line in enumerate(lines) if end in line]
        if len(starts) != 1 or len(ends) != 1 or starts[0] >= ends[0]:
            raise ValueError("Markers must appear exactly once and in order.")
        lines = lines[starts[0] + 1:ends[0]]
    requests = {}
    malformed = 0
    for line in lines:
        match = EVENT.search(line)
        if not match:
            continue
        request = None
        try:
            pairs = FIELD.findall(match[2])
            fields = dict(pairs)
            if len(fields) != len(pairs):
                raise ValueError("Duplicate fields")
            request_id = int(fields["id"])
            if request_id < 0:
                raise ValueError("Negative ID")
            request = requests.setdefault(request_id, {"stages": {}, "bad": False})
            if match[1] == "CAPTURE_SPAN":
                stage = fields["stage"]
                now, age = int(fields["uptimeMs"]), int(fields["requestAgeMs"])
                if (stage not in ORDER + PUBLICATION_ORDER and not re.fullmatch(r"callback-failure-\d+", stage)
                        or now < request_id or age != now - request_id
                        or stage in request["stages"]):
                    raise ValueError("Invalid stage")
                request["stages"][stage] = now
            else:
                scale, readback = int(fields["scaleUs"]), int(fields["readbackUs"])
                if scale < 0 or readback < 0 or "prepare" in request:
                    raise ValueError("Invalid preparation")
                if fields["hardwareReadback"] not in ("true", "false"):
                    raise ValueError("Invalid readback flag")
                request["prepare"] = (scale / 1000, readback / 1000)
        except (KeyError, ValueError):
            malformed += 1
            if request is not None:
                request["bad"] = True

    counts = Counter()
    samples = {key: [] for key in (*PAIRS, "scaleApiMs", "explicitReadbackMs", "failureAgeMs")}
    for request_id, request in requests.items():
        stages = request["stages"]
        ordered = [stages[stage] for stage in ORDER if stage in stages]
        publication = [stages[stage] for stage in PUBLICATION_ORDER if stage in stages]
        failures = [stage for stage in stages if stage.startswith("callback-failure-")]
        if (request["bad"] or ordered != sorted(ordered) or publication != sorted(publication)
                or len(failures) > 1
                or failures and "callback-success" in stages):
            counts["invalidRequests"] += 1
            continue
        if "accepted" not in stages:
            counts["partialRequests"] += 1
            continue
        if failures:
            if ("dispatch" not in stages or stages[failures[0]] < stages["dispatch"]
                    or any(stage in stages for stage in ORDER[2:] + PUBLICATION_ORDER)):
                counts["invalidRequests"] += 1
                continue
            counts["failedRequests"] += 1
            samples["failureAgeMs"].append(stages[failures[0]] - request_id)
        elif not all(stage in stages for stage in ("dispatch", "callback-success", "callback-exit")):
            counts["partialRequests"] += 1
            continue
        else:
            counts["completedCallbacks"] += 1
            if "scene-begun" in stages:
                counts["sceneBegun"] += 1
            else:
                counts["noSceneCallbacks"] += 1
        for key, (a, b) in PAIRS.items():
            if a in stages and b in stages:
                samples[key].append(stages[b] - stages[a])
        if "prepare" in request:
            if "prepare-start" not in stages or "prepare-end" not in stages:
                counts["unpairedPreparation"] += 1
            else:
                samples["scaleApiMs"].append(request["prepare"][0])
                samples["explicitReadbackMs"].append(request["prepare"][1])
    return {"schema": 1, "requests": len(requests), "malformedRecords": malformed,
            "counts": dict(counts), "timings": {k: distribution(v) for k, v in samples.items()},
            "acceptanceEligible": False,
            "limitations": ["Dispatch-to-callback includes platform AND callback scheduling delay.",
                            "Scale API may include hidden transfers; not isolated GPU timing.",
                            "Scene creation is not overlay presentation or alignment validation.",
                            "Missing/partial records are excluded and counted, never treated as zero."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--start")
    parser.add_argument("--end")
    args = parser.parse_args()
    try:
        result = analyze(args.trace.read_text(encoding="utf-8-sig").splitlines(), args.start, args.end)
    except (ValueError, OSError) as error:
        parser.exit(2, f"Capture report failed: {type(error).__name__}\n")
    print(json.dumps(result, indent=2))
