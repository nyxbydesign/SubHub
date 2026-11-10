"""Require exact-source canonical CI before public release signing and publication."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

WORKFLOW = ".github/workflows/android-ci.yml"
ARTIFACT = "SubHub-canonical-ci"
PROFILE = {"immediateQualityExperiment": False, "replacementLabCandidate": False}


def require_run(runs, workflow, repository_id, sha):
    if workflow.get("path") != WORKFLOW or workflow.get("state") != "active":
        raise ValueError("Canonical CI workflow is unavailable")
    candidates = [run for run in runs if run.get("head_sha") == sha
                  and run.get("workflow_id") == workflow.get("id")]
    if not candidates:
        raise ValueError("No canonical CI run exists for the exact release commit")
    run = max(candidates, key=lambda item: (item.get("run_number", 0), item.get("run_attempt", 0)))
    trusted_event = (run.get("event") in ("pull_request", "workflow_dispatch")
                     or (run.get("event") == "push" and run.get("head_branch") == "master"))
    if (run.get("path") != WORKFLOW
            or (run.get("repository") or {}).get("id") != repository_id
            or (run.get("head_repository") or {}).get("id") != repository_id
            or not trusted_event
            or run.get("status") != "completed" or run.get("conclusion") != "success"):
        raise ValueError("The latest exact-source canonical CI run is not a trusted passing run")
    return run


def require_receipt(receipt, run, sha, tree):
    if not isinstance(receipt, dict):
        raise ValueError("Canonical CI receipt must be an object")
    expected = {"schema": 1, "workflowPath": WORKFLOW, "sourceCommit": sha,
                "sourceTree": tree, "profile": PROFILE,
                "runId": run["id"], "runAttempt": run["run_attempt"]}
    typed = (type(receipt.get("schema")) is int
             and type(receipt.get("runId")) is int and type(receipt.get("runAttempt")) is int
             and isinstance(receipt.get("profile"), dict)
             and all(type(value) is bool for value in receipt["profile"].values()))
    if not typed or receipt != expected:
        raise ValueError("Canonical CI receipt does not match the source, tree, run attempt and default profile")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def record(output):
    source = git("rev-parse", "HEAD")
    if source != os.environ["QUALIFIED_HEAD"] or git("status", "--porcelain"):
        raise ValueError("Canonical CI must test the clean immutable source head")
    config = Path("app/build/generated/source/buildConfig/debug/com/subhub/app/BuildConfig.java").read_text(encoding="utf-8")
    for field in ("IMMEDIATE_QUALITY_EXPERIMENT", "REPLACEMENT_LAB_CANDIDATE"):
        if len(re.findall(r"boolean\s+" + field + r"\s*=\s*false\s*;", config)) != 1:
            raise ValueError("Canonical CI must qualify the default experimental flags")
    receipt = {"schema": 1, "workflowPath": WORKFLOW, "sourceCommit": source,
               "sourceTree": git("rev-parse", "HEAD^{tree}"), "profile": PROFILE,
               "runId": int(os.environ["GITHUB_RUN_ID"]),
               "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"])}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


def api(endpoint):
    return json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))


def pages(endpoint, key):
    result = []
    for page in range(1, 101):
        data = api(endpoint + ("&" if "?" in endpoint else "?") + "per_page=100&page=" + str(page))
        rows = data[key]
        result.extend(rows)
        if len(rows) < 100:
            return result
    raise ValueError("Canonical CI lookup exceeded its pagination bound")


def verify(repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid source repository")
    sha, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
    prefix = "repos/" + repository
    repository_id = api(prefix)["id"]
    workflow = api(prefix + "/actions/workflows/android-ci.yml")
    runs = pages(prefix + "/actions/workflows/android-ci.yml/runs?head_sha=" + sha, "workflow_runs")
    run = require_run(runs, workflow, repository_id, sha)
    artifacts = pages(prefix + "/actions/runs/" + str(run["id"]) + "/artifacts?name=" + ARTIFACT, "artifacts")
    artifacts = [item for item in artifacts if item.get("name") == ARTIFACT and item.get("expired") is False]
    if len(artifacts) != 1:
        raise ValueError("The passing canonical CI run has no unique current qualification receipt")
    with tempfile.TemporaryDirectory(prefix="subhub-canonical-ci-") as directory:
        subprocess.run(["gh", "run", "download", str(run["id"]), "--repo", repository,
                        "--name", ARTIFACT, "--dir", directory], check=True)
        files = list(Path(directory).rglob("*"))
        if [path.relative_to(directory).as_posix() for path in files] != ["qualification.json"]:
            raise ValueError("Unexpected canonical CI receipt contents")
        receipt = json.loads((Path(directory) / "qualification.json").read_text(encoding="utf-8"))
    require_receipt(receipt, run, sha, tree)
    # Reconcile the same run after download, including a newly started rerun attempt.
    latest = api(prefix + "/actions/runs/" + str(run["id"]))
    if (latest.get("run_attempt") != run["run_attempt"]
            or latest.get("status") != "completed" or latest.get("conclusion") != "success"):
        raise ValueError("Canonical CI changed while its qualification was being verified")
    refreshed = pages(prefix + "/actions/workflows/android-ci.yml/runs?head_sha=" + sha, "workflow_runs")
    if require_run(refreshed, workflow, repository_id, sha)["id"] != run["id"]:
        raise ValueError("A newer canonical CI run superseded the qualification")
    print("Canonical CI verified for " + sha + " (run " + str(run["id"]) + ")")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    sub.add_parser("record").add_argument("--output", type=Path, required=True)
    sub.add_parser("verify").add_argument("--repository", required=True)
    args = parser.parse_args()
    try:
        record(args.output) if args.operation == "record" else verify(args.repository)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Canonical CI qualification refused: " + str(error) + "\n")


if __name__ == "__main__":
    main()
