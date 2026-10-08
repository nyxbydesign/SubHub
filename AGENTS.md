# SubHub agent workflow

These repository rules apply to every automated or human-assisted change.

## Branch and commit discipline

- `master` is the release branch. Keep it buildable; use a topic branch and pull request for work that is not ready to ship.
- Keep commits narrowly reviewable and start imperative subjects with one of: `[feat]`, `[fix]`, `[perf]`, `[refactor]`, `[docs]`, `[test]`, `[build]`, `[ci]`, `[chore]`, or `[revert]`.
- Commit messages must say what changed and why. Record user-visible behavior, migration or compatibility concerns, and the checks run in the commit body when the subject alone is not enough.
- Release changelogs are generated from commit history. Every release commit must use a supported `[feat]`, `[fix]`, `[perf]`, `[refactor]`, `[docs]`, `[test]`, `[build]`, `[ci]`, `[chore]`, `[revert]` subject and describe the user-visible result clearly. Put one concise release-worthy summary sentence in the first body paragraph; keep checks in a later `Checks:` paragraph and use real line breaks rather than literal `\n` text.
- Do not commit credentials, signing keys, local properties, purchased APKs, decompiled trees, captures, or generated build outputs.

## Versioning

`version.properties` is the only release-version source:

- `VERSION_NAME` uses semantic versioning.
- `VERSION_CODE` is a positive Android integer and must increase for every released APK.
- Public CI builds derive their Android code as `VERSION_CODE * 100000 + release workflow run_number`.
  Stable and development releases share that sequence; a later stable build can replace a dev build
  of the same source version. Local/private candidates retain the unexpanded source code. Keep the
  same `release.yml` workflow sequence; fail closed before run 100000 or Android code overflow.
- Any release-bound change must update both values in the same commit. The helper performs the safe increment:

  `python scripts/release_version.py --set-version 0.2.0`

- Run `python scripts/release_version.py` after editing the file.
- A release tag must be exactly `v<VERSION_NAME>` and point at the tested release commit. Never move or reuse a published tag.

## Required verification

Before merging or tagging a release, run:

`./gradlew testDebugUnitTest lintDebug assembleDebug`

The universal APK is `app/build/outputs/apk/debug/app-universal-debug.apk`. It includes every ABI supported by the project; per-ABI APKs are emitted alongside it for smaller direct installs.

For direct ADB instrumentation, first confirm the declared runner with
`adb shell pm list instrumentation`, then invoke
`com.subhub.app.test/com.subhub.app.SubHubTestRunner`. Do not assume the stock
`AndroidJUnitRunner`; the custom runner prepares the controller PIN without
overriding each test's Dom/Sub state.

## Release procedure

1. Complete the version bump and release notes in reviewable commits.
   When refreshing both release outputs locally, write the single-release fragment to a temporary
   `--changelog-output` path and use `--history-output CHANGELOG.md` for the cumulative changelog;
   `--changelog-output CHANGELOG.md` replaces the history with only the current release.
2. Verify the command above and review the staged diff for secrets or unrelated files.
3. Push the tested commit to `master`.
4. Create and push the exact version tag, for example `git tag -a v0.2.0 -m "SubHub 0.2.0"` followed by `git push origin v0.2.0`.
5. `.github/workflows/release.yml` validates tag/version parity, tests and lints the app, signs all APKs, and stages the GitHub release as a draft while uploading each universal/per-ABI artifact and checksum separately. It must verify the complete asset set before publishing because immutable releases reject later uploads and permanently consume a tag even if deleted.
6. `scripts/generate_release_notes.py` categorizes every commit since the previous tag, places those changes in both the GitHub release and updater manifest, and keeps APK-selection guidance in its own release section. The release fails instead of publishing an empty changelog when a commit lacks a supported type prefix.

Release signing is supplied only through the repository Actions secrets named in the workflow. Do not weaken signing or manufacture a different key for a later release; Android updates require the same key.

## Development releases

Every non-`master` branch push builds its exact pushed commit and publishes a signed GitHub
prerelease named `v<VERSION_NAME>-dev.<release workflow run_number>`. Do not cancel an older push's
build just because a new commit arrives. Each tag is unique and immutable; verify all APKs,
checksums and updater metadata in the draft before publishing. Dev builds must not become the
latest stable release or accidentally enable private experimental flags. Stable release tags
must identify a tested commit on `master`; branch pushes never create a normal release.
Dev release notes cover the pushed commit range. For a first or rewritten branch push, describe
the new snapshot's last commit instead of replaying unrelated legacy history. Stable changelogs
remain cumulative since the previous stable tag; automatic dev tags must not truncate them.
The normalized source history has a recorded equivalent for the historical `v0.6.3` release.
Use that source-history base only when its full Git tree equals the published tag's tree;
never move the tag, guess an equivalent, or disable strict commit-type validation.

Dev updates are opt-in and off by default. Selection uses the compatible manifest's Android
version code, not SemVer alone, and stable lookup must still work when dev builds fill the first
release-feed page. Channel changes invalidate cached candidates/ETags and pending downloads;
in-flight checks from the previous channel must not restore an old candidate. Do not downgrade
or uninstall when the user leaves the dev channel.
