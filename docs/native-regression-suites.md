# Fixed native regression suites

## General Android CI, with native tests off by default

`.github/workflows/android-ci.yml` always runs canonical unit tests, lint, the debug
build and its exact-source qualification receipt. Its manual `native_suite` choice
is `none` by default. Selecting a fixed native suite adds one dependent native job
on a fresh GitHub-hosted Linux x64 runner. PRs never opt into native execution via
labels. There are no feature-specific native workflow files.

The selectable suites are `extra-time` (21 methods), `subhub-pass` (52),
`reconnect` (6), `battery` (31), `device-pass` (57), and `dummy-preview` (226).
These are exact inventories, not wildcards or class-wide selections. This workflow
runs the complete selected suite; it does not accept a method-subset input.
The retained producer's standalone selector parsing remains available to existing
explicit callers, but does not create an automatic PR or dispatch route.

The native job depends on canonical success and builds the same immutable commit.
Its failures, including absent host prerequisites, fail the overall Android CI run.
The release gate requires the latest exact-source run to succeed and carry its own
canonical receipt. An older green run or a native-only receipt cannot replace it.
Native execution does not sign or publish a release. Native runs have distinct
concurrency groups so a later canonical run cannot cancel the owned guest's teardown.

## Preprovisioned host only

The producer never installs host or SDK packages, accepts SDK licenses, changes
KVM permissions, or falls back to unaccelerated emulation. The runner must already
have readable/writable `/dev/kvm`, the Android command-line tools, platform-tools,
emulator, platform 35, build-tools 35.0.0, and the Google APIs API 35 x86_64 system
image. Their installed metadata, executables, runtime libraries and acceleration
are checked before the paired build. Missing prerequisites stop the run. The native
build disables Gradle SDK auto-download; ordinary Gradle/dependency resolution is
not an offline-build guarantee.

`ubuntu-24.04` names the supported host family, not a promise that every required
emulator package or permission is provisioned. Check the current image and actual
job admission result. The producer does not repair incomplete hosts.

Read existing capabilities without executing any child command, starting ADB,
writing files, creating an AVD or connecting to the network:

```sh
python3 scripts/run_extra_time_native.py --check-host
```

This prints bounded host/installed-package metadata and returns nonzero when
requirements are missing. `runtimeVerified` remains false: it does not establish
that KVM operations, dynamic loading, guest boot or tests pass.

An explicitly selected native run retains the existing resource guards: at least
10.2 GiB free before first boot, at least 4.5 GiB available memory, a 2560 MiB/two-core
guest, bounded commands and a cleanup reserve. It refuses an already connected
Android device and creates an owned fresh-data guest. Guest fixture state changes,
exact restoration, source/APK identity, evidence bounds and owned shutdown remain
required. This is synthetic emulator qualification, not a personal phone test.

## Manual selection on a candidate branch

The existing Android CI workflow must be registered on the repository's default
branch. A candidate branch may contain the new inputs without changing `master`.
GitHub documents selecting a different workflow ref through CLI or REST. For an
operator already authorized to dispatch a specific candidate:

```sh
gh workflow run android-ci.yml --ref <candidate-branch> -f native_suite=<fixed-suite>
```

The REST equivalent addresses `android-ci.yml` with the candidate branch in `ref`
and the selected suite in `inputs.native_suite`. The CLI also reads the requested
ref's workflow definitions when prompting. Do not assume a browser form refreshed
its fields after changing branches; verify the selected ref and submitted inputs.
These are supported service interfaces, not a statement that a particular local
CLI or account is authenticated. Do not change default-branch workflows or acquire
credentials merely to expose the form.

References: [GitHub manual workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[dispatch API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event),
[GitHub CLI implementation](https://github.com/cli/cli/blob/trunk/pkg/cmd/workflow/run/run.go),
[Ubuntu 24.04 installed software](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md),
[Android SDK auto-download control](https://developer.android.com/studio/intro/update).

## Inspect inventories without native execution

```sh
python3 scripts/run_extra_time_native.py --suite dummy-preview --check-inventory
python3 scripts/run_extra_time_native.py --suite device-pass --check-inventory
python3 -m unittest scripts.test_extra_time_native_inventory scripts.test_native_preprovisioned scripts.test_native_ci_policy
```

The receipt records the exact suite, selected methods, source hashes, outcomes and
remaining/not-passed methods. An ignored test or assumption is not a pass. Evidence
keeps the original `build/reports/extra-time-native/` path and existing packaging and
limits. Native receipts retain `workflowPath: null` and identify the producer script;
the separate canonical receipt identifies Android CI. Passing results apply to their
recorded source and configuration, not automatically to later changes.

## Integration suite coverage and fixture requirements

| Class | Additional methods selected | Requirements and isolation |
| --- | ---: | --- |
| `DurationChoicesAndroidTest` | 2 | Main-thread native Views only. The label-layout test creates scoped 1.0/1.5/2.0 font contexts; it does not mutate global font settings. |
| `ServiceDurationRetentionAndroidTest` | 3 | Owned UUID SharedPreferences, deleted in `finally`; covers refresh and stale visibility/duration/bounds writes. |
| `PersonRefreshAndroidTest` | 3 | Owned UI integration, unpaired endpoint, drained Activity readers, restored presentation hooks/preferences, UiAutomation restoration. Tests deferred refresh/swipe, recreation/back/role loss, and retained local duration. |
| `RemoteAppIconPresentationAndroidTest` | 1 | `sharedNativeLimitsAndAppSelectorKeepRemoteIconScope` uses owned recorded pairing/cache data and bounded synthetic PNG delivery. API 29+ frame/pixel assertions are eligible on API 35. Both native pages are exercised; screenshots are retained on failure, not manufactured as success witnesses. |
| `RemoteConfigurationAndroidTest` | 5 | Two local stores in an isolated Context with migrated, prefixed preferences and private files. No remote device: coalescing/restart/receipt, rejection/discard, identical snapshot, conflict revision, stale workspace. |
| `RemoteDurationAndroidTest` | 1 | `staleInlineEditCannotQueueAfterDeviceCapabilityOrRelationshipRevocation` uses synthetic local pairing, role/preferences restoration and owned endpoint/store cleanup. |
| `RemoteEditorAndroidTest` | 5 | Synthetic local pairing and catalog, actual editor Activity/UiAutomator, integration and UiAutomation restoration, owned databases/assets cleanup. Covers native choice autosave, recreation without writes, coalescing/close, pending edits, focus/cursor. |
| `RemoteSessionControlsAndroidTest` | 3 | Synthetic endpoint plus controlled review gateway. Drains owned readers and endpoint workers before restoring the application endpoint and role/preferences. Covers single-action state, repeated pending review, actual duration autosave/pending receipt fence. |
| `LazySessionDurationAndroidTest` | 5 | UUID SQLite stores closed/deleted in `finally`; explicit timestamps/time zones. No clock changes, background service, real transport, or global background-sync preference changes. |
| `PageScrollViewAndroidTest` | 3 | Main-thread native View measurement/layout and synthetic MotionEvent, without Activity or global state changes. |

All 31 additional selectors fit this single-guest producer. The two-device `RemoteFlowAndroidTest` and
`RemoteActivityFlowAndroidTest`, all other source-only methods, and unbounded
whole-app suites remain out of scope. This inventory review establishes eligibility,
not runtime success: An opted-in native run must execute each selected method and its teardown.

The existing large-font ExtraTime UI method remains **last**, so no added test
inherits its global font change. The API, controller fixture, method timeout,
overall soft deadline/cleanup reserve, disk/RAM admission, strict APK/source/run
identity checks, required original UI witnesses, evidence export bounds, exact
external-state restoration, and failure-preserving shutdown are unchanged.
