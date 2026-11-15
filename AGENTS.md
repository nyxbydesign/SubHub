# SubHub agent workflow

These repository rules apply to every automated or human-assisted change.

## Branch and commit discipline

- `master` is the stable release branch; `dev` is the development integration branch and the only branch that publishes prereleases.
- Start each feature on its own `codex/<feature>` branch from current `dev`, and open its pull request against `dev`. Feature branches run verification without publishing prereleases.
- Once the feature is complete and its required checks and review pass, merge it into `dev`. Verify that the merged result contains the tested work, then delete its remote and local feature branch. Preserve unmerged changes, active worktrees, and unrelated branches.
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
  of the same source version. Manual private replacement-engine candidates use that same sequence
  for in-place signed updates, without publishing a release or adding a dev suffix. Local builds
  and other private candidates retain the unexpanded source code. Keep the same `release.yml`
  workflow sequence; fail closed before run 100000 or Android code overflow.
- Any stable release-bound change must update both source values in the same commit. Automatic dev
  builds keep the source version and shared CI install-code sequence.
  The helper performs the safe increment:

  `python scripts/release_version.py --set-version 0.2.0`

- Run `python scripts/release_version.py` after editing the file.
- A release tag must be exactly `v<VERSION_NAME>` and point at the tested release commit. Never move or reuse a published tag.

## Required verification

AppCompat methods can be public Java APIs yet restricted to their library by `@RestrictTo`.
Use supported framework or AndroidX compatibility tint APIs from application code; keep
`RestrictedApi` lint enabled rather than suppressing it. Global Settings is
`GlobalSettingsActivity`; `SettingsActivity` is the guarded Censor editor. Resolve the intended
screen before launching a native Settings observer, and preserve its role authorization.

Check expandable TextView overflow after its text layout is ready, such as at pre-draw;
a bounds-change callback alone can miss ellipsis and hide the expansion control. Verify
the control with a native tap and after recreation. Track each native Journal fixture's
owning role and retain a scoped cleanup ledger so a failed multi-role test can remove
its own entries without attempting cross-role deletion or wiping unrelated guest data.

Build native profile-avatar fixtures with `ProfileImages.encode`; persisted thumbnails
use bounded 192-pixel JPEG data even when the imported source is PNG. Keep image validation
strict. Text-fit checks may permit ellipsis only on explicitly tagged, bounded roster
name/bio previews; continue checking their line count and drawn width, all controls in
full, and native tap-through to the complete profile.

After a monitored child Activity finishes, await the parent in `RESUMED` before
recreating it or issuing further foreground input. Keep the existing authority
assertions; a missing resumed host is an observer-lifecycle failure to reconcile,
not a reason to weaken the application's role guard.

Device-capability transitions through `DeviceRolePreferences.set` require controller
authentication on established devices. Native fixtures must verify their existing PIN
before a transition, and restore the required capability mode before deleting their
role-owned Journal entries. Keep rejected Sub changes as guard evidence rather than
weakening the setter to satisfy fixture setup.

Restore native SharedPreferences snapshots with `SharedPreferenceTestRestore` so
profile ages, consent booleans, task-resume timestamps and string sets retain their
actual types. Do not cast a whole preference store to strings or booleans during
teardown. Keep a failed restore as evidence and reconcile any committed partial
changes before resuming the unfinished checks.

Use the measured unobscured ScrollView viewport to reveal a compound identity block.
A scroll offset chosen for a small button can leave a taller name/details group behind
floating navigation. Retain its bounds and rendered frame, then verify full clearance
before sampling avatar pixels or issuing input.

Native text-fit checks must also cover text drawn directly on Canvas inside artwork. TextView bounds checks cannot detect that overflow; measure each drawn string against its allotted region and inspect a native artwork render after copy changes.

TextView `Layout.getLineWidth` includes trailing whitespace. Use
[`Layout.getLineMax`](https://developer.android.com/reference/android/text/Layout#getLineMax(int))
for the visible horizontal extent, and retain full/visible line widths, allocated bounds and
failed native pixels before asserting. After a scroll request, verify the actual scroll endpoint
and then a new hardware-frame commit before capturing the final instructions; smooth scrolling
can still be moving when the main queue becomes idle. A frame callback alone does not establish
screenshot freshness: qualify the composed scrolled layer with an opaque test witness and retain
its draw identity, expected pixel and observed pixel before asserting.

After dismissing a native authentication dialog, await the underlying Activity's window focus
before interacting with it again. Main-thread idleness does not establish dialog-window removal.

Before merging, canonical Android CI must pass for the exact source commit. It runs
`./gradlew testDebugUnitTest lintDebug assembleDebug` once on the pull-request head;
a manual workflow run can qualify that same source when necessary. A push to `master`
qualifies its actual merge commit before a later stable tag; other branch pushes do not
repeat canonical checks. Use focused local
checks for changed contracts and reuse unchanged passing evidence instead of repeating
the full suite locally or on an integration-branch push.

Public releases require that latest exact-source canonical run and its matching source-tree,
run-attempt and default-profile receipt. The release job then runs release-variant lint and
builds/signs the APKs without repeating debug/unit/tooling verification. Keep the tested
commit intact when promoting it; a different merge commit needs its own qualification.
Missing, expired, pending or failed qualification blocks publication. Private candidate
flags change the build configuration, so those builds retain their own full checks.

The universal APK is `app/build/outputs/apk/debug/app-universal-debug.apk`. It includes every ABI supported by the project; per-ABI APKs are emitted alongside it for smaller direct installs.

For direct ADB instrumentation, first confirm the declared runner with
`adb shell pm list instrumentation`, then invoke
`com.subhub.app.test/com.subhub.app.SubHubTestRunner`. Do not assume the stock
`AndroidJUnitRunner`; the custom runner prepares the controller PIN without
overriding each test's Dom/Sub state.
For first-launch or unkeyed onboarding tests, add `-e controller_fixture false`.
The runner otherwise prepares a PIN before activity creation, which changes the
fresh-setup behavior being tested. Keep the fixture enabled for ordinary screen tests.

UI fixtures that replace `People` presentation hooks must isolate any queried `RemoteFeature`
endpoint as well. Use an owned unpaired store for no-Dom assertions; the application's endpoint
can have independent pending work. Capture the live original before constructing its replacement,
publish and restore under the singleton's class monitor, and close only the owned endpoint/database.
Verify that the restored original endpoint remains live.
Install synthetic session gateways through `People.connectionUi()` before opening profile fixtures.
Saving a configuration advances the session panel's revision signature and rebuilds its controls;
one-shot View replacement loses the fixture gateway. Assert its identity after refresh and recreation.
Consent UI fixtures must also swap and restore the registered Hardcore authority under the policy
lock and verify that the original store's consent receipts are unchanged. Replacing the endpoint
singleton alone does not redirect consent receipt writes.
Complete mandatory restoration and owned shutdown even when a receipt assertion fails; exercise
that failure path rather than relying only on normal teardown.
When a fixture injects a synthetic transport endpoint, retain an owned endpoint lease across its
queries and release it before shutdown. Each submitted query can otherwise suspend an idle endpoint
and let a later query open the normal transport; assert the intended identity throughout the fixture.

For native click fixtures, initialize `UiAutomation` before creating the target Views,
then drain the target main queue before the first click so AccessibilityManager receives
the connection state. For pixel assertions, exclude fixture window transitions and await
a new hardware-frame commit where supported; UI idle and a fixed sleep alone do not
prove composition. Keep independent screenshot and hit-map assertions, and retain failed
pixels before assertions discard their evidence. A frame commit is not a physical pixel clock.
For empty-draw tests, keep the overlay visible with retained offscreen geometry. Removing its last
track can intentionally hide the View and prevent its marker or draw callback from appearing;
exercise hidden and detached lifecycle behavior separately.

Instrumentation providers run under the test APK's UID. Create and clean their synthetic
documents through that provider; target-process code cannot enumerate the test APK's cache.
For scrollable forms, reveal and assert the complete control rectangle inside system insets
before a native tap or capture; a partially visible button is not a full target witness.

After returning from Android Settings, await the host window's focus and a fresh hardware
frame before the next native tap; a package match can include a transitioning window.
After cancelling a credential prompt, verify its input is gone before tapping underlying controls.
Seed fixed-count fixtures with explicit entries; iterating a production enum can silently
change the asserted scenario as new features arrive.
For scroll captures, compare the target's full screen bounds with the unobscured viewport.
A clipped `getGlobalVisibleRect` result cannot establish that the complete target is visible.
For dialogs and other floating windows, `getGlobalVisibleRect` uses root-view coordinates.
Translate that clip through the root's `getLocationOnScreen` origin before screenshot samples or
native screen-coordinate swipes. Retain local/root clips, screen origin and expected/observed pixel before
asserting; see the [View coordinate contract](https://developer.android.com/reference/android/view/View#getGlobalVisibleRect(android.graphics.Rect)).
Give native evidence writers candidate-specific log paths and refuse to overwrite an existing log.
When reusing a helper, qualify its output paths before starting the workload.

For native taps on scrolling pages with floating navigation, use
`NativeUiActions.revealAboveNavigation()` before clicking. Espresso `scrollTo()` alone can
leave the target beneath the navigation overlay, so the tap reaches a different control.

For ImageReader colour-metadata fixtures, supply metadata through the actual producer and assert
the acquired image's format and dataspace before invoking the code under test. Do not assume
`setDataSpace()` on an acquired image changes its overridden getter; use a real ImageWriter path
for explicit source dataspaces. Keep requested reader defaults distinct from received frame metadata.

Resource preparation is effect-specific: solid boxes draw inline and do not create prepared image
resources. For those fixture baselines, await a current accepted post and drained render queues,
not an impossible `resourcesMatchCurrent()` condition.

Every fixture registering or reconfiguring `UiAutomation` must save both
`enabled_accessibility_services` and `accessibility_enabled` before registration. In teardown,
switch to `FLAG_DONT_USE_ACCESSIBILITY` before restoring both exact values, then assert restoration.
UI-only automation can enable an inactive remembered service and contaminate the next bound-service
fixture. Keep rejecting enabled capture intent; use a separately owned fresh-data guest instead of
clearing an existing guest's settings or app data.

`UiAutomation.executeShellCommand()` executes directly, without shell quote interpretation. For
validated, nonempty, whitespace-free setting tokens, pass the literal token without added quotes.
Reject unsupported original values before connecting automation, and verify exact restored values
both in the fixture and through an independent external read after disconnection.

Recover debug-app internal Censor Lab files with the authorized `run-as com.subhub.app` path.
Copies in external app storage may not be shell-readable; do not change permissions or repeat
passed workloads to repair export evidence. Verify the structured payload as well as the command
status: Android `exec-out` can return diagnostic text with a zero host exit code. Preserve failed
exports and shutdown observers, and reconcile actual closure separately.

Source-based instrumentation inventories must accept fully qualified eligibility annotations such as `@androidx.test.filters.SdkSuppress` as well as imported annotation names. Reconcile every selected method against the actual package, annotation and APK source witness before launching the guest.

Remote transport fixtures with an isolated Context must complete `LegacyPackMigration.recoverAndMigrate(context)` and assert `RemoteFeature.recoveryReady()` before testing session cancellation or background expiry. Migration readiness belongs to that Context's files and preference stores; readiness from the application Context does not qualify the isolated fixture.

For `MainActivity` instrumentation, launch with `Intent.ACTION_MAIN`. Home normalizes its
intent to that action; `ActivityScenario` compares the launch and activity intents and otherwise
ignores valid lifecycle events. Preserve the mismatch log and correct only the fixture; do not
change Home behavior, widen the timeout or reset the guest to resolve this observer failure.

Seed earned synthetic achievements and consume their pending notifications before navigation-only
fixtures. A one-time achievement snackbar can cover a correctly located row; dedicated notification
tests must retain the real notification path.

On retained Android 13+ guests, a denied notification permission can suppress a fresh
system prompt. Inspect the grant and user-set/user-fixed flags before prompt tests;
reset only that controlled package permission's flags, preserving the original failure
and rerunning only the failed contract against unchanged APKs.

For controller-dialog native taps, wait for UIAutomator idle after the keyboard opens
or closes before tapping Cancel or Unlock. Espresso main-thread idle does not settle
another process's keyboard window. Retain dialog identity when diagnosing a missed
tap so it cannot be confused with a reopened authentication prompt.

Use the constant family declared by the current SDK method's `@IntDef` contract.
Equal-valued legacy aliases can compile but still fail Android lint's `WrongConstant`
check. Guard constant classes introduced after `minSdk` and qualify both compilation
and lint rather than suppressing the mismatch.

For keyboard regressions, check initial launch, activity recreation, and tap-to-edit
separately. Android can restore an EditText's focus after `onCreate`, so a launch-only
focus check does not verify recreated screens.
For media teardown, hide the MediaController and detach it with
`VideoView.setMediaController(null)` before stopping playback. Do not pass null to
`MediaController.setMediaPlayer`: Android immediately queries that control. Verify pause,
role changes and local revocation while media is actually playing.
After a failed SharedPreferences `commit()`, receipt values may already be visible in memory.
Require a successful persistence acknowledgement before marking the corresponding durable effect
complete; `contains()` alone does not establish that the ledger and receipt reached disk.
Checked widget state can also be restored after `onCreate` and invoke change listeners.
When preferences are authoritative, fence persistence, scheduling, and network side effects
during framework view-state restoration, then render from those preferences. Native
recreation regressions must cover both directions when saved widgets and stored values differ.
Gate Android instrumentation tests that require newer platform APIs with
`@SdkSuppress(minSdkVersion = ...)`, not `@RequiresApi`. This applies to helpers in
`androidTest` as well as test methods. Add an explicit SDK guard around a helper's newer
API call where needed; lint rejects `@RequiresApi` anywhere in test sources.
RemoteFeature uses multiple concurrent session workers. A fixture holding invitation creation
pending across recreation must verify the worker count and gate every worker before submitting
that creation. Verify the accepted future is still pending and release all fixture gates in
`finally`; one blocked submitted task does not serialize the pool.
Statistics refresh fixtures must advance the actual retained counter baseline. Lowering a
preference does not remove dated database evidence and cannot prove that progress changed.
Preserve existing history when selecting the next synthetic counter value.
Keep debug manifest Activities and Services instantiable at the minimum SDK: guard their entry
points and annotate API-specific helpers, not the whole component. A component-wide `@RequiresApi`
also raises its manifest requirement, even when its entry point contains a runtime SDK guard.
For `CensorEngine` capture adapters, an admitted dispatch must precede the capturer's return. To move
copying off the main thread, schedule the whole capture invocation on the worker; do not return
before a queued adapter attempts admission. Keep `tryDispatch()` at actual source consumption.
Full-display buffer coordinates are not View-local coordinates. Bind small native pixel witnesses
to measured on-screen View bounds, checking those against an independent native bounds source;
do not force an inset app View to origin zero or compensate the image with guessed offsets.
Controlled scheduler fixtures must execute only due, uncancelled tasks in deadline
order. Capture and display-expiry wakes are independent; insertion order can fire an
expiry in place of a capture and invalidate an otherwise unrelated adapter test.
Spinner value and option TextViews must not inherit interactive EditText flags.
Keep them nonfocusable and nonclickable so taps reach the spinner or option list;
verify opening, choosing, and saving with native taps rather than `setSelection` alone.

For Windows 10 emulator setup when the bundled screenshot helper fails, follow
[Android capture fallback](docs/censor-lab/android-capture-fallback.md). Check supported
keyboard-only control before declaring all UI automation unavailable; never fabricate screenshot
IDs or bypass denied permission/input operations.

## Documentation scope

Do not create or retain numbered Censor pass writeups, pass registries, or internal experiment
diaries in project documentation. Keep reusable developer instructions and product documentation;
temporary test evidence belongs in ignored build reports, not GitHub.

## Documentation screenshots

Use meaningful synthetic feature configurations, not empty/default-only screens. Every Limits
capture must show one or two selected installed apps with distinct custom daily allowances.
Seed and assert that fixture in the capture test so future passes retain it. Never use personal
phone data or live payment credentials for documentation or marketing screenshots.

## Diagnostic trace verification

Run negotiated peer feature phases inside `RemoteSession.exchange`, before its
`done`/`done_ack`/`done_complete` drain. QUIC connection closure can overtake buffered
frames after that drain; qualify new phases with real bidirectional transport,
legacy-peer, reconnect, and revocation tests.

For main-thread sampling, follow [live Accessibility profiling](docs/censor-lab/main-thread-profiling.md).
Instrumentation setup must not be mistaken for a live, bound Accessibility pipeline.
For video/source comparisons, follow [recording clock alignment](docs/censor-lab/recording-clock-alignment.md).

Before using performance traces as evidence, compare raw publication counts with parsed counts.
Every trace-schema change must include a parser fixture for the changed record. Unknown fields or
unsupported formats must be exposed as incomplete parsing, never silently reported as zero work.

Select byte and row bounds for each artifact's schema. Expanded raw traces must not pass through
a smaller maintenance-manifest reader; exercise the actual file reader above the small-manifest
limit and at the raw-trace refusal boundary. Preserve observer failures and recover completed
captures offline rather than repeating the producer to obtain a successful receipt.

`SceneEngine.Source.pixelAtMillis == -1` explicitly means unknown. Preserve and test that sentinel;
never substitute receipt time or a raw API-reported stamp. Validate each clock field's own contract
instead of applying a blanket nonnegative range to every timestamp.

Reconcile copied drawing members and accepted POST members in both directions using literal
post serial, endpoint generation and member identity. Bind geometry and evidence source identities
separately because mixed origins are valid. A matching subset is not complete member coverage;
missing or truncated copies must remain explicitly incomplete.

Presentation revisions are opaque signed-long equality tokens, not timestamps. Diagnostics must
preserve their full domain and use explicit record state, not token sign, to distinguish missing data.
At a live renderer callback, verify actual Surface-use and drawing-lease counts. Reserve whole-owner
`managedResourcesDrained()` checks for final shutdown; an open resource owner is not drained.

## Release procedure

1. Complete the version bump and release notes in reviewable commits.
   When refreshing both release outputs locally, write the single-release fragment to a temporary
   `--changelog-output` path and use `--history-output CHANGELOG.md` for the cumulative changelog;
   `--changelog-output CHANGELOG.md` replaces the history with only the current release.
2. Verify the command above and review the staged diff for secrets or unrelated files.
3. Push the tested commit to `master`.
4. Create and push the exact version tag, for example `git tag -a v0.2.0 -m "SubHub 0.2.0"` followed by `git push origin v0.2.0`.
5. `.github/workflows/release.yml` validates tag/version parity and exact-source canonical CI, checks the release variant, signs all APKs, and stages the GitHub release as a draft while uploading each universal/per-ABI artifact and checksum separately. It must verify the complete asset set before publishing because immutable releases reject later uploads and permanently consume a tag even if deleted.
6. `scripts/generate_release_notes.py` categorizes every commit since the previous tag, places those changes in both the GitHub release and updater manifest, and keeps APK-selection guidance in its own release section. The release fails instead of publishing an empty changelog when a commit lacks a supported type prefix.

Release signing is supplied only through the repository Actions secrets named in the workflow. Do not weaken signing or manufacture a different key for a later release; Android updates require the same key.

## Development releases

Only pushes to `dev` build the exact pushed commit and publish a signed GitHub
prerelease named `v<VERSION_NAME>-dev.<cycle number>`. The cycle starts at 1 after the
latest immutable stable publication and counts dev push runs only, including failed slots
so retries reuse the same label. Keep dev's version.properties aligned with the published
stable version. Existing global-suffix prereleases retain their tags and Android codes.
Do not cancel an older push's
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
