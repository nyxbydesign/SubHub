"""Strict reader for the isolated spatial-refinement CPU probe, not pipeline latency."""
import re
import json
import sys
from pathlib import Path

RECORD = re.compile(r"SPATIAL_SOURCE_CPU samples=(\d+) accepted=(\d+) firstCpuUs=(\d+) "
                    r"medianCpuUs=(\d+) maxCpuUs=(\d+) medianWallUs=(\d+) maxWallUs=(\d+) "
                    r"movingSamples=(\d+) movingMedianCpuUs=(\d+) movingMaxCpuUs=(\d+)")


def parse(text):
    records, raw = [], 0
    for line in text.splitlines():
        if "SPATIAL_SOURCE_CPU " not in line:
            continue
        raw += 1
        match = RECORD.fullmatch(line[line.index("SPATIAL_SOURCE_CPU "):].strip())
        if not match:
            continue
        samples, accepted, first, median, maximum, wall, wall_max, moving, moving_median, moving_max = map(int, match.groups())
        if samples < 1 or accepted > samples or max(first, median) > maximum or wall > wall_max:
            continue
        if not 0 < moving <= samples or moving_median > moving_max or moving_max > maximum:
            continue
        records.append(dict(samples=samples, accepted=accepted, firstCpuUs=first, medianCpuUs=median,
                            maxCpuUs=maximum, medianWallUs=wall, maxWallUs=wall_max,
                            movingSamples=moving, movingMedianCpuUs=moving_median, movingMaxCpuUs=moving_max))
    return dict(rawRecords=raw, parsedRecords=len(records), complete=raw > 0 and raw == len(records),
                pipelineLatency=False, records=records)


if __name__ == "__main__":
    result = parse(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
