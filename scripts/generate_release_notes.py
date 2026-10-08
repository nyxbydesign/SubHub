#!/usr/bin/env python3
"""Build deterministic, user-facing release notes from tagged Git history.

GitHub's generated notes are PR/label-oriented and collapse to only a compare link
when a release is assembled directly from commits on master. SubHub releases are
tag-driven, so this generator treats the repository's ``[type]`` commit subjects as
the stable changelog source and uses the first useful body sentence as optional
detail.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


SUBJECT = re.compile(
    r"^\[(feat|fix|perf|refactor|docs|test|build|ci|chore|revert)\]\s+(.+)$",
    re.IGNORECASE,
)
SEMVER_TAG = re.compile(r"^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")
# The imported source history normalized commit identities, but retained this release's
# exact tree. Bind the equivalent before using it; never move the published tag.
SOURCE_HISTORY_EQUIVALENTS = {
    "v0.6.3": "b04eb9233e530ad525d358df8438f55b086f7661",
}
RELEASE_HOUSEKEEPING = re.compile(
    r"^(?:bump|prepare|release)\b.*\b(?:version|subhub|v?\d+\.\d+\.\d+)",
    re.IGNORECASE,
)

CATEGORIES = (
    ("feat", "New"),
    ("fix", "Fixed"),
    ("perf", "Faster & smoother"),
    ("refactor", "Improved"),
    ("revert", "Restored"),
    ("docs", "Documentation"),
    ("build", "Build & release"),
    ("ci", "Build & release"),
    ("test", "Quality"),
    ("chore", "Maintenance"),
)


@dataclass(frozen=True)
class Commit:
    hash: str
    subject: str
    body: str


@dataclass(frozen=True)
class Entry:
    kind: str
    title: str
    detail: str


@dataclass(frozen=True)
class ReleaseRecord:
    version: str
    tag: str
    notes: str
    published_at: str


def run_git(arguments: Sequence[str]) -> str:
    completed = subprocess.run(
        ["git", *arguments], check=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, encoding="utf-8", errors="strict",
    )
    return completed.stdout


def previous_tag(tag: str) -> str | None:
    try:
        value = run_git(["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*",
                         "--exclude", "v*-dev.*", f"{tag}^"])
    except subprocess.CalledProcessError:
        return equivalent_source_base(tag)
    candidate = value.strip()
    return candidate if SEMVER_TAG.fullmatch(candidate) else None


def equivalent_source_base(tag: str) -> str | None:
    for released_tag, source_commit in SOURCE_HISTORY_EQUIVALENTS.items():
        try:
            run_git(["merge-base", "--is-ancestor", source_commit, f"{tag}^"])
            trees = run_git(["rev-parse", f"{released_tag}^{{tree}}",
                             f"{source_commit}^{{tree}}"]).splitlines()
        except subprocess.CalledProcessError:
            continue
        if len(trees) != 2 or trees[0] != trees[1]:
            raise ValueError("Release history equivalent does not match the published tree")
        return source_commit
    return None


def read_commits(tag: str, base_tag: str | None = None) -> list[Commit]:
    revision = f"{base_tag}..{tag}" if base_tag else tag
    raw = run_git(["log", "--no-merges", "--reverse", "--format=%H%x00%s%x00%b%x00", revision])
    raw = raw.rstrip("\r\n")
    if raw.endswith("\0"):
        raw = raw[:-1]
    fields = raw.split("\0")
    if len(fields) % 3:
        raise ValueError("Unexpected git log record shape")
    return [Commit(*fields[index:index + 3]) for index in range(0, len(fields), 3)]


def development_base(before: str) -> str:
    """Use the pushed range, not unrelated stable history or a rewritten old branch."""
    if re.fullmatch(r"[0-9a-f]{40}", before) and before != "0" * 40:
        try:
            run_git(["merge-base", "--is-ancestor", before, "HEAD"])
            return before
        except subprocess.CalledProcessError:
            pass
    # First push / force-push: describe the new snapshot's last commit, not all legacy history.
    return run_git(["rev-parse", "HEAD^"]).strip()


def _plain_body(body: str) -> str:
    # Repair historical PowerShell commits that accidentally stored literal \n text.
    body = body.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r", "")
    paragraphs = re.split(r"\n\s*\n", body)
    for paragraph in paragraphs:
        clean = " ".join(line.strip() for line in paragraph.splitlines() if line.strip())
        if not clean:
            continue
        if re.match(r"^(checks?|tests?|verification|signed-off-by|co-authored-by):", clean,
                    re.IGNORECASE):
            continue
        return clean
    return ""


def _first_sentence(text: str, limit: int = 240) -> str:
    if not text:
        return ""
    match = re.search(r"(?<=[.!?])\s", text)
    sentence = text[:match.start()] if match else text
    if len(sentence) <= limit:
        return sentence
    clipped = sentence[:limit - 1].rsplit(" ", 1)[0].rstrip(" ,;:-")
    return clipped + "…"


def entries(commits: Iterable[Commit], strict: bool = True) -> list[Entry]:
    result: list[Entry] = []
    invalid: list[str] = []
    for commit in commits:
        match = SUBJECT.fullmatch(commit.subject.strip())
        if not match:
            invalid.append(f"{commit.hash[:8]} {commit.subject}")
            if not strict:
                legacy = re.match(
                    r"^(feat|fix|perf|refactor|docs|test|build|ci|chore|revert):\s*(.+)$",
                    commit.subject.strip(), re.IGNORECASE,
                )
                kind = legacy.group(1).lower() if legacy else "refactor"
                title = (legacy.group(2) if legacy else commit.subject).strip().rstrip(".")
                result.append(Entry(kind, title, _first_sentence(_plain_body(commit.body))))
            continue
        kind = match.group(1).lower()
        title = match.group(2).strip().rstrip(".")
        if kind in {"build", "chore"} and RELEASE_HOUSEKEEPING.match(title):
            continue
        detail = _first_sentence(_plain_body(commit.body))
        normalized_title = re.sub(r"\W+", " ", title).strip().lower()
        normalized_detail = re.sub(r"\W+", " ", detail).strip().lower()
        if normalized_detail.startswith(normalized_title) or normalized_title in normalized_detail[:120]:
            detail = ""
        result.append(Entry(kind, title, detail))
    if strict and invalid:
        joined = "\n".join(f"  - {value}" for value in invalid)
        raise ValueError(
            "Release commits must start with a supported [type] prefix:\n" + joined
        )
    return result


def changelog_markdown(version: str, values: Iterable[Entry]) -> str:
    grouped: dict[str, list[Entry]] = {}
    heading_for = dict(CATEGORIES)
    for value in values:
        grouped.setdefault(heading_for[value.kind], []).append(value)
    lines = [f"## What’s new in SubHub {version}", ""]
    emitted: set[str] = set()
    for _, heading in CATEGORIES:
        if heading in emitted or heading not in grouped:
            continue
        emitted.add(heading)
        lines.extend((f"### {heading}", ""))
        for value in grouped[heading]:
            title = value.title[0].upper() + value.title[1:] if value.title else value.title
            bullet = f"- **{title}**"
            if value.detail:
                bullet += f" — {value.detail}"
            lines.append(bullet)
        lines.append("")
    if not emitted:
        lines.extend(("SubHub received a maintenance release with no user-facing changes.", ""))
    return "\n".join(lines).rstrip() + "\n"


def release_markdown(version: str, changelog: str, repository: str,
                     base_tag: str | None, tag: str) -> str:
    lines = [changelog.rstrip(), "", "## Choose your APK", "",
             "- **Universal** works on every Android CPU architecture supported by SubHub. "
             "**Choose Universal if you are unsure.**",
             "- **arm64-v8a** is the smaller download for most modern Android phones and tablets.",
             "- **armeabi-v7a** is for older 32-bit ARM devices.",
             "- **x86** and **x86_64** are primarily for compatible Intel devices and Android emulators.",
             "", "Per-ABI APKs are smaller, but only install on their matching architecture. "
             "Every APK in this release contains the same SubHub version and features."]
    if base_tag:
        lines.extend(("", f"**Full changelog:** https://github.com/{repository}/compare/{base_tag}...{tag}"))
    return "\n".join(lines).rstrip() + "\n"


def release_history(through_tag: str) -> list[ReleaseRecord]:
    tags = [value.strip() for value in run_git(
        ["tag", "--list", "v[0-9]*", "--sort=version:refname"]
    ).splitlines() if SEMVER_TAG.fullmatch(value.strip()) and "-dev." not in value]
    if through_tag not in tags:
        raise ValueError(f"History tag is not present: {through_tag}")
    records: list[ReleaseRecord] = []
    base: str | None = None
    for tag in tags:
        values = entries(read_commits(tag, base), strict=False)
        version = tag[1:]
        # Dereference annotated release tags so the app receives only the
        # release commit timestamp, not the tag header/message.
        published = run_git(["log", "-1", "--format=%cI", f"{tag}^{{}}"] ).strip()
        records.append(ReleaseRecord(
            version, tag, changelog_markdown(version, values), published,
        ))
        base = tag
        if tag == through_tag:
            break
    return list(reversed(records))


def history_markdown(records: Sequence[ReleaseRecord]) -> str:
    lines = ["# SubHub changelog", "",
             "User-facing changes from every tagged SubHub release.", ""]
    for index, record in enumerate(records):
        opened = " open" if index == 0 else ""
        lines.extend((f"<details{opened}>",
                      f"<summary><strong>SubHub {record.version}</strong></summary>", "",
                      record.notes.rstrip(), "", "</details>", ""))
    return "\n".join(lines).rstrip() + "\n"


def history_json(records: Sequence[ReleaseRecord], repository: str) -> str:
    payload = [{
        "versionName": record.version,
        "tag": record.tag,
        "notes": record.notes,
        "htmlUrl": f"https://github.com/{repository}/releases/tag/{record.tag}",
        "publishedAt": record.published_at,
        "prerelease": "-" in record.version,
    } for record in records]
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--display-tag", help="Published identity for a HEAD development build")
    parser.add_argument("--development-before", help="Previous push SHA for development changelog scope")
    parser.add_argument("--repository")
    parser.add_argument("--changelog-output", type=Path)
    parser.add_argument("--release-output", type=Path)
    parser.add_argument("--history-output", type=Path)
    parser.add_argument("--history-json-output", type=Path)
    parser.add_argument("--base-tag")
    parser.add_argument("--allow-untyped", action="store_true")
    parser.add_argument("--check-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.development_before is not None:
        if args.tag != "HEAD" or not args.display_tag or "-dev." not in args.display_tag:
            raise SystemExit("Development push ranges require a HEAD development display tag")
        base = development_base(args.development_before)
    else:
        base = args.base_tag if args.base_tag is not None else previous_tag(args.tag)
    values = entries(read_commits(args.tag, base), strict=not args.allow_untyped)
    if args.check_only:
        print(f"Validated {len(values)} release-note commit(s) since {base or 'repository start'}")
        return 0
    display_tag = args.display_tag or args.tag
    if not SEMVER_TAG.fullmatch(display_tag):
        raise SystemExit(f"Invalid release tag: {display_tag}")
    if args.display_tag and (args.tag != "HEAD" or "-dev." not in display_tag):
        raise SystemExit("Display tags are restricted to HEAD development releases")
    if not args.repository or args.changelog_output is None or args.release_output is None:
        raise SystemExit("Release output requires --repository, --changelog-output, and --release-output")
    version = display_tag[1:]
    changelog = changelog_markdown(version, values)
    release = release_markdown(version, changelog, args.repository, base, display_tag)
    args.changelog_output.parent.mkdir(parents=True, exist_ok=True)
    args.changelog_output.write_text(changelog, encoding="utf-8")
    args.release_output.parent.mkdir(parents=True, exist_ok=True)
    args.release_output.write_text(release, encoding="utf-8")
    if args.history_output is not None or args.history_json_output is not None:
        history = release_history(args.tag)
        if args.history_output is not None:
            args.history_output.parent.mkdir(parents=True, exist_ok=True)
            args.history_output.write_text(history_markdown(history), encoding="utf-8")
        if args.history_json_output is not None:
            args.history_json_output.parent.mkdir(parents=True, exist_ok=True)
            args.history_json_output.write_text(
                history_json(history, args.repository), encoding="utf-8",
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
