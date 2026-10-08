"""Summarize opt-in Java stack samples. Sampling is diagnostic, not exact wall time."""
import collections
import json
import re
import sys
from pathlib import Path

SAMPLE = re.compile(r"MAIN_SAMPLE uptimeMs=(\d+) sampleMs=(\d+) state=([A-Z_]+) stack=(.*)$")
FRAME = re.compile(r"[\w.$]+#[\w$<>-]+:-?\d+")


def analyze(text):
    raw = parsed = malformed = 0
    top = collections.Counter()
    app = collections.Counter()
    costs = []
    for line in text.splitlines():
        if "MAIN_SAMPLE " not in line:
            continue
        raw += 1
        match = SAMPLE.search(line)
        if not match:
            malformed += 1
            continue
        frames = match[4].split(";")
        if not frames or not all(FRAME.fullmatch(frame) for frame in frames):
            malformed += 1
            continue
        parsed += 1
        costs.append(int(match[2]))
        top[frames[0]] += 1
        app[next((frame for frame in frames if frame.startswith("com.subhub.")), "no-app-frame")] += 1
    return {"raw": raw, "parsed": parsed, "malformed": malformed,
            "completeParsing": raw == parsed and raw > 0,
            "sampleCostMaxMs": max(costs, default=None),
            "topFrames": dict(top.most_common()), "firstAppFrames": dict(app.most_common())}


if __name__ == "__main__":
    result = analyze(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["completeParsing"] else 1)
