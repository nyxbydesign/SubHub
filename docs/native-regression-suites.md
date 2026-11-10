# Fixed native regression suites

The `Extra-time native regression` workflow has **one** job, one paired APK build,
and one fresh, owned API 35 x86_64 emulator. Its default `extra-time` suite remains
21 exact ExtraTime/Tribute methods. Opting into `subhub-pass` runs those same 21
methods plus 31 reviewed integration methods, **52 unique methods total**. The
`remoteRequestIconArrivesWithoutPollingAndRevocationClearsIt` method is already one
of the default 21 and is not duplicated in the integration suite.
The focused `reconnect` suite selects six separate scheduling and durable-queue
contracts without rerunning either older suite.
The focused `battery` suite selects all 24 scheduler contracts plus the real Iroh
background responder and independent pairing-deadline regression, 26 methods total.

## Select the suite without duplicate jobs

- For a PR, add `native-subhub-pass` **before** pushing its combined candidate head.
  The existing PR opened/synchronize/reopened events use that label. Adding a label
  alone does not trigger a run. It takes precedence over the older
  `native-extra-time-ui` and `native-extra-time-tribute-large` subset labels.
- For an explicit later rerun, choose `subhub-pass` in `workflow_dispatch`'s `suite`
  input. An empty `selectors` input selects all 52. Otherwise supply only the exact
  `Class#method` selectors from that suite, separated by commas or newlines.
  Duplicates, class-wide selectors, wildcards, unknown methods, and the older
  source-only methods are rejected. Do not dispatch while the same candidate's
  automatic native job is already in flight.
- A manual dispatch does not depend on PR labels. The default remains `extra-time`.
  No native suite qualifies a release in place of canonical exact-source Android CI.
- For stable-connection retry changes, add `native-reconnect` before the candidate
  push, or select `reconnect` for a later explicit dispatch. This label takes
  precedence over the integration and legacy subset labels in the same single job.
  Remove it when a later candidate should use a different suite. Its six methods
  cover coalesced retry/backoff reset, background opt-out/runtime retirement,
  persisted SENT-command ordering and duplicate receipts, rejection, conflict,
  and fresh consent checks. They use isolated stores and synthetic transport;
  they do not claim a physical network disconnect/reconnect test.

- For background-sync battery changes, add `native-battery` before the candidate
  push, or select `battery` for an explicit later dispatch. This label takes
  precedence over `native-reconnect`, integration and legacy subset labels.
  It covers quiet cadence, offline backoff, dirty-data delivery, foreground and
  pairing poll ownership, opt-out, responder retention, real delayed authenticated
  inbound exchanges, and deadline production while other queues are blocked.
  Synthetic scheduling checks are not physical-device battery measurements.

Inspect the exact inventory locally without a build or emulator:

```sh
python3 scripts/run_extra_time_native.py --check-inventory
python3 scripts/run_extra_time_native.py --suite subhub-pass --check-inventory
python3 scripts/run_extra_time_native.py --suite reconnect --check-inventory
python3 scripts/run_extra_time_native.py --suite battery --check-inventory
python3 -m unittest scripts.test_extra_time_native_inventory
```

The receipt records the suite, its complete selector set, the actual requested
subset, remaining/not-passed methods, and the class source hashes and annotations.
Every class retains an exact source method inventory, including explicitly listed
older methods that are **not** admitted as selectors. An unreviewed source change
fails before building/launching the guest. Each method still needs one exact
instrumentation start and pass; ignored tests and assumptions are not passes.

## Select a bounded PR recovery subset

For one automatic PR job that runs only reviewed unfinished methods, add the
`native-subset` label and exactly one block like this to the PR body **before**
pushing the combined candidate head:

```text
<!-- native-selectors:start -->
[
  "com.subhub.app.remote.ExtraTimeRequestAndroidTest#retainedCapabilityCannotBypassConsentWorkspaceOrRevocation"
]
<!-- native-selectors:end -->
```

Keep `native-subhub-pass` as well when selecting methods from the 52-method suite.
Use `native-reconnect` instead for a subset of the six reconnect contracts, or
`native-battery` for a subset of the 26 battery contracts.
The producer reads the PR event JSON directly from `GITHUB_EVENT_PATH`; body text
is never interpolated into a shell command or workflow expression. With
`native-subset`, the block overrides the legacy subset labels. Without that exact
label, the block is ignored and existing selection behavior is unchanged. Manual
`workflow_dispatch` continues to use only its own suite/selectors inputs.

The block must contain a nonempty JSON array of unique, exact `Class#method`
strings from the chosen suite. No code fences belong **inside** the marker pair.
Missing, reordered, malformed, duplicate or additional marker comments, empty
arrays, class-wide selectors, wildcards, non-string values, unknown methods, and
oversized input fail before producer setup/build. Markers are case-sensitive.
Execution follows the fixed suite order, retaining the large-font case last.

For recovery, inspect the preserved receipt first: `notPassed` includes the
failed method plus unstarted methods; `remaining` alone omits the attempted
failure. Review that set against the fix and the preserved passing cases. Publish
the exact JSON list and label before the next head push. Label/body edits alone do
not start a run because the workflow retains its existing PR event types. Wait
for any existing candidate run to finish; do not also dispatch or rerun a job.

The new receipt records `selectionSource=pull_request_body`, the complete suite
inventory, actual `selected` subset, `notSelected`, method outcomes and retained
source/APK identities. A passing subset does **not** claim that all suite methods
ran on that head. Inspect its exact-head receipt, required UI witnesses,
restoration and shutdown. Prior receipts remain evidence for their original
heads; reconcile relevant production/test/helper/build changes separately before
reusing any passing result. Canonical exact-source Android CI is unaffected.

Remove `native-subset` when a later PR event should run the normal suite again.
Leaving the label and block in place keeps selecting that subset on future heads.
Neither a label nor native subset evidence permits a merge or release.

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
not runtime success: CI must execute each selected method and its teardown.

The existing large-font ExtraTime UI method remains **last**, so no added test
inherits its global font change. The API, controller fixture, method timeout,
overall soft deadline/cleanup reserve, disk/RAM admission, strict APK/source/run
identity checks, required original UI witnesses, evidence export bounds, exact
external-state restoration, and failure-preserving shutdown are unchanged.
