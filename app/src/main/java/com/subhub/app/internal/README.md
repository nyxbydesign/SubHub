# Internal Sub data and adapter boundaries

This package stores an independent, editable internal Sub. Explicit adapters connect its acknowledged
profile and configuration to the ordinary profile, native settings, favorite/roster and Tasks layouts.
The identity never aliases `PersonId.LOCAL`, creates a remote peer, reads or swaps the physical user's
configuration, or grants controller authority. `InternalSubContext` is an editor data adapter, not a
runtime host or security sandbox. Runtime and financial managers must not receive it.

`InternalSubData` owns the chosen data instance and serial worker. `InternalOwnerDataBroker` supplies
separate, invocation-bound editor, task, notification-cleanup and exact-runtime capabilities over
that same owner data. Profile readability does not prove that its separate owner data is available.
Initial creation acknowledges both namespaces before publishing readiness; established missing or
corrupt data is never silently recreated or treated as an empty account.

The five-second Back gesture remains confined to Add a Sub. Visibility changes require a typed
`InternalVisibilityBarrier` proof consumed at the broker mutation boundary. Its provider must revoke
availability and prove the exact physical owner journal idle, or stop and drain that exact internal
owner, before changing generation. The default provider refuses because runtime reconciliation is
not connected yet. Session Start also remains unavailable. These fail-closed paths are deliberate
incomplete integration boundaries, not evidence of functioning enforcement.

Tasks use an owned SQLite projection and journal through the same serial broker. Current progression
is page-driven; the pure scheduler and bounded notification receipt adapters do not yet install a
background worker, deliver notices or apply task-failure assessments. Background effects must use
explicit owner admission and acknowledged receipts, never physical TaskRuntime or payment managers.

## Entry and persistence

1. Choose an explicitly owned parent under the physical application's no-backup storage. The native
   fixtures instead choose one newly created cache directory and clean only that directory.
   The backend canonicalizes this independently trusted physical-parent boundary once to support
   Android's legitimate `/data/user/0` / `/data/data` alias. Do not provide an untrusted owned root
   or asset path as the parent. The `dummy_sub_preview` root and all owned descendants are never
   canonicalized through symlinks. If integration adds its own intermediate owned directory, it
   must reject links in that directory before passing it as this trusted boundary. Concurrent lazy
   area creation is accepted only after verifying the existing child is a safe owned directory.
2. Only an explicit first-time provision operation may call
   `FileInternalSubBackend.createNew(parent, profile, InternalConfigurationStore.defaults())`.
   Never implement `catch (open failure) { createNew(...) }`. A missing entire profile must require
   an explicit recovery/reset decision, not be mistaken for first launch. Creation refuses an
   already existing namespace, including an empty directory left by interrupted initialization.
3. Subsequent opens use `openExisting(parent)` and `InternalConfigurationStore.open(backend)`.
   The latter is the production factory: every store read and write, including visibility changes,
   validates the complete catalog and native profile. Missing, malformed or unknown schema evidence
   is not filled from defaults. A future schema change needs an explicit migration.
4. `read()` returns an immutable `InternalSubRecord`: stable `targetId`, opaque UUID `generation`,
   nonnegative `revision`, `configurationRevision`, visibility, profile and complete typed config.
   Version 2 also stores `tasksResumeAfterMillis`, atomically captured on an explicit enable transition.
   Readable version 1 retains an unknown boundary of zero; reads never invent a clock or upgrade it.
   Tasks with an unknown boundary remain unavailable rather than silently empty or disabled.
   `InternalProfileAdapter` bridges the ordinary `ProfileData` normalization/image validation.
   There is deliberately no role, credential, real identity, consent, financial account or peer.
5. The backend compares the entire expected record under a process gate and OS file lock. New data
   is encoded with bounded typed fields and checksum, written and fsynced, atomically renamed, then
   its directory is fsynced and the record read back. No non-atomic replacement fallback exists.
   Any uncertain outcome leaves that store instance quarantined with no acknowledged callback.
   Explicit recovery can reopen, validate and fsync the retained record, but cannot reconstruct a
   runtime capability or publish a new admission token on behalf of old work. If directory fsync
   or atomic replacement is unsupported on a platform, storage fails closed.

The stable owned root is `dummy_sub_preview`, not a generation-dependent directory. Hide/remove and
re-enable each rotate generation and advance the record revision, preserving profile/configuration
and assets, task, history, file and cache namespaces. No deletion/reset API is provided. Generation
rotation does not itself stop a service; an integration must explicitly revoke routes and stop only
that exact admitted owner, retaining occupancy until its owned resources drain.

The physical runtime owner journal is global and must never live in any of these namespaces.
`InternalSubContext.getApplicationContext()` returns itself, not an Application, and database calls
are rejected. The owner backend independently requires the physical Application context.

## Editing and live revisions

`InternalConfigurationStore.edit(acknowledgedRecord)` creates an editor-local draft with ordinary
SharedPreferences getters, apply/commit and preference-change listeners. **Both apply and commit
only acknowledge an in-memory draft.** They do not fsync or report durable success. Normal controls
can stage slider changes without disk IO. Caller code debounces and serializes `flush(draft)` on a
worker, handles conflict/failure visibly, and uses its returned record as the durable receipt.
Reusable SharedPreferences Editors consume their pending operations and clear flag on successful
staging before callbacks run. A rejected edit leaves both the draft and pending operations intact,
so callers may correct invalid values without partially applying a batch.

When writes require an external controller-admission point, first call `prepare(draft)`. Its
`PreparedEdit.base()` and `configuration()` are immutable and describe the exact proposed write.
Admit that specific image, then call `flushPrepared(prepared)` outside controller/policy locks.
Preparation reserves no storage lock; discard the image when admission fails. Each prepared image
is one-use, bound to its config store and draft, and rejects overlapping flushes or changed bases.
It never includes edits made after preparation. An already admitted write may finish after authority
revocation; queued work must obtain its own current admission. This write admission is not runtime
start authority, and successful persistence cannot mint new authority. The convenience `flush(draft)`
is prepare+flushPrepared for callers without a separate external admission point.

The draft captures one immutable save image. Edits arriving during persistence remain staged for
the next flush; a successful acknowledgement updates only its base. Concurrent flushes of the same
draft are rejected rather than holding an editor lock across persistence callbacks. No-op saves do
not fabricate a new revision. Old-generation, stale-profile or stale-config bases conflict rather
than overwriting newer data. A failure never advances the draft's acknowledged base.

Config saves advance the configuration revision immediately, including during a session. There is
no blanket next-session-only rule. The runtime owns its separately acknowledged applied revision
and each module's usual live-update behavior, permission/restart requirements and effect receipts.
The immutable configuration maps are the runtime adapter's input; the editor Context is not.
`snapshotPreferences(record, storeName)` supplies an immutable view for explicit consumers such as
`SettingsRepository.forPreferences`, with no write or implicit live subscription. Replace that
snapshot only after the runtime has validated the target, generation and revision under its lease.

UI listeners report staged changes. `InternalSubStore.Listener` reports acknowledged records after
storage/file/editor locks are released, and observer exceptions do not undo successful persistence.
Concurrent acknowledgements can be delivered out of order. They are observations, not authority.
The initiating operation must acquire the runtime's beginAvailability/beginAuthorization token
**before** observation/storage work and complete only that exact token after acknowledgement.
Never mint a fresh admission token in a delayed listener. Reject obsolete generations/revisions in
the live-update adapter. An admission grant is distinct from an active lease's lifetime.

## Fields and effects

Catalog sections use the same normal `ConfigurationSettingCatalog` field normalization, defaults
and cross-field validation. Reads/writes to unlisted keys are rejected, even when the normal store
name is correct. The physical Settings store also contains controller salt/hash and role mode;
none can enter these internal preferences.

Additional explicit data schemas are:

- `apps`: selected package names (bounded at 4096), the All Apps choice, a separately observed
  package-catalog snapshot and an internal refresh counter. Never copy the user's live selection.
  The refresh counter is editor bookkeeping, not controller or runtime authority.
- `allowances`: validated 1–1440 minute values keyed by package. The draft maps these to normal
  `app_timer_allowance_minutes:<package>` preference keys. Missing allowances use the caller's
  normal default; removing a selection does not erase its stored allowance. No real usage counters,
  remote credits, armed marker or statistics are imported.
- `duration`: the normal permanent/random/fixed choice, minimum, maximum and hidden-countdown flag.
  There is no pact state, boot timestamp, password, session identity or scheduled expiry.
- `artwork`: up to 512 Censor/Popup content-hash references and an explicit disabled set. No content
  URI, external folder grant, absolute path or remote asset reference is accepted. A typed manifest
  update advances `custom_images_revision` and notifies the draft's normal preference listeners
  outside its lock. That revision is read-only to raw preference editors. The manifest can be
  changed atomically to remove a disabled asset. Imported content and hash/existence validation
  still require a separate immutable/staged asset adapter before config publication.
- `wallet_display`: EUR/USD denomination and a fixed true modern-rule-schema marker. These are
  independent internal presentation/schema values, never migrated from real payment state. Writes
  cannot re-enable the legacy migration path. The normal Wallet effect managers remain prohibited.
- `popup_local`: photosensitivity acknowledgement defaults false and requires the actual UI choice.
  It is not role consent, authentication or permission to invoke an OS effect.
- `presets`: independent saved Censor looks using the normal 32-look, typed appearance-only schema.
  Subliminal and Popup preset selectors already belong to the ordinary catalog. Preset commit is
  still only a draft acknowledgement; integration must await flush before reporting durable save.

Wallet rule configuration is ordinary demo configuration, not permission to invoke PenanceManager,
PayPal, paid pause checkout, autopay, remote logging or payment scheduling. Runtime integration must
use an explicitly nonfinancial local effect adapter. Runtime/task/history state likewise requires
typed, scoped adapters, not merely a different Context passed to production process singletons.

## Context limits

`InternalSubContext` serves editor data only. Application canonicalization preserves scope.
Resources, bundled read-only assets and legitimate local package-catalog reads remain available.
Unknown preferences, databases, external file roots, content resolvers and common effect calls are
rejected; source authority must still be observed using the actual user/controller context.

This wrapper is **not a security sandbox**, service/window token, full OS-effect interceptor or
stale-write capability. It does not scope StatsRepository, DailyStatsStore, TaskRuntime, notification
IDs, alarms, network clients, global caches or code that kept another Context. It cannot revoke a
previously returned File/path/open stream when a generation is hidden. Asset imports must write new
immutable/staged content and generation-check publication through the config CAS; never mutate a
published asset in place. Raw paths must never stand in for lease/owner-specific effect permits.

## Verification

- `InternalStorageChecks` / `InternalStorageTest`: SDK-independent core/file contracts, shared
  with the actual JUnit wrapper. These do not substitute for Android filesystem qualification.
- `InternalConfigurationTest`: real catalog-backed draft/config contracts for canonical JUnit.
- `InternalStorageAndroidTest`: six owned native fixtures covering on-device persistence/reopen,
  scope canonicalization, forbidden physical-store access, owner-journal exclusion, effect rejection,
  data retention and corrupt/missing-record refusal. They must run on a preprovisioned host.

No native execution, UI runtime, real enforcement, canonical lint/build or release qualification is
claimed by writing these tests. Production integration and independent review remain required.
