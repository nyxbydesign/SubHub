"""Strict parser for optional person-model and render-publication records; no pixels/text."""
import argparse
import json
import re
import statistics
from pathlib import Path

SCHEMA = {
    "MODEL": {"v", "run", "totalMs", "prepMs", "runtimeMs", "postMs", "cancelled", "success"},
    "PROVISIONAL": {"v", "source", "captureAgeMs"},
    "PUBLISH": {"v", "run", "source", "captureAgeMs", "applied", "submitted", "dropped", "preemptions", "denied"},
}
BOOLS = {"cancelled", "success", "applied"}


def parse(text):
    raw = 0
    records = []
    invalid = []
    for line_number, line in enumerate(text.splitlines(), 1):
        raw_line = line;
        if line.lstrip().startswith("{"):
            try:
                event = json.loads(line)
                line = event["message"]
                if not isinstance(line, str):
                    raise ValueError("invalid event message")
            except (ValueError, KeyError, TypeError):
                if "PERSON_" in raw_line:
                    raw += 1
                    invalid.append(line_number)
                continue
        match = re.search(r"\bPERSON_([A-Z_]+)\s+(.*)$", line)
        if not match:
            if "PERSON_" in line:
                raw += 1
                invalid.append(line_number)
            continue
        raw += 1
        kind, payload = match.groups()
        try:
            pairs = [token.split("=", 1) for token in payload.split()]
            fields = dict(pairs)
            if kind not in SCHEMA or set(fields) != SCHEMA[kind] or len(pairs) != len(fields):
                raise ValueError("unsupported fields")
            if fields["v"] != "1":
                raise ValueError("unsupported version")
            record = {"kind": kind}
            for key, value in fields.items():
                if key in BOOLS:
                    if value not in {"true", "false"}:
                        raise ValueError("invalid boolean")
                    record[key] = value == "true"
                else:
                    if not re.fullmatch(r"\d+", value):
                        raise ValueError("invalid nonnegative integer")
                    record[key] = int(value)
            records.append(record)
        except (ValueError, TypeError):
            invalid.append(line_number)
    models = [r for r in records if r["kind"] == "MODEL"]
    publications = [r for r in records if r["kind"] == "PUBLISH"]
    run_ids = [r["run"] for r in models]
    matched = all(r["run"] in run_ids for r in publications)
    complete = raw > 0 and raw == len(records) and len(set(run_ids)) == len(run_ids) and matched
    distributions = {}
    for kind, field in (("MODEL", "totalMs"), ("MODEL", "prepMs"), ("MODEL", "runtimeMs"),
                        ("MODEL", "postMs"), ("PROVISIONAL", "captureAgeMs"), ("PUBLISH", "captureAgeMs")):
        values = [r[field] for r in records if r["kind"] == kind]
        distributions[f"{kind}.{field}"] = None if not values else {
            "count": len(values), "median": statistics.median(values), "max": max(values)}
    return dict(rawRecords=raw, parsedRecords=len(records), complete=complete,
                invalidLines=invalid, modelRuns=len(models),
                appliedRefinements=sum(r["applied"] for r in publications),
                distributions=distributions, records=records)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    print(json.dumps(parse(args.path.read_text(encoding="utf-8-sig")), indent=2))
