#!/usr/bin/env python3
"""Resolve the public dev label without reusing Android's installation counter."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import re
import subprocess
from urllib.parse import urlencode

if __package__:
    from .release_version import CI_CODE_STRIDE, build_version, parse_version, read_version
else:
    from release_version import CI_CODE_STRIDE, build_version, parse_version, read_version


WORKFLOW = ".github/workflows/release.yml"
STABLE_TAG = re.compile(r"v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)")
SHA = re.compile(r"[0-9a-f]{40}")
# Public dev names through .259 used the global workflow counter. They remain immutable.
LEGACY_LAST_RUN = 259
PAGE_SIZE = 100


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, timeout=30).strip()


def github(endpoint: str, *options: str) -> bytes:
    try:
        return subprocess.check_output(["gh", "api", endpoint, *options], timeout=60,
                                       stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        # Artifact redirects and credentials should never appear in workflow diagnostics.
        raise ValueError(f"GitHub could not read {endpoint.split('?')[0]}") from error


def api(endpoint: str):
    return json.loads(github(endpoint))


def pages(endpoint: str, *, key: str | None = None, **query):
    for page in range(1, 101):
        payload = api(endpoint + "?" + urlencode(dict(query, per_page=PAGE_SIZE, page=page)))
        if key is not None and payload["total_count"] >= 1000:
            raise ValueError("Workflow history exceeds GitHub's bounded search window")
        rows = payload[key] if key else payload
        yield from rows
        if len(rows) < PAGE_SIZE:
            return
    raise ValueError("GitHub history exceeded its pagination bound")


def timestamp(value: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError("Expected a GitHub UTC timestamp")
    return datetime.fromisoformat(value[:-1] + "+00:00")


def stable_at(releases, created_at: str) -> dict:
    """Use the stable publication visible when this immutable workflow run was created."""
    current = timestamp(created_at)
    candidates = [
        release for release in releases
        if release.get("draft") is False and release.get("prerelease") is False
        and STABLE_TAG.fullmatch(release.get("tag_name", ""))
        and timestamp(release["published_at"]) <= current
    ]
    if not candidates:
        raise ValueError("No published stable release anchors this dev run")
    stable = max(candidates, key=lambda release: (timestamp(release["published_at"]), release["id"]))
    if stable.get("immutable") is not True:
        raise ValueError("The latest stable release must be immutable")
    return stable


def trusted_run(run: dict, workflow_id: int, repository_id: int) -> bool:
    return (run.get("workflow_id") == workflow_id and run.get("path") == WORKFLOW
            and (run.get("repository") or {}).get("id") == repository_id
            and (run.get("head_repository") or {}).get("id") == repository_id)


def cycle_sequence(current: dict, stable_run: dict, published_at: str, runs) -> int:
    """Rank dev pushes in this cycle. Retries and private builds do not create slots."""
    run_number = current["run_number"]
    anchor = max(LEGACY_LAST_RUN, stable_run["run_number"])
    if not 1 <= stable_run["run_number"] <= anchor < run_number < CI_CODE_STRIDE:
        raise ValueError("Dev run is outside the cycle's Android version bucket")
    after = {
        run["run_number"]: run for run in runs
        if anchor < run["run_number"] <= run_number
    }
    if set(after) != set(range(anchor + 1, run_number + 1)):
        raise ValueError("Incomplete Release workflow history for this dev cycle")
    if after[run_number]["id"] != current["id"]:
        raise ValueError("Current run does not match its Release workflow history")
    published = timestamp(published_at)
    sequence = sum(
        run["event"] == "push" and run["head_branch"] == "dev"
        and timestamp(run["created_at"]) >= published
        for run in after.values()
    )
    if timestamp(current["created_at"]) < published or sequence == 0:
        raise ValueError("Dev run does not follow its stable release")
    return sequence


def stable_manifest(prefix: str, stable: dict, commit: str) -> dict:
    tag = stable["tag_name"]
    name = f"SubHub-{tag[1:]}-update.json"
    assets = [asset for asset in stable["assets"] if asset["name"] == name]
    if len(assets) != 1:
        raise ValueError("Stable release has no unique updater manifest")
    asset = assets[0]
    digest = asset.get("digest", "")
    if (asset.get("state") != "uploaded" or type(asset.get("id")) is not int
            or type(asset.get("size")) is not int or not 0 < asset["size"] <= 1024 * 1024
            or not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)):
        raise ValueError("Stable updater manifest lacks immutable asset metadata")
    raw = github(f"{prefix}/releases/assets/{asset['id']}", "-H", "Accept: application/octet-stream")
    if len(raw) != asset["size"] or "sha256:" + hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("Stable updater manifest does not match its published digest")
    manifest = json.loads(raw)
    if (manifest.get("schema") != 1 or manifest.get("packageName") != "com.subhub.app"
            or manifest.get("tag") != tag or manifest.get("versionName") != tag[1:]
            or manifest.get("sourceCommit") != commit or manifest.get("prerelease") is not False
            or type(manifest.get("versionCode")) is not int):
        raise ValueError("Stable updater manifest does not identify the tagged source")
    return manifest


def verify_install_order(source_version: tuple[int, str], run_number: int, stable_code: int) -> None:
    dev_code, _ = build_version(*source_version, run_number)
    if dev_code <= stable_code:
        raise ValueError("Bump dev VERSION_CODE so the build can update the published stable APK")


def resolve(repository: str, run_id: int, run_number: int) -> int:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid source repository")
    prefix = "repos/" + repository
    repository_id = api(prefix)["id"]
    workflow = api(prefix + "/actions/workflows/release.yml")
    if workflow.get("path") != WORKFLOW:
        raise ValueError("Cannot identify the canonical Release workflow")
    workflow_id = workflow["id"]
    current = api(f"{prefix}/actions/runs/{run_id}")
    if (not trusted_run(current, workflow_id, repository_id)
            or current.get("id") != run_id or current.get("run_number") != run_number
            or current.get("event") != "push" or current.get("head_branch") != "dev"
            or current.get("head_sha") != git("rev-parse", "HEAD")):
        raise ValueError("Run does not identify the checked-out dev push")

    stable = stable_at(pages(prefix + "/releases"), current["created_at"])
    tag = stable["tag_name"]
    stable_commit = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    if not SHA.fullmatch(stable_commit):
        raise ValueError("Stable tag does not peel to a commit")
    git("merge-base", "--is-ancestor", stable_commit, "refs/remotes/origin/master")
    stable_code, stable_name = parse_version(git("show", f"{stable_commit}:version.properties"))
    if stable_name != tag[1:]:
        raise ValueError("Stable version.properties does not match its published tag")
    manifest = stable_manifest(prefix, stable, stable_commit)
    bucket, stable_sequence = divmod(manifest["versionCode"], CI_CODE_STRIDE)
    if bucket != stable_code or not 0 < stable_sequence < run_number:
        raise ValueError("Stable release is outside the shared Android version sequence")
    verify_install_order(read_version(), run_number, manifest["versionCode"])

    endpoint = prefix + "/actions/workflows/release.yml/runs"
    stable_runs = [
        run for run in pages(endpoint, key="workflow_runs", head_sha=stable_commit)
        if trusted_run(run, workflow_id, repository_id) and run["run_number"] == stable_sequence
        and run["head_sha"] == stable_commit
        and (run["event"] == "workflow_dispatch"
             or (run["event"] == "push" and run["head_branch"] == tag))
    ]
    if len(stable_runs) != 1:
        raise ValueError("Stable manifest does not match a unique Release workflow run")
    stable_run = stable_runs[0]
    if timestamp(stable_run["created_at"]) > timestamp(stable["published_at"]):
        raise ValueError("Stable release predates its Release workflow run")
    runs = (run for run in pages(endpoint, key="workflow_runs",
                                created=f"{stable_run['created_at']}..{current['created_at']}")
            if trusted_run(run, workflow_id, repository_id))
    return cycle_sequence(current, stable_run, stable["published_at"], runs)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--run-number", required=True, type=int)
    args = parser.parse_args()
    try:
        print(resolve(args.repository, args.run_id, args.run_number))
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Dev release cycle refused: {error}\n")


if __name__ == "__main__":
    main()
