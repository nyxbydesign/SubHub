# SubHub changelog

User-facing changes from every tagged SubHub release.

<details open>
<summary><strong>SubHub 0.7.0</strong></summary>

## What’s new in SubHub 0.7.0

### New

- **Reframe Atmosphere and arrangement navigation** — Give Atmosphere a Dom-only navigation destination, keep its Sub view as a compact read-only Home card, and move arrangement creation and profiles behind the shared Settings surface.
- **Add shareable Censor Lab captures** — Let users pair Android's native screen recorder with an opt-in, bounded SubHub telemetry session, visible synchronization markers, optional recording attachment, and one shareable diagnostics ZIP.
- **Record Censor Lab captures in app** — Censor Lab now requests Android screen-capture consent, records video and sanitized telemetry as one bounded session, and builds a ready-to-share ZIP without a file picker.
- **Make shared app controls adapt to narrow screens and text size** — Keep core headers and navigation readable, preserve minimum touch targets, and unify core field and action styling without changing the purple branding or role-based destinations.
- **Prototype bounded background viewport anchor sampling** — Add an inactive single-worker sampler and absolute screen-space geometry core to recover fresh positions without queueing reads or granting tracker/cache authority.
- **Separate current-view quality admission from cache eligibility** — Add explicit current-only quality stamps with window, document, transform, phase and camera fences in the latest-only mailbox.
- **Carry immutable render provenance with raw observations** — Keep capture-origin and measured-bias metadata separate from tracking decisions.
- **Add source-relative render coordinate contracts** — Keep each snapshot geometry reference, calculate per-region offsets and compatible bitmap sampling, and allow steering to forget a changed coordinate basis without resetting unrelated tracks.
- **Reconcile visual camera anchors with delayed event intervals** — Add a bounded, unintegrated camera accounting core that prevents covered Accessibility deltas from moving a visual anchor twice.
- **Refine clean-frame spatial registration with distributed patches** — Add a bounded subpixel image-registration core, synthetic controls, private-corpus replay and sparse-cadence Android cost measurement.
- **Retain scoped source-image coordinates across rejected frames** — Own a bounded reference image and explicit map generation so rejected frames stay unknown while later spatial matches can recover correspondence.
- **Place cached visual regions in registered source-frame coordinates** — Add an OFF-by-default source-image cache path for fast publications and scroll re-entry.
- **Add guarded source-image tracking continuity experiment** — An emulator-only experiment preserves eligible visual track identities using registered source movement before clipping and association, while isolating cache output and leaving production defaults unchanged.
- **Add object-local image matching to tracking experiment** — The emulator-only tracking experiment can preserve source-frame correspondence across unknown global poses and body-crop edges using scoped local pixel evidence without inventing a display camera.
- **Guard same-capture immediate quality refinement experiment** — Define strict eligibility for refining an already-visible fast capture without another inference, preserving structural and motion fences.
- **Add bounded quality worker ownership and admission** — Add the quality worker dependency for reproducible latency candidates, retaining one pending source while fast work has priority and releasing claimed frames and permits on every completion path.
- **Consolidate overlapping body censors with deterministic head hints** — Render plausible same-body overlaps as one coverage-preserving censor with a deterministic label, while distinct head assignments separate neighboring people.
- **Add tiled quality preparation and bounded contention governor** — Make the quality lane crop plan and fast-tail contention guard reproducible dependencies for the signed Accessibility candidate.
- **Carry measured render coordinates through overlays and cache** — Preserve each censor and effect bitmap coordinate reference across cached and live presentations, and apply bounded asynchronous anchor measurements without adding event displacement twice.
- **Integrate measured quality delivery and same-capture refresh candidate** — Wire the owned concurrent quality lane, later-fast handoff, render-coordinate propagation and default-off immediate refresh into Accessibility.
- **Add confidence-gated scroll calibration core** — Prepare automatic per-app scroll learning with context-separated numerical profiles, independent held-out gesture validation, drift revocation and bounded probe work.
- **Add aligned and reusable scroll calibration evidence** — Align independent anchor history with delayed scroll-event intervals and keep compact validated profiles in bounded device-local no-backup storage.
- **Observe active scroll surfaces with bounded local learning** — Collect independent per-surface calibration evidence during active censoring, with compiled-resource candidate keys, bounded worker reads, fresh validation, private persistence and numeric diagnostics.
- **Apply validated local scroll calibration to live motion** — Adapt future Accessibility scroll increments and bounded overlay timing only after independent holdout validation, preserving the visible camera origin and fencing incompatible cache and capture work.
- **Prepare optional person coverage with shared image inputs** — Add default-off person coverage primitives, provisional body association and a lazy NanoDet session that can reuse existing detector pixels and outputs.
- **Render frame-scoped provisional and refined person masks** — Add render-only whole-person expansion with exact-source matching, capture-time expiry and process-unique refinement tokens.
- **Add optional whole-person censor coverage in both capture modes** — Offer default-off whole-person coverage with immediate provisional masks and same-source model refinement.
- **Add PERSON-only NudeNet post-training kit** — Train a separate person box head on frozen shared NudeNet features without requiring the original dataset, and export one parity-checked graph.
- **Encrypt PayPal merchant credentials in shared arrangements** — Dom-authored arrangements can now include a passphrase-encrypted PayPal client ID, secret, environment, and fallback recipient link for trusted recipients.
- **Save Censor Lab ZIPs directly to Downloads** — Save complete local capture bundles without choosing a share recipient, using pending MediaStore rows on Android 10+ and a document picker on older supported versions.
- **Support currency-bound EUR and USD Wallet settlements** — Wallet uses PayPal primary-currency discovery when available with an explicit EUR/USD fallback, preserves historical denomination and blocks switching until unpaid balances are resolved.
- **Unify native settings and arrangement pack workflows** — Replace legacy profile, backup and pack-lock interfaces with one native four-step pack creator and consistent feature-only configuration under Enter Service and Leave Service.
- **Compact settings choices and simplify censor configuration** — Put Appearance first, embed replacement image management, replace Whisper intensity radios with four stops, and compact choice lists with readable wrapping.
- **Add popup intensity slider and clarify detection labels** — Select Popup intensity with four discrete stops and separate each detection level from its smaller italic explainer.
- **Share app coverage and compact configuration surfaces** — Make All apps cover enabled Censor, Limits and Subliminal features, compact draft forms, remove redundant captions and put Home achievements after lifetime statistics.
- **Broaden phrase pack voices and reduce repetition** — Expand themed Censor labels and Subliminal messages with distinct non-graphic voices and avoid repeated text in the display pool.
- **Make configuration clearer and preserve edits reliably** — Group Censor choices into selectable tiles, use explicit On/Off status controls, and put core setup ahead of advanced decoration and payment details.
- **Theme dialog shells and inline notices consistently** — Give app dialogs, color picking and inline notices a consistent plum theme with high-contrast actions and rounded surfaces.
- **Unify achievement badges around a dark plum palette** — All 58 achievements retain their gothic designs and use a coordinated dark plum-purple palette with subdued metallic highlights.
- **Publish branch dev builds with opt-in app updates** — Publish signed GitHub prereleases for every non-master push and let controllers opt into Dev updates without ADB or external file hosting.
- **Add signed development releases and opt-in update channels** — Publish every non-master push as a signed GitHub prerelease and add a default-off Dev updates toggle without carrying the separate UI redesign into master.
- **Add signed development releases and opt-in update channels** — Publish every non-master push as a signed GitHub prerelease and add a default-off Dev updates toggle without carrying the separate UI redesign into master.
- **Port encrypted PayPal arrangement transfers to master** — Studio can attach merchant details with a separate passphrase and explicit recipient confirmation; applying an encrypted pack clears inherited payer authorization and preserves local rollback.
- **Port encrypted PayPal arrangement transfers to master** — Studio can attach merchant details with a separate passphrase and explicit recipient confirmation; applying an encrypted pack clears inherited payer authorization and preserves local rollback.
- **Port currency-bound Wallet settlements and cashout batching** — Wallet supports EUR and USD with denomination-preserving history, exact PayPal order/capture validation, and safe currency changes; background cashouts accumulate to the internal minimum without restricting explicit manual settlement.
- **Port currency-bound Wallet settlements and cashout batching** — Wallet supports EUR and USD with denomination-preserving history, exact PayPal order/capture validation, and safe currency changes; background cashouts accumulate to the internal minimum without restricting explicit manual settlement.
- **Add durable photo and video export processing** — Prepare independent gallery export settings and background jobs with timestamp-preserving H.264 video, audio preservation or muting, cancellation, and validated gallery publication.
- **Port finished control surfaces and arrangement workflows** — Bring the completed themed Home, Settings, Censor controls, app assignments, Wallet, Atmosphere, Studio creator, and illustrated milestones to master while retaining its stable Censor engine.
- **Add authenticator access and private randomized Pacts** — Allow paired authenticator codes at every Dom PIN prompt, and let users agree to a random Pact range and hide its countdown before entering service.
- **Connect gallery video previews and background export controls** — Expose photo/video selection, independent censor settings, quality and audio controls, previews, progress, retry and saved-copy actions in the SubHub gallery.
- **Give Keyholder setup a playful native handover flow** — Use the existing SubHub header, gradient cards, rounded actions and clear pairing steps for handing over a key.
- **Unite Keyholder PIN and remote approval in Rituals** — Make Keyholder discoverable through Rituals and a dismissible Home introduction, with protected PIN management and authenticator pairing together.
- **Add privacy controls to focused Settings categories** — Keep Settings concise with focused categories and optional discreet presentation and device-authenticated app entry.
- **Explain both Keyholder methods in exclusive expandable cards** — Show matching in-person and remote setup guides, with Google Authenticator examples and one expanded method at a time.
- **Record durable daily statistics and completed export receipts** — Retain dated feature counts, separate currency totals and checkpointed service time without reconstructing missing history from lifetime totals.
- **Show compact Home activity and detailed daily statistics** — Keep Home activity concise while offering dated feature trends, calendar selection and separate currency totals in Statistics.
- **Add first-run tour and consolidate Settings and notices** — Introduce skippable setup with saved feature and appearance choices, expand Settings controls in place, and show Home notices in one shared dismissible slot.
- **Refine authenticator pairing with a compact themed card** — Frame the pairing QR and six-digit confirmation in one SubHub card, keep the setup key collapsed, and remove repetitive helper paragraphs.
- **Build an informative six-step setup with resumable previews** — Guide new users through feature and app choices, actual censor-effect previews, optional Keyholder approval, Android access and a final service review; preserve unfinished drafts across relaunch and keep replay previews from overwriting…
- **Organize Settings around apps, access and arrangements** — Separate app assignments from Android permissions, group arrangements and protection together, place PayPal under Wallet, and order expandable Settings cards by purpose with current readiness summaries and a Whispers feature toggle.
- **Place PayPal setup and compact Wallet editors within Wallet** — Wallet combines the amount due, payment action and recent ledger in one compact surface, with focused Rules & caps, Paid pause, PayPal and Corrections editors.
- **Add packs and Gallery Censor to Rituals** — Rituals puts packs first and opens Import, Library, Achievements and Gallery Censor, while Statistics stays on Home.
- **Guide Accessibility setup one step at a time** — Help and first-run setup open a themed Accessibility guide whose later steps stay greyed out until the preceding settings button is used.
- **Retain app selections beneath the All Apps override** — All Apps now applies a dynamic installed-app scope while preserving the manual selection and its daily allowances.
- **Put service controls on their pages and keep Dom access explicit** — Censor uses its Off detection choice, Limits uses its two budgets, and Wallet uses its individual rules.
- **Animate the service clock and censor hidden countdowns** — Timed service replaces the duration choices with an animated clock at the same height.
- **Rework Help around searchable answers and current controls** — Help now offers searchable, expandable answers, accessible tools and accurate permission, role, app-scope and service guidance.
- **Preserve duration choices and animate the Home clock** — Home keeps the selected duration, shows Permanent as an optionally censored infinity clock, and animates configured censor effects.
- **Show Censor, Wallet and Limits together in Setup** — Setup now illustrates content cover, detection tributes and daily app time together, with clearer Blur and Pixelate previews.
- **Replace Censor and Subliminal phrase libraries** — Censor and Subliminal choices now use the supplied complete phrase rewrite, with 94 Censor phrases and 92 Subliminal messages.
- **Group consecutive Wallet activity entries** — Wallet combines consecutive matching events into a counted row with their combined amount, keeping currencies, dates and payment states distinct.
- **Add compact Wallet rules to the six-step setup** — Setup now follows Introduction, Censor, Wallet rules, Apps, Keyholder and Permissions, with Finish on the last page and an app list that uses the available height.
- **Restore Wallet caps and corrections in setup** — Wallet setup now includes daily and weekly caps and the correction window, with more space below the artwork and concise explanations for app-open and censor-tap tributes.
- **Present Home assignments in grouped bottom sheets** — Home assignment details now open in rounded bottom sheets, with a phone preview of the saved Censor style, border, colors and custom artwork.
- **Add appearance presets and replace Error popup with Lockpad** — Use built-in and saved censor looks in the live and export editors, with the existing Error popup slot replaced by an opaque Lockpad.
- **Display pack covers in Studio cards and review** — Pack cover images now appear as thumbnails in Library and Drafts and as uncropped artwork in the editor review.
- **Integrate Studio cover presentation with the replacement host** — Pack covers now appear in Library and Draft cards and the editor review, preserving uncropped artwork and the existing export/import format.
- **Admit atomic source-pair censor motion** — The replacement Accessibility engine now supports independent header/body geometry as one atomic source-pair result, rejects forward and inverse ambiguity, and retains original sampling pixels; live-service activation remains a separate…
- **Separate uncertain image presentation from tracking authority** — Keep direct source-local image hints separate from detection identity and source-pixel ownership in the replacement censor runtime.
- **Show pack and allowance details in Home sheets** — Rituals shows the active pack and keyholder configuration, while Limits lists each assigned app and its daily allowance.
- **Show the active pack cover in Rituals** — Rituals shows the active pack cover to the right of its name and version.
- **Own native text reads independently of screenshots** — Add independent native text acquisition to the replacement censor engine while keeping image timing, pixels and presentation separate.
- **Bind replacement rendering to captured source provenance** — Keep replacement-engine sources tied to their captured protection scope and reserve reverse-effect pixels from the exact presented image.
- **Paint replacement snapshots with source-local immutable resources** — Preserve all eight censor effects while drawing replacement-engine member geometry from its own captured evidence and bounded immutable artwork.
- **Bound replacement paint resource preparation** — Replacement paint resources can now use one running preparation and one latest pending descriptor with at most two output generations, keeping retired draw leases and delayed UI work from accumulating resources.
- **Add owner-backed snapshot censor surface** — Add an unwired snapshot-only Android censor surface with fresh owner drawing, bounded asynchronous artwork preparation, and source-scoped visible tap attribution.
- **Integrate source-owned OCR into the replacement censor host** — The replacement host can detect screenshot text independently of visual inference while preserving source-local line groups and the existing text settings.
- **Bind replacement censoring to real Accessibility windows** — Add a real Accessibility binding for replacement capture, native text and a worker-rendered overlay without activating the replacement in the product service.
- **Add source-owned censor evidence claims** — Prepare replacement censor counting to consume accepted detections once without treating redraws or repeated lane results as new evidence.
- **Connect replacement source evidence to session-safe accounting** — Privately qualified replacement censoring now records confirmed source counts and feeds existing Money Rules and achievements without duplicate delivery or attribution to a newly armed session.
- **Connect replacement dwell and popup placement** — The privately qualified Accessibility replacement preserves viewport dwell episodes and places popups using current source-owned detection hints, without changing default censoring.
- **Enable opt-in replacement Lab candidates** — Add a controller-protected replacement-engine selection to private signed Lab candidates without changing normal builds.
- **Enable replacement-engine testing in development updates** — Normal development updates now offer the opt-in replacement censor engine on Android 14+, while stable builds retain their existing engine.
- **Preserve source-owned native text coverage through scrolling** — Opt-in replacement censoring preserves ordinary native-text identities through scrolling and refreshes admitted nodes independently of screenshots; default censoring is unchanged.
- **Add replacement projection input ownership boundary** — Add a consuming newest-image mailbox and direct replacement-engine adapter without enabling projection capture or changing Accessibility defaults.
- **Add isolated continuous-capture source ownership** — Prepare a controlled continuous-capture reference without changing Accessibility capture or active censoring.
- **Schedule dev checks every fifteen minutes and show release time** — Development updates use a fifteen-minute schedule and Preview history shows local publication time with its UTC offset, while stable updates retain their six-hour interval.
- **Add encrypted peer transport and durable sync foundation** — Add identity-bound encrypted peer connections and transactional per-peer synchronization primitives, including acknowledged changes, revocation records, protected installation keys and resumable verified assets.
- **Separate Dom and Sub navigation around people and contracts** — Give Dom a People workspace and person-scoped contracts, stats and connection pages, while Sub receives read-only Contract and Stats destinations with existing service actions on Overview.
- **Add mutual peer pairing and resumable duplex sync** — Bind pairing approval to both authenticated device identities and recover interrupted handshakes without repeating approval.
- **Integrate role UI and peer sync foundations** — Combine separate Dom/Sub role views with identity-scoped person repositories and encrypted peer synchronization foundations.
- **Add journaled remote contracts and retained usage history** — Apply versioned peer configuration through a recoverable journal, preserve credentials and current sessions, and retain daily Limits usage beyond rollover for incremental sync.
- **Add paired device screens and targeted remote session End** — Pair two phones with explicit identity approval, synchronize contracts, artwork, statistics and runtime state, and retain queued changes for an unavailable peer.
- **Integrate person contract flows with paired device management** — Combine the revised person, Contract and saved-library navigation with paired device configuration, statistics and targeted session End.
- **Unify keyholder setup and paired device authority** — Keyholder setup offers a local PIN or approved device pairing, and paired Subs unlock with a generated one-time code supplied by their Dom while profile pages separate configuration, status, and connection controls.
- **Add paired Activity and streamlined device profiles** — Show paired Subs' app entries, assessed Tributes and reached limits in a durable Activity feed, with per-person history, read state and generic Android notifications.
- **Refine overview and profile controls** — Bring the Dom and Sub overviews closer to the reference, surface session and device status, restore achievement previews, and simplify profile session and contract actions.
- **Group Activity entries by date** — Show a local calendar date heading for each run of events while preserving individual navigation and read state.
- **Share profile feature editors and respect Sub choices** — Local and paired profiles use the same native feature pages, with separate Dom and Sub names, consistent app rows, and isolated remote drafts.
- **Collapse repeated Activity events into groups** — Activity collapses matching events for each Sub and local calendar day into one card with an event count.
- **Add paired Tasks with explicit completion review** — Subs can receive timezone-aware recurring tasks, submit completion proof or outside-app requests, and use author-permitted occurrence or series refusal without a failure tribute.
- **Integrate Tasks with UI V3 and monitoring-free P2P** — Combine the tested Tasks feature and accepted UI V3 with the monitoring-free P2P candidate, preserving task proof, notifications and consent over their separate negotiated channel.
- **Merge Tasks and monitoring-free P2P into UI V3** — Make paired Tasks available with UI V3 while retaining local consent and the monitoring-free P2P policy.
- **Make paired Tasks and monitoring first-class** — Restore consented paired Activity, statistics, usage and runtime data and give Tasks a dedicated styled navigation flow.
- **Add inline task media and confirmed cancellation** — Tasks now group with Contract on profiles, show a live active-occurrence count on Sub Home, embed instruction media, and let Doms confirm cancellation of one occurrence or an entire recurring series.
- **Separate Dom achievements and paired Sub statistics** — Give Doms a separate 17-badge catalog and statistics for their currently paired Subs, while preserving the 57 Sub achievements and role-specific visibility.
- **Add Dom progress alongside task media and cancellation** — Dom Home now opens separate statistics and achievement views with 17 Dom badges alongside the refined Tasks experience, while preserving the existing 57 Sub badges.
- **Unify statistics and achievements across roles** — Dom and Sub now share the Home achievement card, a Stats destination with Statistics/Achievements section controls, and the same statistics page and monthly calendar presentation.
- **Streamline task proof and Sub invitations** — Task details place instructions before completion requests, show a single proof inline and open multiple proofs on a dedicated attachment page.
- **Integrate shared statistics with task attachment navigation** — Combine the shared role statistics and achievement presentation with the direct Sub invitation and inline proof layout checkpoint.
- **Recover retained achievement history and local Sub totals** — Include the on-device Sub in Dom statistics and milestones on Both devices, and recover supported historical totals and achievement dates from existing local and active paired records.
- **Integrate retained history with incremental overview rendering** — Combine local Sub totals and conservative achievement history recovery with the retained Home, Activity and Tasks view updates.
- **Integrate compressed storage with retained statistics and achievements** — Historical statistics and achievements read legacy text and compressed local and paired payloads while preserving event identities, dates, currency, consent and sync cursors.
- **Raise Dom goals and redesign achievement badges** — Raise Dom milestone requirements and give all 76 badge resources distinct plum artwork while keeping earned badges and their original dates.
- **Guide first-run setup with role-specific graphical journeys** — First-run setup now starts with Sub, Dom or Both and explains pairing, Tasks, security and permissions through a resumable graphical walkthrough.
- **Retain Subs and Stats sections within their existing screens** — Subs/Activity and Stats/Achievements now switch in place, preserving the selected section, calendar controls, feed groups and navigation without adding switch-only Back entries.
- **Add role-specific Profile with identity and participation controls** — Profile now brings each role's name, rounded avatar, About me, Stats and Achievements together, with Sub participation choices moved out of Settings.
- **Add private profile journals with voice notes and explicit sharing** — Journal entries now support private text, voice notes, and bounded attachments with explicit sharing to current pairings.
- **Unify profile previews and polish every Journal state** — Unify Dom and Sub profile previews with optional self-entered gender and age, and give Journal entries, editing, voice notes, attachments and shared states a consistent native layout.
- **Compose role overviews and the read-only Sub Contract** — Dom and Sub overviews now group their authorized profiles, own journals, session state, recorded activity, configured app time and achievements in compact native cards.
- **Combine Profile statistics and restore Overview details** — Combine statistics with both role profiles and restore titled achievement goals, local Sub counts and consistent card styling.
- **Refine Profile editing and separate Subs Activity** — Make Allowed features read-only on Sub Profile until Edit profile, and move Subs Activity into its own page while retaining feed state and guarded access.
- **Show recent Subs activity and Switch profile roles** — Preview the latest three Activity entries in Subs, align Contract and Overview status text, and label Both-mode own profiles as Switch.
- **Stage the bounded remote session command journal** — A versioned session-command journal retains scoped requests and application results with idempotent replay, while remote Start remains inactive until the runtime and consent path is qualified.
- **Prepare guarded Sub Profile session controls** — Add one duration-bound Start/Stop row to remote Sub Profiles and retain local Hardcore consent reviews across recreation while native receiver application remains disabled.
- **Integrate scoped native session consent and journals** — Prepare receiver-bound Hardcore Start consent, clock- and sequence-bounded command journals, checked native statistics and recoverable session markers.
- **Integrate guarded native session command application** — Prepare and apply remote session operations through exact runtime and statistics identities, with separate Start readiness and Stop clock observations and conservative capture-closure proof after restart.
- **Integrate guarded relationship presentation** — Show authoritative relationship rosters and explicit local profile review in People, setup and Home while preserving standalone Sub content and exact pending operation references.
- **Integrate durable session command reconciliation** — Recover remote session command results after interrupted connections without renewing a request or repeating its first native effect.
- **Gate remote session reviews on current controller authority** — Add gated remote session coordination that binds Start and Stop reviews to current controller authority, exact Sub observations, and original deadlines.
- **Define typed relationship recovery results** — Add typed relationship recovery results that keep fresh local reviews, durable operation status, and unresolved preparations distinct.
- **Add inert formal relationship storage** — Preserve existing pairing relationships while adding bounded formal-link records, stable addition ordering and atomic governing slots.
- **Enable recoverable local relationship linking** — Link local Dom and Sub profiles through explicit review, confirmation, and unlinking, with pending operations recoverable after recreation and process restart.
- **Render scoped remote app icons** — Render verified remote PNG app icons with bounded caching, cancellation, and revocation handling while retaining existing local-icon behavior.
- **Exchange and display shared app icons** — Shared app records now show their peer-provided icons across profiles, Activity, Stats and remote editors, with bounded PNG transfers and immediate revocation.
- **Enable guarded remote session controls** — Linked Doms can start a Sub's applied session with current Hardcore consent and stop that exact active session after consent is withdrawn.
- **Preserve bounded pairing operations across app navigation** — Keep pairing codes valid across app navigation with bounded background completion.
- **Add journal titles with creation-time defaults** — Name journal entries above their body, default to their creation time, and show titles in Overview and profile previews with backward-compatible sharing.
- **Add role-specific notification choices and applied session alerts** — Settings now offer independent local notification switches for Dom activity and task reviews, plus contract changes and paired session starts and stops for both roles.
- **Add role-specific event notifications and verified tribute receipts** — Show individual Dom activity choices and Sub session choices for the active workspace, and notify a Dom only after a verified tribute payment is committed.
- **Download verified updates automatically before notifying** — Automatic checks download the compatible APK silently and notify only when verified bytes are ready for the explicit Install action.
- **Keep paired sync active in the background** — Keep the encrypted paired endpoint listening through a connected-device foreground service so mutations can sync promptly in the background, with bounded reconnect backoff and a 60-second idle contact refresh.
- **Add a local background sync opt-out** — Background sync is enabled by default and can be disabled on this device while foreground syncing and queued peer changes remain available.
- **Explain contract changes and unify session and task notices** — Notifications now show readable applied contract differences and task outcomes, with one Sessions toggle and one Sub Tasks toggle that preserve existing opt-outs.
- **Add foreground presence dots to profile avatars** — Show compact green or grey avatar dots with a surface-colored separation ring, and use verified foreground history for human last-seen labels.
- **Add one-time app time requests with Dom review** — Subs can request a one-time app allowance, Doms can approve extra minutes or decline, and configured tribute is assessed when the request is submitted.
- **Restore shared six-button duration choices in current theme** — Reuse the original three-row duration layout for local and remote controls without writing local preferences from remote drafts.
- **Automatically sync Dom configuration edits with durable coalescing** — Dom feature, contract and duration editors now save valid changes automatically without Apply buttons, retain offline edits, and show save or receipt failures without rebuilding the active form.
- **Connect device activity sync and refine Tasks and profiles** — Use existing activity and app-time events for timely coalesced sync, remove hidden task and journal polling, add accepted-only Completed counts, and clarify profiles and contract details.
- **Add an isolated view-only Dummy Sub preview** — Hold Back for five seconds on the Add Sub connection page to add or remove a Dummy Sub, then return normally; its separate settings catalog has no real profile, session, relationship or configuration writes.
- **Add isolated runtime ownership foundation** — Prepare explicit DEVICE and INTERNAL ownership for the internal demo Sub without enabling new application behavior yet.
- **Persist runtime ownership without implicit resets** — Keep physical runtime ownership unavailable when existing journal evidence is missing, corrupt or uncertain.
- **Add isolated internal Sub profile and configuration storage** — Prepare an independent editable profile and normal typed configuration without copying or replacing real device data.
- **Connect isolated internal Sub editors and Tasks** — Add owner-scoped editable profile, settings, favorite and Tasks pages with durable independent data and serialized accounting boundaries.
- **Preserve first-stop clocks and counted runtime cleanup** — Add reviewed installation admission and DEVICE cleanup protocols, and retain the first exact-owner fence observation rather than extending accounting through later cleanup.
- **Connect internal Sub Tasks and coherent statistics** — Use isolated internal task authority, bounded indexed pending-effect lanes, owner-local notifications, and immutable clock-consistent statistics projections.
- **Bind exact start prompts and owner-local Limits admission** — Add reviewed DEVICE entry adapters with exact prompt routes and bounded recovery, counted preinstallation mutation admission, and the isolated Limits binding.
- **Bind internal start revocation and legacy owner accounting** — Keep internal session starts tied to acknowledged controller changes and give isolated legacy rendering exact-owner counters and nonfinancial tap assessments.
- **Add owned legacy renderer boundaries and retain current dev** — Prepare isolated legacy artwork and callback lifetimes without changing the normal renderer entry point.
- **Guard internal journal creation and managed device rendering** — Bind one-time internal journal creation to acknowledged provenance and an exact bootstrap proof.
- **Add isolated session clock and owned service lifetime prerequisites** — Prepare internal local duration and nonfinancial pause accounting without changing real sessions or activating the unfinished runtime.
- **Deliver reviewed activity, achievement and loading improvements** — Use consistent subdued progress meters, keep achievement and Activity views current, and retain authorized screen data while refreshing.
- **Assemble owned Demo Sub MediaProjection capture** — Connect the approved projection token to the actual same-owner Accessibility modules, capture service, and isolated visual accounting.
- **Bind Demo Start to the acknowledged capture plan** — Reserve the exact profile and controller revision, check that the Accessibility source is connected, and require the selected projection grant before assembly.
- **Route Demo Start through exact Android permissions** — Give the Demo editor an internal-only start capability that returns each actual Android permission callback and reports ACTIVE only after the exact owner acknowledges activation.
- **Bind profile session controls to exact runtime receipts** — Wait for the saved Demo duration before Start and keep permission callbacks tied to their attempt across recreation.
- **Complete Demo profile visibility and session End controls** — Allow an authorized controller to show or hide a Demo profile before the physical runtime is provisioned.
- **Connect Demo Censor to owned Android window capture** — Use acknowledged Demo foreground allowances, settings and local ledger for Android 14 window capture.
- **Connect Demo sessions to the bound Accessibility service** — Demo Start can borrow an idle Accessibility source, and End waits for the source to return before acknowledging completion.

### Fixed

- **Keep Popup Storm tied to active protection** — Treat Accessibility recognition—not a merely connected Accessibility service—as the protection runtime that may launch Popup Storm.
- **Keep Settings aligned with active space** — Rebind the Settings navbar whenever the activity resumes so a Dom/Sub transition cannot leave stale destinations or selection state.
- **Unify primary headers and raise achievement goals** — Render every primary destination through one shared header contract so geometry, typography, icon sizing, and top inset cannot drift between tabs.
- **Stop Accessibility motion events from intercepting touch** — Remove the Android 14 touchscreen MotionEvent subscription and all renderer prediction state after real-device validation showed that the subscription prevented the Pixel from receiving ordinary touch input.
- **Fence text updates against scrolling** — Accessibility text keeps its stable node identity through coordinate mapping, rejects scans whose motion generation or scroll position changed during traversal, and removes missing confirmed lines immediately during post-scroll…
- **Keep censor labels readable over overlapping boxes** — Render censor artwork and label bands before a final glyph layer so later boxes cannot bury earlier phrases.
- **Keep Diagnostics available in Sub Space** — Open Diagnostics as a read-only surface in Sub Space instead of letting the generic controller-edit helper redirect it to Home.
- **Separate Chromium motion producers** — Keep sticky scroll-surface identity for content-space cache continuity while resolving motion against each observed Accessibility producer.
- **Correct measured scroll phase without extending prediction** — Apply same-direction measurement correction under the existing 56-pixel jump cap and retain bounded reversal and deceleration braking.
- **Add explicit motion invalidation policy for scene transactions** — Allow callers to retain reprojectable fast-only transactions through ordinary scroll while preserving structural invalidation, supersession, and atomic-scene safety.
- **Parse quality preparation spans with old-frame metadata** — Accept the optional oldFrame token without silently dropping preparation timing and native-overlap evidence.
- **Exclude censor labels and page art from alignment evidence** — Recognize supported dark-filled purple censor components and mask their full interiors instead of treating colorful page art as boxes.
- **Reconcile measured scroll independently of prediction** — Catch up remaining known viewport displacement within 16ms instead of spreading it over the speculative trajectory.
- **Preserve anchor baselines across slow reads** — Reject over-budget observations without throwing away valid reference geometry.
- **Preserve identity and render provenance during reprojection** — A coordinate-only scroll reprojection must not discard the reconciled track ID or its source reference.
- **Expose trace parsing losses and accept render provenance fields** — Add provenance-suffix fixtures and explicit raw versus parsed publication counts.
- **Separate draw execution from expected presentation latency** — Measure input-to-draw on uptime rather than a future animation timestamp.
- **Reject narrow-column animation in shadow motion estimation** — Require horizontal support within each agreeing vertical band so one animated strip cannot impersonate whole-viewport motion.
- **Parse asynchronous capture publication stages** — Expose publication queue latency instead of rejecting newer trace stages, and record the normal-cadence shadow/control failure.
- **Keep screenshot receipt time separate from verified pixel timing** — Carry typed request/reported/callback timing through Accessibility capture and reject receipt-based visual camera anchors.
- **Preserve applied spatial coverage through bounded capture gaps** — Distinguish missing screenshots from newly unmatched images.
- **Count retained cache regions and clear immutable view snapshots safely** — Require actual retained cache identities before preserving applied cache coverage.
- **Preserve reprojectable fast scenes across scroll events** — Complete the service wiring for motion-surviving fast scenes while retaining conservative atomic and structural fences and rejecting old-generation event-cache writes.
- **Preserve face recall when competing labels share a category** — Retain face candidates when both leading labels resolve to the shared enabled face policy, without lowering confidence floors or relaxing cross-category ambiguity checks.
- **Guard motion handoffs against unrelated censor categories** — Preserve independent face and body-part coverage when partial overlap cannot establish object identity.
- **Stabilize padded censor groups without historical spanning boxes** — Group the actual padded footprints once, reject multi-head/span bridges, preserve stable presentation identities and ignore small covered geometry jitter.
- **Compare body head hints in shared pixel coordinates** — Keep nearby torso parts merged by removing the ranking advantage given to larger distant heads in previous image cards; preserve existing eligibility and separation guards.
- **Preserve raw scroll provenance in calibration diagnostics** — Keep traced adjustment and amplification totals relative to the original Android event delta after applying a validated physical scale, without changing the trace schema or motion behavior.
- **Stabilize moving censor sizes and connected body groups** — Keep moving visual masks at settled dimensions through small detector fluctuations and merge connected body fragments despite contradictory duplicate face labels.
- **Admit scroll-learning episodes without touch interception** — Allow ordinary scroll streams to form separated learning episodes without requiring touch-exploration events, while retaining trusted producer and independent geometry validation gates.
- **Preserve quality coverage through unrelated fast-model misses** — Keep confirmed quality-only censors stable between quality scans, and retire missing regions only from newer observations that actually cover them.
- **Preserve person refinements across normal publication cadence** — Eliminate a reproduced 41 ms reuse-expiry gap without extending the existing maximum source age.
- **Bound provisional person masks and suppress duplicate decorations** — Limit temporary head/torso estimates to observed body evidence instead of inventing eight head-heights below cropped portraits.
- **Coalesce duplicate Chromium scroll-companion movement** — Avoid applying an identical trusted absolute/virtual explicit handoff twice while retaining independent producer baselines and all ordinary scroll displacement.
- **Prevent reverse overlay correction during same-direction slowdown** — Hold a bounded display-only overshoot while fresh scroll input continues in the same direction, then converge to authority when input expires.
- **Retain current-viewport quality across compatible fast frames** — Prevent stationary quality masks disappearing after one fast handoff while preserving source-age expiry and scene invalidation.
- **Wait for all dependency checks**
- **Wait for all dependency checks**
- **Quote dependency schedule times**
- **Quote dependency schedule times**
- **Use portable dependency cooldown options**
- **Use portable dependency cooldown options**
- **Normalize GitHub repository name casing**
- **Normalize GitHub repository name casing**
- **Replace scroll learning with source-clocked native X motion** — Remove persistent scroll calibration and reconstruct native X RecyclerView motion from fixed Android spline physics and actual event timestamps, preserving camera authority and callback continuity.
- **Batch background cashouts above the internal minimum** — Require 15.00 in the existing EUR currency before reserving a new background automatic settlement.
- **Group achievement tiers coherently and keep Legend last** — Display related achievements together in stable easier-to-harder progression, with the completion milestone after every current and future feature group.
- **Point the updater at the moved GitHub repository** — Read published updates directly from confiteor48/SubHub instead of relying on the former repository redirect.
- **Give detection level cards room to breathe** — Space the three detection cards apart, move icons above their labels, and simplify the Low and High descriptions.
- **Align paid-pause configuration with Wallet page margins** — Keep the paid-pause card inset and aligned with its peers after moving it into the Wallet page flow.
- **Preview Popup Storm without enabling service participation** — Remove the redundant participation switch and status from Storm configuration, and let its bounded preview run while the service setting stays off.
- **Complete Pack Maker toggles and speed touch-local press feedback** — Use shared On/Off controls throughout Pack Maker and show quick toggle press feedback at the actual touch point instead of the hidden-thumb hotspot.
- **Simplify body-area choices and collapsed Appearance** — Show Vagina, Dick & Balls, Tits and one combined Ass choice, rename Abdomen to Abs / Tummy, remove the four specified help texts and hide the selected style when Appearance is collapsed.
- **Clarify detection tiers and theme enabled controls** — Show detailed male/female coverage in portrait phone icons and replace bright enabled-control accents with a consistent muted plum palette.
- **Align settings controls and trim redundant summaries** — Give detection cards equal row heights with aligned content, center assignment checkboxes under their labels, rename Dressed matches and remove redundant Censor help and Stats achievement-count cards.
- **Make Censor appearance previews phone-oriented** — Show all eight Censor styles inside coherent portrait phone previews, shorten detection subtexts and hide image management when the selected effect does not use replacement images.
- **Clarify coverage icons and populate Limits capture fixtures** — Illustrate intimate coverage in Low, feet in Medium, and precise face-only masks with visible hair in High, while retaining its small armpit regions.
- **Bind release notes to equivalent normalized source history** — Keep stable changelogs and CI working after source-history normalization without moving published tags or admitting untyped new commits.
- **Render release notes with aligned bullets and bold headings** — The in-app changelog uses real bullet points, hanging indents for wrapped changes, and bold section headings and change titles instead of exposing Markdown markers.
- **Render release notes with aligned bullets and bold headings** — The in-app changelog uses real bullet points, hanging indents for wrapped changes, and bold section headings and change titles instead of exposing Markdown markers.
- **Render release notes with aligned bullets and bold headings** — The in-app changelog uses real bullet points, hanging indents for wrapped changes, and bold section headings and change titles instead of exposing Markdown markers.
- **Include settlement bills in PayPal payment descriptions** — New PayPal requests describe every settlement cause, count and charged amount; manual checkout uses grouped cause rows and automatic saved-wallet payments keep one aggregate row with the complete bill.
- **Include settlement bills in dev PayPal payments** — New PayPal requests describe settlement causes, counts and actual EUR/USD charges, with grouped manual rows and one complete aggregate bill for saved-wallet payments.
- **Allow removing either Keyholder method and simplify setup** — Allow both methods to be removed without extra confirmation, preserve the remaining credential when one is removed, and keep Rituals in Dom navigation only.
- **Allow screenshots throughout SubHub** — Remove screenshot-blocking window flags from Keyholder, PIN setup, Studio passphrase dialogs and privacy mode while retaining separate Android recent-preview controls.
- **Separate daily statistics, totals and session history** — Replace the duplicated statistics page with Activity, Totals and Sessions views, truthful daily-history boundaries, available-record charts and a five-entry session pager; attach the Home arrangement to the service widget and place…
- **Remove Whispers from the core Features card** — Keep the Settings Features card and enabled count limited to Censor, App limits and Wallet, while preserving the independent Whispers setting when saving them.
- **Backfill dated saved sessions into daily statistics** — Saved sessions now populate historical daily time and feature counters once, without duplicating current daily tracking.
- **Keep Statistics on one compact page** — Statistics now shows activity, concise lifetime totals and recent sessions together, with details expanding in place instead of three separate views.
- **Share Studio packs with the .sub extension** — Studio now exports .sub files with consistent ZIP content types while retaining existing libraries, drafts and .subhubpack imports.
- **Keep setup focused and return from its app picker** — Setup now uses phone previews, offers Censor, Limits and Wallet without Whispers or redundant notices, and returns from app selection to the same draft.
- **Select service durations inline on Home and Settings** — Service Duration now shows fixed choices, Random beside Permanent and a compact Hide countdown checkbox directly in the shared widget, including when no service lock is active.
- **Theme duration controls and refine the setup preview** — Permanent service hides the countdown option, Random uses themed numeric fields, and the setup phone preview shows a stick woman with accurately placed censor regions.
- **Keep OCR callbacks and input alive through shutdown** — OCR completion no longer depends on a service executor that may already be stopped, and recognizer retirement waits for pending SDK tasks.
- **Allow Android permission settings from Sub mode** — Accessibility, overlay, notification and battery settings are now accessible from Sub mode without granting Dom access.
- **Require Dom to leave service and stop timed sessions cleanly** — Leaving service requires Dom mode for both timed and permanent sessions, and releasing a timer no longer starts a permanent session.
- **Keep completed censors until a newer detection is ready** — Capturing or queueing another screenshot no longer cancels a useful in-flight detector or revokes the last completed scene; structural and non-reprojectable motion fences remain intact.
- **Submit Dom credentials with the keyboard Enter action** — PIN setup, PIN changes, Dom unlock and authenticator pairing submit through their existing validation action when Enter is pressed.
- **Unify configuration form styles and responsive layouts** — Configuration pages and Pack dialogs share typography, spacing and control states, with aligned paired fields and readable choices on narrow screens.
- **Keep form focus intentional and theme Pack dropdowns** — Ordinary forms wait for a tap before editing, while Dom unlock and setup retain automatic PIN focus and keyboard access.
- **Remove app lists from Home assignment details** — Home assignment popups now show feature configuration without listing assigned apps or per-app allowances.
- **Clean up assignment sheets and section navigation** — Artwork captions fit their allotted space, assignment values consistently stack below left-aligned labels, and Censor uses a square saved-style sample.
- **Fit setup artwork and normalize arrangement controls** — Setup artwork has room for its image, caption and bottom phone bar.
- **Keep Censor Lab recording isolated from normal service effects** — Censor Lab temporarily runs censoring for selected apps without arming the normal service or charging Limits, Wallet, taps, dwell, or popup effects; ownership ends on stop and failure.
- **Compare censor identities in the captured scroll phase** — Project detached tracker snapshots into each captured frame before fast and quality identity matching, preventing known scroll displacement from creating duplicate identities without moving the live tracker early.
- **Keep the daily Limits input fully inside its row** — The Daily minutes pill now measures its natural height with no inherited top margin or baseline offset, so its lower outline remains visible at enlarged text sizes.
- **Attribute censor taps to displayed masks** — Censor tap Tribute now follows displayed text, padding, whole-person coverage and admitted cached masks rather than raw detector boxes.
- **Render Lockpad consistently in live censor overlays** — Render Lockpad in the live censor overlay with the same opaque artwork used by presets and exports.
- **Preserve cached Glitch texture and hide collapsed presets** — Cached Glitch masks retain opaque noise instead of a flat cyan box, and the Presets button is visible only while Appearance is expanded.
- **Preserve supported censor geometry and fence motion conflicts** — The replacement Accessibility engine retains supported header/body geometry during partial image updates and rejects contradictory measured routes before they can preserve invalid coverage; live-service activation remains a separate gate.
- **Use the SubHub icon for update downloads** — Update downloads show a branded SubHub notification with quiet progress and retain Android download handling in the background.
- **Serialize replacement visual jobs without discarding admitted quality** — Replacement censoring no longer grants visual inference overlap from provider labels or cancels admitted quality work on capture refresh.
- **Preserve completed native confirmation across partial reads** — Keep native text detection accounting progressing through successful partial node renewals without granting partial reads completed credit.
- **Keep scroll tracking steady after delayed callbacks** — Preserve existing scroll tracking while preventing stale callbacks from restarting censor movement.
- **Reuse working scroll tracking with replacement detection** — Keep replacement detection while moving image censors between scroll observations with the established scroll trajectory.
- **Keep fresh censor sources from jumping during scroll** — Prevent fresh replacement-engine detections from inheriting scroll forecast movement that predates their captured source.
- **Preserve censor paints across fresh capture preparation** — Prevent unrelated fresh screenshot metadata from clearing a mask already being drawn.
- **Select replacement scroll curves per validated owner** — Keep a child callback label or another scroll owner from rephasing an existing replacement censor trajectory.
- **Prevent lagging scroll correction from overshooting its forecast** — Prevent a lagging same-direction scroll correction from briefly moving backwards after catching its forecast.
- **Brake exact-coordinate scroll corrections within forecast allowance** — Keep an existing censor moving forward when fresh forward scrolling lands exactly on its displayed coordinate, preserving the incoming velocity and the existing forecast allowance and return schedule.
- **Keep scrolling frame requests independent of UI acknowledgements** — Scrolling censor frames no longer wait for the previous frame's UI acknowledgement before requesting the next display callback.
- **Quarantine uncertain QUALITY initialization errors** — Keep uncertain optional-model startup failures owned instead of leaving QUALITY readiness stuck without a resource-failure signal.
- **Restore preferred-frame timing for scroll masks** — Restore display-frame timing for scroll masks while retaining actual source and accounting clocks.
- **Restore preset Accessibility cadence in replacement mode** — Replacement mode now applies the existing preset-dependent Accessibility service policy at startup and after live settings changes, matching the established legacy behavior.
- **Complete person profiles and restore Dom session controls** — Give Dom direct session controls on each device profile and Add Sub on People, while Sub keeps a read-only session view with its allowed start, payment and pause actions.
- **Reconcile role profiles with the newer Censor controls** — Keep person-scoped Contract navigation while retaining current pack covers, security details, and per-app allowances from the Censor branch.
- **Include implemented merge changes in release notes** — Release notes and updater manifests now include typed merge commits so features implemented during integration are visible to users.
- **Remove peer monitoring from the test candidate** — Disable peer activity, usage, statistics and live-state collection and transfer while retaining paired configuration and app choices.
- **Include the local Sub in Home and Activity** — Count the on-device Sub and show its existing Activity alongside eligible remote events.
- **Show the name for the active workspace only** — Settings shows Sub name in Sub mode and Dom name in Dom mode, preserving both saved names when switching roles.
- **Preserve scrolling and controls during overview refresh** — Home updates its changed Tasks, usage, relationship and people sections without replacing unrelated controls.
- **Group task instructions and show app icons across role UI** — Task descriptions and their inline image, audio or video share one card, and app entries consistently show device-local application icons with accessible fallbacks for unknown packages.
- **Retain combined features in launcher startup update** — Apply launcher shortcut registration on the complete UI, P2P, Tasks, storage and achievement baseline so development updates retain all accepted features.
- **Simplify task media and role navigation** — Task attachments now appear directly below their description without an extra heading, Dom Activity lives inside Subs, and Sub navigation places Contract before Tasks.
- **Place review verdicts on submissions and refine Activity and Stats** — Show each completion verdict on its matching submission, use the saved Sub name in local Activity, and omit the large unknown period total from Stats while retaining recorded history.
- **Keep controller lessons in Dom onboarding and pair Both once** — Setup now keeps Wallet and Censor lessons in Dom after Add a Sub, removes the Dom Hardcore lesson and teaches pairing once in Both.
- **Restore primary header icons and consistent content spacing** — Main pages now retain their icons and use a consistent 12dp gap before content, including Activity and Achievements.
- **Show the contract owner's profile and compact allowance controls** — The Sub contract now shows its Dom's authorized profile, and both allowance views use a compact adaptive layout with clear On/Off states.
- **Bound user text and imported media without truncating saved credentials** — Apply field-specific text ceilings and source-byte limits to user inputs, preserving existing saved credentials through form recreation and rejecting oversized imports before replacing them.
- **Unify Subs cards and profile detail placement** — Show the same profile identity across Subs cards, full profiles and Contract previews, with role and optional age or gender directly below the name.
- **Unify headers, spacing and role overview controls** — Restore the galaxy background and page icons with a shared header, centered Settings button and four bottom destinations.
- **Preserve padding around profile feature choices** — Keep Allowed features inset within the Edit profile card at normal and large text sizes.
- **Share Switch profiles and enable Sub maintenance** — Paired profiles show Switch when their owner uses Both, Subs can change language, repair Android permissions and configure update preferences without a Dom unlock, and Dom Overview ends with the same Help & safety card as Sub Overview.
- **Let Subs hide diagnostics during active sessions** — Subs can turn the diagnostics overlay off during an active session without ending protection, and delayed capture frames cannot show it again after it is disabled.
- **Order configuration consent before remote authority** — Remote configuration application waits for the Sub consent lock before taking the shared store, allowing the existing Tasks path to use the same authority store without the opposing lock order.
- **Keep overlay redraws within their original tap scope** — Prevent retained overlay redraws from replacing a newer publisher's tap map or assigning collected geometry to a later scope.
- **Integrate capture ownership with runtime tap revocation** — Bind capture shutdown and frame effects to their original owner, preserve replacement session state, and revoke retained tap geometry when native runtime ownership changes.
- **Drain retiring captures after queued Android starts** — Close an already retiring capture when Android delivers a start that was registered before its earlier Stop could finish.
- **Observe native boot clocks before authority callbacks** — Seed the process-stable boot counter at ordinary feature startup so native expiry code can read a pure cached clock under runtime locks.
- **Integrate UUID-bound session expiry transitions** — Expiry checks now commit only the current due pact or paid pause and retire generation-specific immutable alarm tokens.
- **Integrate durable session Stop bookkeeping** — Bind Stop cleanup to its recorded statistics, pact, pause and capture identities so interrupted work can recover without changing a replacement session.
- **Keep session broadcasts alive through queued work** — Retain boot and expiry broadcasts while queued session effects and timer installation finish, so a receiver-only process can restore its scoped alarms after reboot.
- **Retain the actual Stop time during statistics recovery** — Recover interrupted session accounting with the original native Stop timestamp and fixed duration so delayed recovery cannot add time to a locally started session.
- **Keep Settings responsive during keyholder checks** — Settings reads keyholder availability asynchronously so credential decryption cannot block opening or resuming the screen.
- **Update training Pillow and ONNX security pins** — The separate PERSON training environment uses patched Pillow 13.0.0 and ONNX 1.22.0 while retaining the existing training and export contract.
- **Keep local Dom access available on Both-role devices** — Both-role devices can open their own Dom workspace with their existing local PIN while an assigned remote Dom and the protected Sub session remain in place.
- **Diagnose underlying peer recovery failures safely** — Peer-sync diagnostics identify underlying recovery failure types and application code locations without logging private peer or credential data.
- **Keep Dom Overview stable during relationship failures** — The Dom Overview keeps the journal, Activity and Achievements cards visible when relationship loading fails or retries.
- **Recover paired profiles after interrupted relationship reads** — Paired profiles recover after canceled relationship reads, retain independently valid saved data and show each counterpart's last online time.
- **Scope Dom views to approved relationships** — Restrict Dom activity, tasks and statistics to approved relationships and resolve assigned Dom profiles by exact peer generation.
- **Show concise inline Last seen timestamps** — Show inline Last seen timestamps as dd.MM.yyyy HH:mm:ss and remove role labels from profile previews.
- **Restore direct Add Sub invitations after expired attempts** — Open Add Sub directly with a usable invitation and recover expired or unavailable pairing attempts.
- **Shorten last-seen timestamps for readable profiles** — Show day and month with minutes for this year and include the year for older observations, preserving the Overview layout.
- **Open Add Sub directly on a recoverable pairing code** — Resume valid invitations, retire unusable pending attempts automatically, and return Cancel directly to Subs while preserving active pairs.
- **Restore session time controls beside Start and Stop** — Restore the established duration and Start/Stop presentation while retaining current session behavior.
- **Show journal sharing for the current audience** — Distinguish entries shared with an earlier audience and offer explicit sharing with the current recipient without automatically exposing old entries.
- **Wake peer sync automatically and show role-correct status** — Replace the Sync button with automatic foreground and network-return attempts, retaining scheduled background work and showing the streams relevant to each role.
- **Preserve Subs scrolling during live updates** — Update changed roster cards in place so automatic refreshes preserve scrolling, stable cards, and current relationship access.
- **Show connection details together in one card** — Keep one peer ID, status and last-seen time together with always-visible sync details, removing the expandable panel and generic action error note.
- **Respect Sub feature choices while Hardcore is enabled** — Keep disabled features inactive and reject queued changes regardless of Hardcore, removing the override profile notes and correcting related help text.
- **Allow Subs to change local privacy controls** — Make Discreet mode and App lock accessible in Sub mode while retaining Android authentication for enabling App lock.
- **Remove redundant read-only labels from Contract** — Show the contract without read-only helper text while preserving protected editing permissions.
- **Align the Your Dom heading with Session** — Remove the extra top inset above Your Dom and align Open Contract text and chevron while preserving its 48dp touch target.
- **Clarify Dom duration selection in Hardcore consent** — Clarify that the Dom chooses the session duration and remove redundant wording from the remote-start consent.
- **Idle censor workers after untracked-app inactivity** — Release inactive model sessions after three minutes on High and immediately on Medium or Battery Saver, while preserving active protection and selected coverage.
- **Count Limits only after confirmed foreground usage** — Limits now count a selected app only while it is visibly focused on an unlocked screen for at least five continuous seconds, preventing brief overlays and stale foreground state from spending allowance.
- **Keep Stop available while configuration assets download** — Stop commands remain available while required configuration assets are incomplete, while a fresh Start keeps its original pending receipt until the configuration is ready.
- **Show peer presence without stale online history** — Show Online alone while a peer is present, and Offline with the last-seen time when contact history is known.
- **Refresh relationship and session views automatically** — Recover relationship and remote session views through passive observation without manual Refresh buttons, while retaining explicit consent, review and session commands.
- **Return repeated navigation taps to each section start** — Reselecting the active navigation tab closes its subpages and returns the section to the top while preserving workspace, saved data and explicit edit controls.
- **Keep Tasks headers stable and refresh task data automatically** — Task details hide Settings and retain one read-only Sub indicator across Back and recreation.
- **Sort task History and show completion review details** — Finished tasks appear newest first by their first recorded History entry, and previews show Approved or Declined with the original completion-request time in the task timezone.
- **Allow Whisper opacity up to 35 percent** — Advanced Whisper opacity can be selected up to 35 percent in settings and packs while existing selections and preset defaults remain unchanged.
- **Suspend Censor and usage while the screen is unavailable** — Censor, Whispers and active-use accounting pause while the screen is off or locked, then resume from a fresh eligible foreground observation without counting the suspended interval.
- **Show Online only for verified foreground app presence** — Profiles now distinguish verified foreground presence and last-online time from background transport contact, with ordered observations and bounded expiry.
- **Refresh roster presence while the people page is open** — Refresh roster badges when verified foreground presence changes or expires, while preserving relationship approvals, pending operations and scroll position.
- **Preserve last seen before the first sync checkpoint** — Offline profiles retain their verified foreground last-seen timestamp even when the peer has not completed its first sync checkpoint.
- **Admit the hosted emulator and extra-time settings checks** — Keep extra-time request defaults covered by the configuration catalogue regression and prepare the observed Android emulator PulseAudio dependency before running native verification.
- **Preserve and inspect paired native verification APKs** — Read the installed APK signer output with strict certificate checks and preserve the built debug APK pair before metadata inspection can fail.
- **Remove focus-catcher tint from app-time requests** — App-time request and review cards retain their normal colours after typing or recreating the page.
- **Preserve negotiated extra-time support and clarify unavailable requests** — Keep last negotiated app-time compatibility through incomplete handshakes and make unavailable request controls explain why they cannot submit.
- **Restore remote app icons and group extra-time receipts** — Show scoped remote app artwork in time reviews, including retained requests whose apps are absent from current catalog/usage, and refresh arrival through the existing authority-checked provider.
- **Combine favorite Sub session and event details** — Show one Session Active or Session Inactive · N events line on the Dom favorite profile card, while retaining explicit unknown and stale states and removing its protected-duration subtext.
- **Derive session elapsed time without background polling** — Count unchanged logical sessions across screen-off and process gaps by reconciling persisted start and stop facts when statistics are opened or the session changes.
- **Retain page content and scroll across background refreshes** — Keep the Dom Sub profile, session draft and activity preview stable during telemetry refreshes, and restore replaced page content after layout without overriding newer navigation.
- **Show one state-aware remote session action** — Restore a single themed Start or Stop control while retaining existing authority, review, applied-duration and receipt gates.
- **Preserve extra-time scroll after retained-content refresh** — Restore the latest pending position after focus restoration and Android measurement, so background receipt/icon updates cannot jump the page or override a newer navigation gesture.
- **Order app-time lists by remaining allowance percentage** — Put the lowest remaining fraction first across Home, profiles, statistics, Extra time and local/remote Limits editors.
- **Keep allowance percentage ordering consistent across screens** — Rank individual app allowances by their own effective budget everywhere, with the shared allowance remaining a separate budget.
- **Refresh retained duration choices without overwriting newer settings** — Refresh the local profile duration picker from current preferences on resume and refresh while retaining incomplete range input.
- **Keep remote app artwork in shared native editors** — Bind the shared Limits editor and app selector to their selected peer instead of the Dom device package catalog.
- **Avoid duplicate local names in allowance telemetry** — Keep the applied-credit observation unchanged while distinguishing its daily minutes from the existing daily statistics store, allowing Android Java compilation to proceed.
- **Observe API35 zero-state notification permissions** — Admit complete fresh-install permission dumps while preserving exact grant/flag restoration and failing closed on unknown, malformed, truncated, or ambiguous state.
- **Remove explanatory session statistics subtext** — Remove the explanatory paragraphs below current-session statistics on Sub and the corresponding remote view on Dom while retaining elapsed values, observation timestamps, and existing sync freshness indicators.
- **Retry pending synchronization on a stable connection** — Resume saved changes automatically after the default network has remained validated and usable for two seconds, clearing stale offline reconnect delays without replacing durable commands or bypassing consent and terminal results.
- **Keep Completed on Tasks and mirror app-time meters correctly** — Keep accepted-only Completed counts on the Tasks page while restoring the overview's original Active, Pending and Scheduled counters, and render app-time meters from the correct side in RTL layouts.
- **Preview Dummy Sub through real native screens** — Dummy Sub now opens the normal read-only profile and native contract settings with an isolated synthetic configuration, while preserving the five-second Back toggle.
- **Preserve the Wallet restoration guard in preview** — Keep Wallet preview read-only through the existing lifecycle override while preserving normal saved-rule restoration.
- **Unify activity and progress bars with a muted plum theme** — Activity trends, app-time allowances, limits, achievements, export and download progress now share a quieter plum fill and rounded styling.
- **Update live achievement progress and Dom milestones** — Advance streaks inside continuing sessions, show elapsed timed-lock progress, and refresh the open catalog and details.
- **Retain timed-lock progress when service ends** — Keep completed timed-lock progress in the same transaction that clears service, so the seven-day badge remains earnable after natural expiry or Stop.
- **Keep live streak dates in the recorded session zone** — Use the same recorded civil timezone for session-day projection and streak recency, preventing westward travel from clearing an ongoing streak.
- **Keep Activity history visible during updates and add mark all read** — Activity keeps its retained page and current scroll anchor while refreshed facts arrive, avoids rebuilding unchanged rows, and exposes a scoped mark-all-read action for collapsed and older receipts.
- **Retain authorized achievement content during refresh** — Keep previously rendered achievements and local statistics during same-authority refreshes, show truthful unknown states before first data, and discard cached presentation when role, authority or membership cannot be revalidated.
- **Fence retained Activity deliveries to the captured authority** — Ignore pending results when the workspace or relationship authority changes before delivery, and fetch fresh facts for the current context.
- **Preserve streak calendars and revalidate resumed Dom catalogs** — Keep stopped streaks in their recorded session calendar and withhold retained Dom achievement content until membership has been revalidated after a pause.
- **Revalidate Activity authority and enter the bulk-read continuity gate** — Activity clears revoked or unverified private facts, validates retained pages before resume, and marks the eligible receipt snapshot read under the existing continuity gate.
- **Keep retained streak composition on one calendar** — Prevent retained session history from reviving an expired streak after travel by evaluating current and recovered progress against the same captured civil date.
- **Revalidate queued Journal reads and save stored allowance edits** — Journal polling now checks authority before coalescing pending reads and cancels private reads and playback on access loss.
- **Bind allowance metadata readiness to the displayed app scope** — Restore the selected allowance rows before reusing any prior pending, failed or successful metadata observation.
- **Recover current streaks only from dated session evidence** — Prevent eastward travel and unzoned activity history from reviving expired streaks while keeping historical activity dates and earned badge eligibility intact.
- **Preserve streak provenance when a session changes timezone** — Keep expired streaks from returning when a later session starts in a different timezone, without revoking earned badges.
- **Preserve Android locale metadata in the isolated owner journal** — Allow the exact Android-maintained locale table while preserving strict owner schema, identity and corruption checks.
- **Keep historical streak awards in a coherent calendar** — Prevent mixed travel dates and persisted session copies from manufacturing a historical streak.
- **Preserve censor calendar provenance for streak backfill** — Prevent modern device-day censor copies from acquiring legacy streak authority after travel.
- **Preserve responsive runtime updates on Android 8** — Apply saved runtime settings through separate module and persistence executors, retaining exact-owner permits until actual module return and durable acknowledgment.
- **Integrate reviewed Activity refresh authority checks** — Keep Activity rows stable during refresh while revalidating peer membership and bulk-read authority before applying changes.
- **Bind captured DEVICE statistics inside exact startup authority** — Reuse the acknowledged physical statistics repository instead of reconstructing one after startup, and reject a stopped or foreign source before capture delivery.
- **Preserve Popup activation binding compatibility**
- **Keep DEVICE rearm refusal compatible with Android 26**
- **Request extra app time from Overview** — Subs can request more time from an expanded App time row and see immediate progress, pending status or an explanation when requests are unavailable.
- **Preserve owned capture retirement and native qualification** — Retain the inactive Demo Sub runtime while fixing retired frame callbacks, cleanup ordering, and access to the exact Accessibility qualification witnesses.
- **Open extra-time notifications in Overview** — New Sub notifications open the expanded app-time row directly, and notifications issued before the update still reach the same Overview.
- **Observe screen and power changes for owned Demo sessions** — Tie the screen, lock and power observations to the exact internal Accessibility session and retire their registration through its shutdown receipt.
- **Preserve Overview and Demo roster behavior on development** — Reconcile the internal roster generation guard with the Overview extra-time request revision while retaining the development CI workflow and selector coverage.
- **Keep projection rejection local to the selected shell** — An owned token cannot stop an incumbent DEVICE capture, a rejected owned shell cannot fall back to DEVICE, and failed owned projection preparation still attempts cleanup for each returned resource.
- **Fence display capture when its source disconnects** — Stop a disconnected Accessibility source from dispatching, publishing, or lending a screenshot while preserving actual returns for previously admitted resources.
- **Count legacy session timer work before runtime handover** — Keep boot, pact expiry, and paid-pause restore from starting during a runtime handover, and retain queued timer deliveries until their work returns.
- **Wait for Demo accounting before attaching capture** — Start attaches capture after the owner data acknowledges the Demo session and after rechecking the same bound Accessibility service.
- **Complete Demo Sub capture and repeatable session handoff** — Demo Sub starts with its own settings and capture session, then restores ordinary Accessibility after End so the next Demo session can start cleanly.
- **Correct Dom achievement roles and Wallet grouping** — Move the five text-filter and appearance milestones from Sub to Dom while preserving existing achievement IDs, requirements, artwork and earned receipts, and group paid tributes with Wallet Bound.
- **Show peer presence on authenticated app updates** — New contract and app data update Online as soon as they are accepted, and ordinary sync carries foreground changes while Last seen survives expiry.
- **Refresh the open Contract on peer changes** — The Contract screen refreshes its peer status as presence changes and shows applied contract updates while the screen stays open.

### Faster & smoother

- **Render censor tracks at display cadence** — Gate borderline detections until a second consistent observation while preserving immediate coverage for high-confidence hits.
- **Anchor text and interpolate scroll motion** — Represent detector observations with source, geometry-quality, and semantic-anchor metadata.
- **Decouple capture and eliminate hot-path churn** — Run MediaProjection capture independently from inference through a latest-only broker, pool ordinary capture bitmaps, decode ONNX output from its contiguous tensor buffer, and write model input directly into reusable native storage.
- **Hint inference and govern sustained capture** — Give Android 12+ an ADPF latency target for the inference worker and report actual work duration.
- **Reuse retained effect frames** — Return MediaProjection source frames to the bounded capture pool when the overlay replaces or closes them.
- **Restore low-latency Accessibility censoring** — Publish qualified visual detections on their first observation instead of waiting another rate-limited screenshot.
- **Make Accessibility text authoritative** — Use exact Accessibility text nodes before OCR, move image OCR behind primary inference, throttle it to settled confirmation and steady cadences, downscale it, invalidate it on motion or native text, and expire stale results.
- **Split live censoring from settled refinement** — Move Accessibility text onto a direct render lane, follow confirmed touch scrolling at display cadence, and remove reverse-prone viewport coasting.
- **Reconcile scroll motion and detector authority** — Filter isolated wrong-way Accessibility deltas with reversal hysteresis, bound pathological producer jumps, and interpolate accepted motion to exact coordinates without overshoot or retained drift.
- **Remove competing motion and parallelize model input** — Ultra now follows Accessibility scroll events as its sole motion authority, accepts initial movement immediately without replaying held deltas, and avoids delayed double-sized scroll jumps.
- **Cancel stale Accessibility text scans** — Abort obsolete Accessibility-tree traversal as soon as a new scroll generation starts.
- **Prefetch and atomically publish Accessibility text** — Use Android 13+ breadth-first node prefetching to avoid cold per-node Accessibility IPC fan-out.
- **Defer text traversal until scrolling settles** — Gate Accessibility-tree scanning on the same quiet-motion interval used for post-scroll reconciliation.
- **Make Accessibility refinement converge** — Move quality inference out of the post-scroll critical path and make it a cache-only producer so fast and quality geometry no longer compete on still frames.
- **Stabilize refinement and rapid reversals** — Accessibility censoring now keeps fast geometry authoritative, preserves anchored text through scroll reprojection, and follows deliberate rapid reversal bursts without accumulating one-direction drift.
- **Reconcile text as a persistent scene** — Keep confirmed Accessibility text anchors across scrolling, reconcile surviving and removed anchors on the first settled scan, and confirm only new candidates through targeted node refreshes.
- **Sustain fast text and scroll tracking** — Keep the real-time detector responsive by making quality refinement yield to pending fast frames, using mobile-aware ONNX provider threading, and restoring source frames whenever optional refinement is skipped.
- **Restore realtime text tracking headroom** — Skip exact Accessibility character geometry for text the classifier has already rejected, bound stale text from the first invalidation without clearing on every scroll event, and give the realtime ONNX session a latency-focused worker…
- **Eliminate systematic Accessibility scroll lag** — Apply the resolver's complete authoritative scroll displacement instead of clipping every event to half a viewport, and converge the compositor transform within one frame.
- **Continuously steer Accessibility censors** — Advance the viewport between sparse Accessibility events with a finite drift-free lead, and preserve per-track visual and text trajectories across detector publications so corrections bend an existing path instead of replacing snapshots.
- **Reconcile fast and quality censor identities** — Accessibility scrolling now invalidates stale refinement caches, preserves cross-pass track lineage, and prevents coarse quality observations from spawning parallel censors beside their realtime identity.
- **Publish settled quality coverage atomically** — Hold unmatched quality-only detections until their settled confirmation completes, then expose the completed scene in one tracker update instead of multiple late censor waves.
- **Preserve authoritative Accessibility scroll timing** — Let explicit and absolute Accessibility deltas bypass fallback anti-bounce heuristics, and stop low-confidence reversal filtering from folding withheld distance into a later doubled jump.
- **Phase-lock Accessibility viewport motion** — Render between sparse Accessibility scroll events along a bounded, reversible trajectory while keeping authoritative event positions exact.
- **Eliminate first-frame and settled refinement lag** — Present authoritative Accessibility viewport deltas at their exact position on the first eligible draw, reject stale vsync timestamps after a newer mutation, and keep bounded prediction only between confirmed events.
- **Preserve continuous censor coverage through scroll** — Carry a bounded presentation residual across authoritative Accessibility events, source detections from the hardware screenshot timestamp, and commit settled detections as one terminal quality batch.
- **Isolate quality inference from capture latency** — Run the 320 px CPU fast lane and 512 px NNAPI quality lane with separate preprocessing workers, retained hardware-buffer ownership, strict motion-generation commits, and no direct late quality render authority.
- **Unify Accessibility censors under one camera** — Render established, newly born, retained, and text coverage in stable content coordinates through one timestamped viewport camera.
- **Commit one censor scene per display tick** — Run fast and quality inference as private same-capture observations, close each scene exactly once, and present only the newest immutable scene on the next device vsync.
- **Bound content-space cache storage** — Keep confirmed scroll regions in fixed primitive storage with a vertical index, strict metadata and entry limits, and access-order eviction so re-entry queries avoid map churn and unbounded retention.
- **Retain confirmed censors across scroll re-entry** — Keep confirmed render-only regions in a bounded content-space cache so previously seen content can re-enter on the next display frame without waiting for another screenshot or inference pass.
- **Build a causal scroll calibration gate** — Record passive touch boundaries, stable surface decisions, numeric scene geometry, and synchronized Censor Lab timing so MediaProjection recordings can teach Accessibility presentation without becoming runtime authority.
- **Remove quality from the fast render path** — Publish settled detections immediately, run portrait fast inference on a recall-gated rectangular tensor, and keep optional NNAPI quality as bounded cache-only evidence.
- **Avoid redundant hardware bitmap scaling transfers** — Read sRGB screenshots into software once before resizing, avoiding the platform hardware scaler upload followed by a second readback.
- **Prepare quality crops with a single readback** — Add a bounded coordinate-based crop preparer that avoids the hardware scaling upload/readback loop for sRGB frames, preserving crop geometry and wide-gamut conversion.
- **Bound anchor baseline reads and classify budget overruns** — Stop discovery after the first over-budget read instead of fetching all remaining nodes.
- **Reuse worker-owned GPU preparation behind a debug opt-in** — Retain one renderer per output size, discard source display lists, reset failed outputs, and queue teardown behind capture work.
- **Warm experimental GPU preparation after first publication** — Keep the first capture on software and queue one worker-owned warm-up only after a scene is published.
- **Bound repeated GPU preparation overruns without one-spike shutdown** — Keep the 48 ms target, disable after three consecutive overruns, and immediately disable after failure or a call exceeding 96 ms.
- **Cache descriptor rows in shadow motion scoring** — Remove repeated row lookups and the four-column inner loop while preserving the exact accumulation order and rejection policy.
- **Abandon noncompetitive row motion scores early** — Reduce experimental shadow-observer arithmetic while preserving the winning shift and conservative ambiguity rejection.
- **Score small row motion offsets first** — Tighten early-abandon bounds sooner for ordinary scroll displacement while still searching every candidate and preserving stationary-first then lowest-shift tie order.
- **Reconstruct sparse scroll motion without recurring position jumps** — Keep established scroll presentation continuous across Accessibility updates, forecast inter-event travel, and brake measured reversals without the recurring 56-pixel correction snap.
- **Remove independent person inference while retaining body geometry** — Keep the original NudeNet detector and category controls without the second model, detached image copy or late person callbacks.
- **Consolidate UI loading and remove redundant refresh work** — UI controls now avoid synchronous selection writes and repeated hidden refresh work, with shared loading tied to each page's lifecycle.
- **Schedule Accessibility captures from one owned deadline** — Capture completion, settled motion and quality release now share a scheduler, avoiding idle polling phases while preserving Android dispatch spacing and callback ownership.
- **Resolve scroll owners off the main thread in FIFO order** — Move blocking Accessibility owner traversal to a bounded worker while preserving every admitted delta, original source clocks, effective app scope and document/window fences; explicit gaps trigger conservative fresh capture.
- **Bound completed-frame presentation before the main queue** — Completed frames now coalesce before main-thread delivery, retain only the newest pending presentation and yield after one selected scene per display callback while preserving capture, motion and inference clocks.
- **Overlap replacement FAST readiness with capture** — The replacement Accessibility host now prepares its mandatory FAST context before captured pixels arrive while keeping model work and disposal on the same serial owner.
- **Publish confirmed native groups before parent completion** — Confirmed native text groups can reach the replacement Accessibility overlay before unrelated groups finish, preserving their own confirmation age and parent resource ownership.
- **Reclassify two admitted text nodes per bounded read** — Share scope acquisition across two admitted native text addresses while preserving per-node freshness, independent expiry and bounded full rediscovery.
- **Run replacement native text at normal worker priority** — Keep native acquisition, confirmation and cleanup on the existing serial owner at normal Android thread priority.
- **Validate projection opacity with native bitmap operations** — Projection copies retain exhaustive opacity validation without a Java per-pixel direct-buffer loop.
- **Compress retained payloads and bound disposable editor caches** — Reduce repeated Activity and peer payload storage while preserving original history, offline queues and proof; reclaim only known idle import and derived editor files.
- **Send compact peer state and keep background sync responsive** — Peer sync now records authenticated contact independently of full completion, avoids retransmitting unchanged runtime state, and prioritizes session controls before historical data.
- **Reuse acknowledged feature sections during configuration sync** — Configuration updates now send hash references for unchanged feature sections when they produce a smaller compressed transfer, reducing duplicate configuration data without adding idle manifests.
- **Reduce idle background sync and repeated offline retries** — Check quiet active background peers every five minutes and back off unavailable peers to fifteen minutes, without delaying healthy new data, foreground/network recovery, pairing or revocation.
- **Preserve stored screen values while metadata refreshes** — Show saved app selections and allowances immediately, enrich metadata in place, and coalesce repeated page reads so ongoing work can finish without loading-copy flashes.
- **Read lightweight roster projections away from the main thread** — Build Dom roster cards from identity, presence and session totals without formatting unused contracts or app allowances.
- **Reuse verified immutable update manifests** — Update checks skip repeated manifest downloads when GitHub reports the same immutable asset digest and contact the current repository directly.

### Improved

- **Simplify Home to compact configured feature counts** — Home shows a slimmer header and count-only arrangement cards independent of service activity, while retaining detail taps, payment actions, and feature visibility.
- **Compact app assignments into independent module rows** — Replace tall app cards with compact named rows and aligned Censor, Limit and Subliminal checkboxes, retaining large accessible targets and saved assignments.
- **Share one included-app list and retain daily allowances** — Every enabled service feature uses one include/exclude list, while Limits keeps distinct app allowances and shows the stricter remaining daily budget.
- **Derive service participation from individual rules** — Limits and Wallet now run from their individual budgets, tribute rules and Paid Pause offer.
- **Keep Censor Lab inside Diagnostics** — Censor Lab is available inside Diagnostics, with its duplicate Settings and Help entries removed.
- **Integrate Censor recovery with the current service UI** — Preserve the latest permission, service-stop, duration, countdown and Help behavior while bringing OCR recovery and capture scheduling onto the same development source.
- **Unify typography and compact controls across the app** — SubHub pages now share dark-plum surfaces, scalable text, consistent action controls and compact spacing, including denser Limits app rows.
- **Center app wording on Dom and Sub** — SubHub now addresses the Dom and Sub directly, with playful kink-themed guidance across setup, Help, Wallet, Censor, Limits, Rituals, Studio and Keyholder.
- **Establish a source-owned censor scene engine** — Add the replacement censor engine's standalone scene owner, with source-local identity, independent quality evidence, bounded retention and immutable presentation snapshots.
- **Own censor capture jobs and resources in one runtime** — Establish the replacement runtime's source-scoped capture, inference and presentation ownership before switching Android adapters, keeping existing capture behavior unchanged in this checkpoint.
- **Bind Android capture and inference to the censor owner** — Add the replacement engine's Android acquisition and source-local detector adapters while keeping the existing production capture behavior unchanged until host and renderer integration qualifies.
- **Add source-owned Accessibility host and presentation expiry** — Prepare the replacement censor runtime to retain each mask's source geometry and pixels through asynchronous inference and expire stale coverage without another capture; the live censor engine remains unchanged.
- **Move snapshot surface painting onto one render worker** — Prepare worker-owned censor rendering with bounded frame demand and post-gated tap coverage; production activation remains pending.
- **Qualify replacement through the production Accessibility service** — Add a private emulator-only qualification route from the production Accessibility service to the replacement owner while keeping normal censor behavior unchanged.
- **Separate detected viewport metadata from paint** — Add source-scoped detected-region availability with withdrawal, expiry and qualified-window guards to the replacement censor runtime.
- **Bound projection row copying and raw retirement** — Prepare the replacement capture boundary to retire raw images without closing a live borrower or treating failed cleanup as success.
- **Separate saved remote contracts from section settings** — Open Saved contracts as a dedicated library while remote feature editors show only the selected settings and artwork.
- **Put session and contract controls in their person flows** — Dom profiles now own session actions and lead to Contract, Stats and Connection.
- **Integrate atomic native session preparations** — Persist each exact native preparation with its applying journal state in one SQLite transaction, so interrupted commands retain their original selection and target.
- **Integrate the typed relationship presentation boundary** — Keep relationship eligibility, operation state and connection availability distinct in immutable presentation data.
- **Group Settings by connection, privacy and app controls** — Settings now separates device role, connections, privacy and safety, notifications, Android access, app preferences, and help so related controls are easy to find.
- **Retain the daily writer pool for supported isolation checks** — Keep the existing single-worker scheduling contract behind an unconfigurable ExecutorService while retaining its private app-owned pool reference.
- **Integrate reviewed screen continuity before internal Sub surfaces** — Carry the exact reviewed PR73 loading and summary-projection changes into the isolated internal Sub candidate before extending shared person, task and editor surfaces.
- **Integrate reviewed achievement history before internal runtime scoping** — Preserve reviewed live progress, role grouping and provenance-aware streak history while preparing separate internal Sub accounting.
- **Separate runtime effect permits from durable lifecycle work** — Keep future internal session callbacks responsive while retaining exact-owner admission, activation acknowledgement and counted shutdown.
- **Combine muted progress meters with internal Sub foundations** — Use the shared muted progress-meter style alongside the reviewed loading and achievement updates.
- **Add isolated capture and session ownership adapters** — Prepare owned Censor configuration, capture, provider cache and exact DEVICE continuation boundaries for the editable internal Sub, retaining real callbacks and resources through shutdown.
- **Preserve owned DEVICE work and renderer retirement boundaries** — Keep native computation concurrent while retaining its exact owner lifetime; separate local DEVICE start-role observation and client renderer retirement from global state and physical drain claims.
- **Compile exact owned capture consumers without activating them** — Connect the concrete DEVICE factory, backend and capture-service sources in an inactive candidate while preserving the published development fixes and opt-in preprovisioned native qualification.
- **Preserve inactive Accessibility extraction for code handoff** — Preserve the reviewed full-policy pipeline extraction and its lifecycle fixture updates without enabling the internal runtime.
- **Carry screenshot source ownership through quality inference** — Keep the existing display and window screenshot algorithms while transferring the exact release action with the retained quality frame.

### Restored

- **Restore original achievement badges and goals** — Restore the original achievement artwork, milestone requirements and progress presentation while retaining the current UI, P2P, Tasks, storage and launcher fixes.

### Documentation

- **Document the SubHub instrumentation runner** — Record the custom ADB runner so device tests preserve each test's intended Dom or Sub state instead of silently resetting it.
- **Define the Google Images censor release gate** — Make the Emiru Google Images test a mandatory physical-Pixel acceptance gate for Accessibility/App Mode.
- **Record Pixel motion evidence and remaining alignment gaps** — Preserve frozen Pass32 and Pass33 artifact identities, scoped telemetry, cumulative video measurements, and user verdicts.
- **Record signed single-readback device candidate** — Record release-variant validation, APK and signing identity, and source-snapshot provenance while explicitly retaining the pending real-device gate.
- **Preserve keyboard-only emulator recovery workflow** — Record the verified hardware-keyboard prerequisite and supported keyboard navigation with Android-only screenshot observation.
- **Record corrected alignment oracle comparison** — Compare identical bounded video samples before and after censor identification repair.
- **Preserve full host motion diagnostic and next action**
- **Record measured catch-up result and remaining early motion gap** — Preserve emulator trace, artifact and test provenance.
- **Record anchor recovery limits and missing capture reference** — Preserve the rejected live experiment, restored-off state, verified source-coordinate defect and Android15 prefetch constraint so later passes do not repeat an unsupported shortcut.
- **Record source-aware candidate and measurement availability gap** — Distinguish tested core contracts, dirty integration artifacts, sparse live references, parser repair and restored experiment-off state.
- **Record anchor rejection audit and next evidence gate**
- **Record bounded anchor discovery and read-cost evidence**
- **Record corrected motion clocks and capture-stage follow-up**
- **Locate capture preparation and publication queue costs**
- **Record GPU reuse gains and rejected cold-start trial**
- **Record warmed GPU trial and contention gate**
- **Record sustained GPU and software latency comparison**
- **Separate repeated GPU latency gains from unresolved alignment**
- **Establish synchronized unreported viewport movement evidence**
- **Confirm absolute-offset gap and outline image-motion residual probe**
- **Record scoped row observer and clean-source shadow gate**
- **Record shadow motion evidence and abnormal emulator timing**
- **Record isolated row observer CPU measurements** — Document emulator-only cost reductions and the outstanding live-motion acceptance gates; keep experimental presentation authority disabled.
- **Require live-service readiness for Android profiling** — Prevent instrumentation-disconnected Accessibility processes from being mistaken for valid censor performance evidence.
- **Record stable queues and failing visible scroll tracking** — Separate three uninstrumented controls from a slower recorded article replay.
- **Require verified recording clocks and matching analysis runtimes** — Prevent nominal-FPS comparisons, empty metadata exports, and mismatched native Python modules from becoming misleading Android motion evidence.
- **Distinguish Accessibility receipt clocks from pixel provenance** — Require source-implementation checks and explicit timing provenance before camera fusion or pixel-age claims; document the request-based legacy latency field.
- **Record repeated GPU preparation latency and startup limits** — The opt-in GPU path reduces steady preparation latency in fresh opposite-order emulator trials while preserving the small detector corpus.
- **Reject spatial cache video trial after capture-gap coverage loss** — Recorded ON coverage fails despite a better matched-motion aggregate.
- **Map remaining candidate dependencies and device validation gate** — Separate the intertwined provider, quality-scheduling and presentation changes from verified safety fixes, and require a controlled physical-device comparison before selecting further runtime behavior.
- **Preserve video timebase when validating frame decode** — Distinguish null-sink timestamp rounding from source-frame corruption, using explicit output timing and independent Winscope count validation.
- **Record manual Pixel quality-delivery latency and user verdict** — The installed Pixel baseline looks cleaner to the user but still trails and delays quality additions; paired logs locate a 472 ms median delivery time including 218.5 ms waiting after quality completion.
- **Record signed development candidate and Pixel update verification** — Record the enabled candidate source, APK and signing fingerprints, successful artifact-only runs, and user-authorized wireless replacement install with original APK backup.
- **Verify matched signed quality-control artifact** — Record the flag-off ARM64 checksum, matching signer and exact-source provenance for the control.
- **Record signed Pixel render-stability installation** — Document verified Pass 91 installation and the remaining live torso-group duplication without claiming device acceptance.
- **Record verified signed body-grouping candidate** — Record the exact source, artifact and APK checksum for the private Pass 92 candidate while retaining Pass 91 on the Pixel for user evaluation.
- **Verify authorized Pixel body-grouping update** — Record replacement installation and matching APK hash after user authorization, with live validation unavailable while the phone is dozing.
- **Verify signed Pixel scroll-trajectory update** — Record exact signed source and installed APK hash for Pass 93, with live motion validation pending because App Mode is currently unarmed.
- **Record verified scroll-learning candidate and device blocker** — Record the private signed candidate provenance and verification, while distinguishing successful builds from the unperformed Pixel installation and real-app acceptance.
- **Record verified wireless Pixel installation of scroll learning** — Confirm replacement installation and exact installed hash without claiming unobserved calibration or user acceptance.
- **Verify signed Pixel grouping and learning candidate** — Record exact source, checksum, and compatible signing identity for the private Pass95 artifact, leaving installation and user acceptance explicitly pending.
- **Verify signed quality-continuity Pixel candidate** — Record the private Pass96 source, APK checksum and compatible signing identity while leaving installation, profiling and user acceptance pending.
- **Record verified Pass96 installation on Pixel** — Record authorized wireless installation, exact installed APK hash and service rebind.
- **Record bounded Pass96 post-install timing evidence** — Report raw-count-verified fast and quality timing distributions from ordinary Pixel usage without claiming controlled improvement or visual acceptance.
- **Isolate X absolute-scroll discontinuity from Lab evidence** — Record the user-rejected X behavior, raw-count-verified Lab telemetry and sequential video analysis.
- **Verify signed X producer-diagnostic candidate** — Record exact Pass98 source, APK checksum and installed-compatible signature.
- **Record verified whole-person candidate installation on Pixel** — Record the private signed build and authorized data-preserving Pixel replacement with matching installed APK checksum.
- **Record dense-frame evidence of rejected person-mask behavior** — Document stationary mask pulsing, transient cross-tile coverage, scroll displacement and overlapping labels from the user Pixel recording.
- **Record verified Pass 104 Pixel installation** — Document the private signed candidate and independent installed APK readback while keeping real-device visual acceptance explicitly open.
- **Record remaining failures in Pass 104 Pixel evaluation** — Document dense real-device evidence of opposite-direction mask motion, stationary coverage changes, overlapping labels, and complete lane timing without claiming acceptance.
- **Record user rejection of Pass 104 visual behavior** — Preserve the user-confirmed failure assessment and prioritize scroll jumps before stationary coverage and duplicate-label repair.
- **Record verified Pass 108 Pixel installation** — Record private signing provenance, compatible certificate, original-model-only asset check and independent installed APK checksum.
- **Record Pass 109 stationary mask gaps and transition leakage** — Document the latest phone Lab review, including complete event/video validation, stationary disappearances, duplicate borders, remaining scroll lag, and fast/quality timing.
- **Guard Android test taps against floating navigation** — Keep emulator density deliberate and verify scrolled tap targets are above the floating navigation bar before injecting input.
- **Record verified private Pass 110 Pixel candidate** — Record exact signed provenance, APK checksum and compatible certificate while explicitly leaving installation and user acceptance pending.
- **Record verified Pass110 installation on Pixel** — Record successful replacement installation, independent installed APK hash verification and Accessibility service restart.
- **Record verified Pixel installation of native scroll candidate** — Document exact signed-source provenance, APK checksum, independent installed readback and Accessibility binding.
- **Bound disposable emulator memory for UI verification** — Run disposable UI emulators with explicit command-line RAM and CPU budgets while preserving saved AVD settings and user data.
- **Refresh UI gallery and add screenshot-based social collages** — Show the current SubHub UI in 42 reviewed emulator screenshots and portable square/portrait feature collages, with editable sources and capture provenance.
- **Remove internal Censor experiment archives** — Remove numbered pass diaries, the historical registry and internal prototype audits from project documentation, while retaining reusable Android testing guides.
- **Reorganize the feature tour and refresh marketing materials** — Put each feature and its screenshots together in a clear product-to-service-to-build reading order, center Tribute beside Censor and Subliminal Messages in both social layouts, and add a five-screen desktop version linked from the README.
- **Port current product guides and verified visual assets** — Document the finished control surfaces, Studio schema, gothic achievement palette, and social assets on master, with refreshed stable-boundary screenshots that omit Whole person and Censor Lab.
- **Clarify custom Home intents in Android UI tests** — Use ACTION_MAIN for ActivityScenario Home launches and explicit Dom fixtures so lifecycle tracking and controller-only screens are tested consistently.
- **Describe the shared app list and revised feature routes** — Document shared inclusion, preserved daily allowances, compact Wallet editors and PayPal setup, Rituals tools and Gallery export behavior.
- **Explain fresh onboarding instrumentation fixtures** — Fresh setup tests now document the runner option that preserves an unkeyed first launch.
- **Cover recreated keyboard focus and spinner taps** — UI verification now checks restored focus after activity recreation and native opening, selection and saving of spinner choices.
- **Include Canvas artwork text in native layout QA** — Require artwork text bounds and native render review so TextView-only checks cannot miss clipped illustration captions.
- **Gate instrumentation tests with runtime SDK checks** — Use Android's test runtime SDK gate so platform-specific instrumentation remains eligible only on supported devices and passes Android lint.
- **Validate Lab counters across recording sessions** — Lab evidence checks now retain process-wide sequence continuity without falsely rejecting later recordings that start above one.
- **Fence native fixture readiness before assertions** — Native Accessibility regression checks now prepare observers, fence hardware frame submissions and retain screenshot failures for diagnosis.
- **Require deadline-ordered native scheduler fixtures** — Native scheduler guidance now distinguishes due capture and display-expiry tasks so insertion order cannot invalidate adapter verification.
- **Describe appearance presets and the Lockpad replacement** — Document where to find built-in and saved censor looks and how live and export editors apply them independently.
- **Point native tap fixtures to the navigation clearance helper** — Native UI fixtures now have an explicit reference to the shared helper that prevents floating navigation from intercepting scrolled controls.
- **Await effect-appropriate native render baselines** — Keep inline-box fixtures from waiting on image resources they never prepare.
- **Preserve renderer token and lease contracts in diagnostics** — Keep native render diagnostics from mistaking signed revision tokens or an open resource owner for invalid state.
- **Require actual received metadata in ImageReader fixtures** — Require producer-bound colour metadata so native fixtures test the intended input rather than a requested reader default.
- **Clarify native capture fixture ownership and coordinates** — Native capture guidance now distinguishes direct shell tokens, component SDK guards and display-buffer coordinates from View-local bounds.
- **Guard preference side effects during Activity restoration** — Keep stored settings authoritative when Android restores checked widget state after screen creation.
- **Clarify raw trace and unknown clock contracts** — Diagnostic verification now specifies artifact-specific bounds, explicit unknown pixel clocks and bidirectional drawing-member reconciliation.
- **Keep native taps clear of floating navigation** — Document the navigation-aware tap helper so scrolling fixtures reach their intended control.
- **Align Help with current navigation** — Update Help to describe the current profile, contract, session, and feature routes in clear language.
- **Guard native media teardown and receipt recovery** — Explain safe Android media teardown and durable receipt recovery checks.
- **Guard native evidence output paths** — Prevent reused native helpers from overwriting earlier failure evidence.
- **Qualify isolated native transport fixtures** — Require context-specific migration readiness and fully qualified eligibility annotation support before native transport tests.
- **Match Home instrumentation launch intents** — Document ACTION_MAIN for MainActivity instrumentation so ActivityScenario observes the lifecycle events of Home's normalized intent.
- **Isolate navigation taps from achievement notices** — Document preparing synthetic achievement state before navigation-only fixtures so a one-time snackbar cannot cover a route target.
- **Clarify native fixture eligibility and worker gates** — Native test helpers use SDK eligibility guards, and invitation recreation fixtures must gate every RemoteFeature session worker before asserting that creation remains pending.
- **Advance retained counters in statistics refresh fixtures** — Document how to create meaningful synthetic progress when historical database facts survive preference changes.
- **Clarify transparent achievement padding checks** — Check badge padding against opaque artwork and native-size renders so faint resampling pixels do not trigger unnecessary redraws.
- **Qualify visible text and composed native capture state** — Native UI checks now distinguish visible text width, settled scrolling, composed capture pixels and authentication-dialog window dismissal.
- **Qualify retained Android permission and dialog fixtures** — Native checks now distinguish retained notification denials and keyboard-driven dialog movement from product failures.
- **Use SDK-declared constant families in Android view code** — Android view changes now check SDK constant families and minimum-version support before release.
- **Clarify native Settings and PIN interaction barriers** — Native UI checks now require a focused fresh frame after returning from Android Settings and proof that a cancelled PIN prompt has closed before tapping the underlying page.
- **Tighten native fixture and capture qualification** — Native fixtures now require explicit fixed-count inventories and complete target bounds for scroll captures.
- **Guard peer completion and native fixture boundaries** — Keep negotiated peer phases before terminal connection drain and make native UI evidence cover complete controls.
- **Clarify native API and profile fixture verification** — Native verification now records the supported tint API, correct Settings route, settled overflow checks, and role-scoped fixture recovery.
- **Require authenticated device-capability test fixtures** — Native fixtures authenticate existing capability transitions and restore their role before cleaning up Journal entries, preserving the Sub authorization guard.
- **Restore typed native preference fixtures safely** — Native fixture teardown preserves profile ages, consent choices, task timestamps and sets instead of casting whole preference stores to one type.
- **Require scoped backend fixtures for native UI** — Native UI fixtures must use an owned endpoint for no-Dom assertions and restore the original live singleton after the test.
- **Translate native dialog witnesses to screen coordinates** — Native dialog pixel and gesture witnesses must translate root-relative clipping bounds through the window's on-screen origin.
- **Isolate consent authority and transport fixture lifetimes** — Require consent UI fixtures to restore the registered authority and preserve the original SQLite receipts.
- **Require fixture cleanup after failed receipt assertions** — Exercise mandatory restoration and owned shutdown when a receipt witness fails, so a successful normal teardown cannot conceal a leaked fixture.
- **Keep empty-draw native fixtures visible** — Retain offscreen geometry when testing an empty overlay draw, because removing its last track intentionally hides the View.
- **Explain compact sync and foreground usage behavior** — Documentation now explains feature-section reuse, local notification choices and the five-second foreground qualification used by Limits.
- **Describe lower-power background synchronization** — Document quiet-background cadence, offline retry backoff, and unchanged foreground, pairing and revocation responsiveness.

### Build & release

- **Add private signed-build artifacts** — Allow manually dispatched release-variant builds to use the established Actions signing key and upload a short-lived private artifact without creating or publishing a GitHub Release.
- **Sign fingerprinted immediate-quality experiment artifacts** — Allow the existing artifact-only signing workflow to build a default-off or enabled immediate-quality candidate, verify the generated flag and bundle exact source provenance.
- **Add compact private Pixel artifacts for development testing** — Upload an additional ARM64-only private bundle with source provenance and its own checksum, avoiding a full multi-ABI download for each Pixel validation pass.
- **Automate tested compatible dependency updates**
- **Automate tested compatible dependency updates**
- **Enable agent-reviewed dependency upgrades**
- **Enable agent-reviewed dependency upgrades**
- **Preserve tested signed dependency reviews**
- **Preserve tested signed dependency reviews**
- **Bound signed APK packaging workers and retain failure diagnostics** — Use the same two-worker bound as the verified local pipeline and keep stack traces for otherwise opaque APK packaging failures.
- **Make private replacement candidates installable in place** — Give manual private replacement-engine candidates the shared CI Android version sequence for in-place signed updates, without publishing them or changing ordinary builds.
- **Restore dependency checks after repository rename** — Use the canonical repository name so the dependency gate can assess tested updates again.
- **Keep Dependabot checks outside signed releases** — Dependabot updates use the read-only Android validation pipeline without attempting a signed development release that cannot access Actions signing secrets.
- **Review complete Gradle wrapper updates** — Allow reviewed Gradle upgrades to include the official wrapper JAR and launchers so a complete Dependabot wrapper migration can pass the dependency gate.
- **Upgrade the Gradle wrapper to 9.8.2** — Upgrade SubHub's build wrapper to Gradle 9.8.2 with official launchers, the verified wrapper JAR and a pinned distribution checksum.
- **Upgrade artifact uploads to version 7** — Use upload-artifact v7 for debug and private signed-build artifacts while retaining zipped artifacts and their existing names, paths and retention.
- **Publish development updates only from the Censor branch** — Automatic development releases now build only the consolidated Censor branch, preventing UI, P2P and dependency branches from publishing competing updates.
- **Restore dependency checks on the development branch** — Run Android CI for the consolidated development branch and its pull requests so dependency updates receive the real test, lint and APK build checks.
- **Publish development releases only from dev** — Development releases now publish only from dev after completed feature branches are reviewed and merged into it.
- **Reuse canonical verification for signed releases** — Run canonical checks once per pull-request head and reuse their exact-source receipt for release publication, retaining release-specific checks.
- **Verify app time requests on a fresh Android guest** — Verify one-time app allowances, submission tribute and the grouped settings with targeted native contracts and composed screenshots on an isolated Android 35 guest.
- **Select a focused 52-method SubHub native suite** — Keep the default 21 ExtraTime/Tribute contracts and add an opt-in subhub-pass suite for the 31 additional single-guest integration contracts.
- **Admit labelled exact native subsets from PR event data** — Allow an explicit native-subset label to select one bounded JSON array of exact known methods from a delimited PR body block.
- **Reconcile stable dependencies for the 0.7 promotion** — Combine the completed development application, including the isolated Dummy Sub preview, with the stable branch's newer build dependencies and verified wrapper.
- **Report bounded native failure locations without runtime values** — Make future native failures diagnosable from the supported CI job log using allowlisted exception categories, protocol state and selected-class source locations.
- **Expose bounded build failure categories without raw traces** — Keep one complete canonical test, lint and universal APK build while streaming future Gradle failure diagnostics through a fixed-label sanitizer.
- **Contain parent Bash notifications without losing build status** — Keep version-dependent parent-shell pipeline notices inside the same stderr boundary as reporter failures, while capturing PIPESTATUS immediately in the current shell.
- **Remove feature-specific native workflows from development** — Stop automatic Extra-time and Dummy preview workflow jobs while retaining their native test inventories and runner for explicitly arranged qualification.
- **Add opt-in native checks on preprovisioned hosts** — Add a manual native suite choice to Android CI while preserving canonical build and exact-source qualification.
- **Report bounded unit Java failure categories** — Show fixed unit-test source and error labels with bounded line numbers for future compiler failures, without exposing raw messages, symbols, source excerpts or checkout paths.
- **Register new runtime unit diagnostic labels** — Keep fixed compiler diagnostics current with the fourteen new ownership and capture regression fixtures.
- **Add optional native checks to Android CI** — Allow an explicitly selected native suite to run after canonical Android CI on a GitHub-hosted runner with the required SDK and KVM already available.
- **Reconcile Demo Sub with current development CI** — Keep the qualified development CI policy and document the exact selectors available on the inactive Demo Sub branch.
- **Register Demo Sub diagnostic source labels** — Add fixed names for the tracked Demo Sub runtime and test files so canonical CI can summarize compilation failures without exposing diagnostics.
- **Report bounded unit test source labels** — Include up to eight allowlisted source labels when Gradle reports failed JVM tests.
- **Reconcile development changes for 0.7.0** — Bring Demo Sub and the latest development changes into the 0.7.0 draft while retaining the stable dependency baseline and manual native test selection.
- **Integrate eligible development changes for 0.7.0** — Bring the reviewed achievement, Wallet, peer, and updater changes through dev.254 into the stable candidate.

### Quality

- **Add deterministic Pixel scroll replay** — Record the canonical Emiru Google Images gesture sequence and add an opt-in ADB replay tool so motion passes can use the same slow scrolls, flings, reversals, and jitter inputs.
- **Match settings navigation structure** — Keep the UI contract aligned with Help and Diagnostics living in Settings instead of Home.
- **Bound video diagnostics and disclose alignment blind spots** — Label the legacy video analyzer as relative-motion evidence rather than absolute target alignment, and allow explicitly bounded sampling so expensive diagnostics cannot silently consume an entire recording.
- **Measure retained accessibility anchor freshness without render authority** — Add an opt-in bounded background diagnostic that separates receipt-order movement from source-time-confirmed freshness and records numeric evidence without feeding the censor pipeline.
- **Separate anchor signal surveys from polling feasibility** — Measure cold and warmed node costs without relaxing the strict budget, allow bounded three-to-five anchor selection, and require the immediately next source event to confirm freshness.
- **Separate screenshot scaling and readback latency** — Add debug request-stage spans and bitmap preparation timings to locate emulator capture stalls without changing detection or presentation.
- **Add Android capture fallback for Windows helper failures** — Capture authorized Android device pixels through a binary-safe ADB stream when the Windows capture helper cannot run.
- **Analyze capture spans and record live quality admission failure** — Report request-correlated capture stages without treating partial data as zero latency.
- **Add explicit anchor experiment setup for emulator gates** — Allow a true/false instrumentation argument to select the existing debug sampler without granting permissions or arming protection.
- **Classify asynchronous anchor rejection evidence** — Separate clipping, translation disagreement, scope changes and typed node-read failures without recording provider data or changing acceptance policy.
- **Trace fast-result processing and presentation queue stages** — Use the existing numeric capture span schema to separate geometry and policy work from main-thread and frame-tick waiting.
- **Probe model-sized GPU readback and pixel fidelity** — Measure direct small-surface rendering against full screenshot software readback before considering production integration.
- **Compare GPU preparation against real-image detector outputs** — Require positive corpus evidence and one-to-one matching at the production rectangular input shape, while retaining negative controls and reporting preparation timings.
- **Reject per-request GPU renderer churn in preparation probe** — Add ownership and real-corpus tests for the experimental adapter.
- **Exercise GPU source turnover and separate screenshot failures** — Reuse the same GPU output across fresh recycled sources and record why mixed-path live trials cannot yet establish throughput gains.
- **Measure host and Android clock offset before video correlation** — Record bounded midpoint/round-trip estimates so host video is not aligned directly to a drifting emulator wall clock.
- **Validate actual GPU preparation adapter on Pixel hardware** — Use the production candidate adapter in the synthetic probe and record the single user-authorized physical-device run.
- **Observe cached scroll-source bounds without camera mutation** — Log numeric source rectangles and observation cost to distinguish document scrolling from viewport translation.
- **Pair scroll-source bounds with absolute offsets** — Include raw event offsets in numeric debug telemetry to distinguish clipped document geometry from viewport translation.
- **Add bounded row-motion observer with ambiguity rejection** — Estimate vertical frame motion without Android or camera authority, requiring spatial consensus and rejecting weak/ambiguous inputs.
- **Fence shadow row-motion observations by capture scope and time** — Extract bounded descriptors from caller-owned prepared pixels, retain no source bitmap, and reject stale pairs, scope/crop changes and motion without a recent scroll signal.
- **Observe clean capture-frame motion behind a shadow-only switch** — Add capture/source-geometry fences and bounded descriptor extraction from already-prepared pixels.
- **Separate shadow observer CPU cost from scheduling delay** — Preserve legacy unknown CPU values and document the equally slow observer-off control rather than attributing global emulator slowdown to the observer.
- **Add isolated Android row observer CPU benchmark** — Measure prepared-pixel extraction and motion estimation separately from scheduling delay while checking every estimated displacement.
- **Cover noisy row translations and unrelated frames** — Protect experimental row-motion scoring against incorrect acceptance after early-abandon optimization with 1040 deterministic image-descriptor pairs.
- **Separate row descriptor and matching CPU costs** — Instrument the actual descriptor and matcher stages to identify the dominant experimental observer cost without changing production visibility or behavior.
- **Account for row observer runtime warmup** — Increase diagnostic warmup and correct the interpretation of short-run emulator costs.
- **Sample live Accessibility main-thread work on emulator** — Add a bounded one-shot debug probe and strict parser to locate censor publication stalls without disconnecting the live service during sampling.
- **Compare scoped camera corrections with recorded frame clocks** — Integrate camera reconciliation behind the existing shadow-only switch and add strict timestamp-matched optical checks.
- **Capture private prepared frames to verify screenshot timing** — Add a bounded emulator-only asynchronous frame recorder and photometric comparison against clock-matched display recordings.
- **Verify native Chrome bounds against toolbar movement** — Add a bounded emulator-only geometry probe and strict parser.
- **Reproduce bounded capture gaps without blocking scroll or render** — Add a private, explicitly armed emulator-only five-second capture-admission pause with a guarded replay runner and strict gap/recovery parser.
- **Trace source and tracker face geometry without pixel-time assumptions** — Bound face-only geometry records and preserve raw versus filtered coordinates, observation source and camera basis.
- **Compare registered source motion with face geometry** — Trace source-image poses alongside face observations to distinguish missed event motion from tracker smoothing without assuming pixel time or changing rendering.
- **Audit frozen Pixel candidate source lineage** — Identify source retained only in the worktree before assembling another Pixel candidate, without treating a commit hash or source fingerprint as APK equivalence.
- **Prevent unrelated backfill categories sharing confirmation** — Require distinct body-part observations to earn their own cache confirmation and retain independent same-capture candidates.
- **Expose numeric scroll producer evidence for X diagnosis** — Record supplied offsets, ranges, indices and existing owner-resolution outcomes without extra node reads or page identifiers.
- **Capture named UI states and handle fractional display densities** — Make the documentation capture suite wait for the actual wizard, dialog and app-assignment state, isolate service-lock fixtures, and preserve strict geometry checks with independently rounded native padding.
- **Add bounded source-pair pixel evidence controls** — Detector-independent source-pair controls now distinguish sampled RGB correspondence from unqualified physical motion without changing live censor behavior.
- **Guard Lab trace and native fixture verification** — Keep Lab traces and native fixture settings verifiable across successive runs without weakening privacy or ownership checks.
- **Add sustained native scroll and observer lifetime checks** — Add long native scroll/reversal, new-node discovery and withdrawal checks while keeping production behavior unchanged.
- **Add parent-bound native window acquisition diagnostics** — Add optional window-read timing diagnostics without changing censor scope checks or defaults.
- **Verify native cancellation across observer cutoff** — Add deterministic native lifecycle coverage for cancellation across observer cutoff and actual reader retirement.
- **Verify natural native renewal without held provider reads** — Add an actual-service scroll regression that preserves independent parent counts and original node deadlines through natural two-address renewal.
- **Qualify natural native service accounting** — Qualify normal native detection accounting through natural completed reads without partial-read credit or duplicate charges after appearance changes.
- **Measure live Accessibility scroll alignment against pixel witnesses** — The debug qualification fixture now exercises real scroll callbacks, captured source pixels and asynchronous Surface painting against same-frame witness geometry, exposing moving alignment errors separately from stationary coverage.
- **Observe every actual censor Surface post independently of UI delivery** — Add default-off copied draw and post metadata so scrolling diagnostics can distinguish submitted geometry from later UI acknowledgement.
- **Verify rejected colors through actual producer metadata** — Validate unknown and wide-gamut capture colors without changing the copier or capture defaults.
- **Qualify foreground projection capture and borrowed-frame shutdown** — Controlled capture tests now exercise real consent, cross-app pixels and source shutdown while a copier holds an image.
- **Qualify projection feedback with a frozen real censor** — Add an opt-in emulator probe that rejects projection input containing the actual censor overlay, preventing that source from being promoted as clean input.
- **Verify visible replacement censors with native photo pixels** — Add an opt-in emulator regression that requires actual photo pixels and a source-supported painted face censor without changing capture defaults or runtime behavior.
- **Exercise real-photo fast scrolling and reversals** — Add bounded native motion and same-frame observations to expose censor drift and missing coverage without changing the product runtime.
- **Forward submitted geometry through the product observer path** — Censor diagnostic fixtures can now observe actual submitted geometry through the product path without changing tracking behavior.
- **Qualify an opt-in bounded CPU inference path** — Add explicit CPU-only detector budgets and bounded RGB preprocessing for controlled backend qualification while preserving production constructors and serialized scheduling.
- **Clarify profile fixtures and native viewport checks** — Build avatar fixtures through the persisted JPEG encoder, scope allowed preview ellipsis, and await resumed parent activities before recreation.
- **Restore consent fixtures after a failed receipt witness** — Complete endpoint and authority restoration, owned shutdown and database cleanup even when a receipt assertion fails.
- **Add opt-in two-emulator pairing and delivery bridge** — Keep manual two-device pairing, journal delivery, and reconnect observations reproducible behind an explicit instrumentation opt-in.
- **Align onboarding checks with Sub feature choices** — Keep onboarding regression coverage aligned with Hardcore respecting feature choices while preserving release and payment-consent explanations.
- **Retire lifecycle fixture alarms under runtime ownership** — Lifecycle notification fixtures now retire their owned alarms under the required runtime lock, await completion and release every fixture resource even when cleanup fails.
- **Give extra-time fixtures the production continuity gate** — Verify Dom approval and the app-time request screens through real continuity witnesses on freshly owned synthetic databases.
- **Authenticate protected fixture revocation** — Verify that app-time credit disappears after an authenticated relationship revocation while retaining the unauthenticated refusal check.
- **Preserve paired controller authority in app-time fixtures** — Verify that the paired Sub cannot unlink its controller without authority and that the exact relationship remains active after refusal.
- **Capture app-time review in ordinary touch mode** — Capture the app-time request and review screens in ordinary touch mode while retaining strict composed-pixel evidence and the original input modality.
- **Prepare owned tribute editor credentials** — Verify tribute rule persistence and grouped settings with acknowledged local credentials on an idle, unpaired emulator.
- **Reveal tribute navigation before the native Back tap** — Native tribute grouping verification returns to the overview through a fully visible Back control.
- **Await observed touch-mode restoration in extra-time captures** — Observe the attached focused target after the WindowManager touch-mode request rather than treating an idle app queue as completion.
- **Anchor unavailable-request captures on opaque reason text** — Keep disabled request buttons at half opacity while placing the screenshot composition witness on the adjacent opaque reason.
- **Retain the session gateway through duration autosave refresh** — Bind the synthetic session gateway through the profile composition hook so configuration-revision refreshes and recreation keep exercising the real duration fence.
- **Seed and verify active Tasks overview relationships** — Use an owned active local relationship in both Tasks overview fixtures and exercise both routes in the strict 54-method device/profile suite.
- **Attach and settle native profile fixtures** — Verify allowance accessibility in an attached debug Activity and wait for the newly imported avatar and recreated allowance text layout before asserting native presentation.
- **Resolve profile avatar fallback by its tagged view** — Verify the visible initials and removed image after avatar removal using the tagged ProfileAvatarView rather than stale ancestor assumptions.
- **Retain the native extra-time draft restoration witness** — Record synthetic editor and retained-draft state around the large-font recreation check without changing its input or assertions; keep Dummy Sub qualification fixed and identify its actual workflow in receipts.
- **Separate roster journal acknowledgements from preview isolation** — Keep Dummy Sub preview comparisons strict while allowing only the real roster's documented write_witness UUID refresh after returning to PeopleActivity.
- **Isolate real preview adapters and restore owned fixture state** — Exercise Dummy Sub through actual production adapters bound to an owned endpoint and relationship store instead of leaving captured providers attached to the application's database.
- **Recreate preview children within their existing navigation task** — Qualify Presets, Whispers and Popup recreation on the actual open child rather than launching an independent ActivityScenario and expecting its retained parent to resume.
- **Reveal native Back controls before preview navigation** — Scroll the actual Back control into view after inspecting lower settings, using the existing native reveal helper.
- **Qualify preview editors in separate bounded contracts** — Keep every real preview editor recreation, read-only input, forged route and live revocation assertion in independently restored native cases without increasing the per-method timeout.
- **Prepare guarded authentication for explicitly unkeyed fixture guests** — Allow endpoint cleanup regressions to prepare a temporary PIN only for an explicitly unkeyed guest with no credentials, retaining existing credentials, guarded verification and exact restoration.
- **Keep read-only cleanup contracts independent of guest authentication** — Verify owned endpoint failure cleanup without changing credentials or roles that its read-only snapshot never requires, and assert the original settings and session remain unchanged before failure-safe restoration.
- **Identify compile-stage failures with fixed diagnostic labels** — Canonical verification now identifies bounded Java, Kotlin and resource build stages without exposing compiler messages, source paths or resource values.
- **Read theme fixtures with Android-compatible file APIs** — Theme contract tests use readAllBytes with explicit UTF-8 instead of Files.readString, which is absent from Android compile stubs.
- **Use supported Android file APIs in internal storage fixtures** — Keep the owned-storage isolation and concurrency assertions while replacing Files string helpers introduced after compileSdk35 with byte operations and explicit UTF-8.
- **Use Android-compatible file APIs in owner fixtures** — Keep the provisioning and persistence regression fixtures compiling against the Android API by using explicit UTF-8 byte reads and writes instead of newer Files string helpers.
- **Run capture host Android fixtures on the native runner** — Keep the two pure API-boundary contracts in JVM tests and move fourteen unchanged Intent-dependent contracts to the explicitly inventoried Android suite.
- **Fence stale Demo Start after a visibility change** — Verify that hide and re-add retire a permission-approved reservation and that a delayed callback cannot consume the fresh reservation.
- **Admit the isolated expiry fixture through the legacy barrier** — Bind the exact registered debug fixture to the physical Application for legacy ticket accounting while preserving its isolated preferences.
- **Register crossed peer retries in native suites** — The battery and device regression suites include authenticated crossed-dial retries and the malformed and background controls.

### Maintenance

- **Merge committed censor pipeline updates into UX branch** — Keep UX updates based on all committed censor-pipeline-v2 work while preserving the isolated export foundation.
- **Sync committed PayPal billing updates into UX branch** — Keep ux-updates current with the committed censor pipeline branch while retaining the gallery and controller work.
- **Remove redundant Wallet and Help helper text** — Wallet and Help omit four unwanted helper notices, and an absent update summary no longer leaves an empty row.
- **Integrate isolated censor fixes with current UX** — Combine temporary Censor Lab ownership and source-phase identity matching with the current interface while preserving saved service settings and existing censor quality policy.
- **Deps: Bump com.google.android.material:material** — Bumps [com.google.android.material:material](https://github.com/material-components/material-components-android) from 1.11.0 to 1.14.0.
- **Deps: Bump com.microsoft.onnxruntime:onnxruntime-android** — Bumps [com.microsoft.onnxruntime:onnxruntime-android](https://github.com/microsoft/onnxruntime) from 1.20.0 to 1.31.1.
- **Deps: Bump org.jetbrains.kotlin.android from 2.2.21 to 2.4.21** — Bumps [org.jetbrains.kotlin.android](https://github.com/JetBrains/kotlin) from 2.2.21 to 2.4.21.
- **Preserve unqualified internal Sub code for OPS handoff** — Preserve the stopped owner, session, capture, accounting and lifecycle work as exact source snapshots for implementation handoff.
- **Deps: Bump androidx.test.uiautomator:uiautomator** — Bumps androidx.test.uiautomator:uiautomator from 2.4.0 to 2.4.1.

</details>

<details>
<summary><strong>SubHub 0.6.3</strong></summary>

## What’s new in SubHub 0.6.3

### New

- **Add Atmosphere and trusted arrangement updates** — Brings Whispers and Popup Storm into one discoverable Atmosphere hub, and lets Sub Space apply an active Studio pack update only when its pack, creator-device, name, and author identity still match.

### Improved

- **Unify SubHub around a purple accent system** — Replaces the remaining hot-pink default chrome with a consistent violet palette across buttons, tabs, switches, selection tiles, quality icons, borders, and Whisper artwork.

### Quality

- **Publish the complete SubHub screen map** — Expands the device screenshot suite to cover both spaces and every major settings, Wallet, Studio, Atmosphere, diagnostics, update, achievement, and service-lock surface.

</details>

<details>
<summary><strong>SubHub 0.6.2</strong></summary>

## What’s new in SubHub 0.6.2

### Fixed

- **Simplify Home progress summary** — Present one focused current-service row and a compact two-row lifetime summary so Home stays useful without duplicating the detailed Statistics page.

</details>

<details>
<summary><strong>SubHub 0.6.1</strong></summary>

## What’s new in SubHub 0.6.1

### New

- **Complete app-wide service progress** — Expand Home and Statistics beyond censor counts with durable feature-aware service history and retuned achievements; preserve timed service state across process and Accessibility interruptions; let Dom Space release timed service…

</details>

<details>
<summary><strong>SubHub 0.6.0</strong></summary>

## What’s new in SubHub 0.6.0

### New

- **Add portable SubHub Studio arrangements** — Introduce the always-available Studio surface and bounded .subhubpack format with drafts, previews, embedded art, role-aware sharing, reviewed section merges, reversible backups, optional locks, and legacy .bbpack import.

</details>

<details>
<summary><strong>SubHub 0.5.10</strong></summary>

## What’s new in SubHub 0.5.10

### Fixed

- **Restore the full Hardcore guard** — Require both Accessibility and Device Admin before Hardcore protection is considered ready, surface persistent repair guidance after revocation or updates, and keep the Clear Storage target blocked while leaving Clear Cache usable.

</details>

<details>
<summary><strong>SubHub 0.5.9</strong></summary>

## What’s new in SubHub 0.5.9

### Fixed

- **Guard Clear Storage in Hardcore mode** — Resolve the real Android application window while the accessibility concealment overlay is present, so navigating from App info to Storage cannot make the guard inspect its own overlay and clear itself.

</details>

<details>
<summary><strong>SubHub 0.5.8</strong></summary>

## What’s new in SubHub 0.5.8

### Fixed

- **Refine arrangement labels** — Replace the remaining Ass and Anus category copy with Butt / Cheeks and Anal, and render ordinary censor, detection, and text-filter values in Title Case instead of arbitrary all-caps text.

</details>

<details>
<summary><strong>SubHub 0.5.7</strong></summary>

## What’s new in SubHub 0.5.7

### Fixed

- **State active filters on the Home card** — Label the compact Beta Filter summary explicitly as Image filter on/off and Text filter on/off.
- **Reuse Dom labels in arrangement details** — Render censor style, border, detection, text-filter, and phrase values from the same string resources used by Dom settings so the Sub-space summary cannot drift into alternate names.
- **Capitalize arrangement option labels** — Use consistent Title Case for body-area, text-filter, capture, phrase, and arrangement labels in both Dom settings and the Home detail sheets.

</details>

<details>
<summary><strong>SubHub 0.5.6</strong></summary>

## What’s new in SubHub 0.5.6

### New

- **Add premium motion and frosted plum surfaces** — Give tab changes, back navigation, and dialogs restrained directional motion while layering translucent plum surfaces and supported Android 12 blur for a calmer premium finish.
- **Add dated achievement detail cards** — Make every visible achievement tappable for a larger badge, description, progress, and unlock-date view, persist future unlock timestamps, and backfill existing unlocks to the 2026-08-27 update date.
- **Clarify Home arrangement and progress summaries** — Home now uses exact Dom labels, reports image/text state and enabled tribute-rule counts, shows SubHub's app icon with SUB SPACE or DOM SPACE, and puts the statistics button below session metrics.

### Documentation

- **Clarify cumulative release-note generation** — Document the distinction between the single-release changelog output and the cumulative history output so local release preparation cannot replace older changelog entries.

### Build & release

- **Bundle SubHub 0.5.5 release history** — Embed the complete collapsible 0.5.5 changelog in both repository Markdown and the in-app release history.
- **Stage immutable releases before publication** — Upload verified release assets one at a time to a reusable draft, compare the complete remote asset set with local build outputs, and only then publish the immutable release.
- **Publish SubHub 0.5.6** — Roll the complete tested 0.5.5 feature set forward after GitHub's immutable-release policy permanently consumed the unpublished v0.5.5 tag.

</details>

<details>
<summary><strong>SubHub 0.5.4</strong></summary>

## What’s new in SubHub 0.5.4

### New

- **Add browsable release and arrangement details** — Make every tagged changelog available as compact expandable update cards and let Home arrangement cards reveal their full active selections without exposing edit controls.
- **Give SubHub a new launcher identity** — Replace the fragile horned shield with an approved demonic-heart lock mark that remains legible inside Android's adaptive safe zone.

### Build & release

- **Add release-run recovery** — Allow an existing immutable tag to be rebuilt through a manual workflow dispatch when GitHub leaves the original tag event stuck before job creation.

</details>

<details>
<summary><strong>SubHub 0.5.3</strong></summary>

## What’s new in SubHub 0.5.3

### New

- **Rebuild the Home dashboard** — Replace the stacked Home cards with a responsive command, arrangement, and progress hierarchy.
- **Rebuild the Home command deck** — Home now uses a cohesive dark command deck with compact single-line arrangement cards and calmer service controls.

### Faster & smoother

- **Keep Ultra's hot path bounded** — Expired censor tracks now leave the tracker instead of accumulating for an entire protection session, while low-confidence model candidates are rejected before class metadata work and small per-frame collection allocations are reduced.

### Build & release

- **Generate meaningful release changelogs** — GitHub releases and the in-app updater now show categorized user-visible changes before separate APK guidance.

</details>

<details>
<summary><strong>SubHub 0.5.2</strong></summary>

## What’s new in SubHub 0.5.2

### Fixed

- **Stop app limits with protection** — Gate both daily-limit usage accounting and foreground eviction on SubHub's armed protection state.

</details>

<details>
<summary><strong>SubHub 0.5.1</strong></summary>

## What’s new in SubHub 0.5.1

### Fixed

- **Complete protected interactions and update handoff** — Start service-duration locks only after the selected capture path is available, and clear stale timed state when protection is no longer engaged.

</details>

<details>
<summary><strong>SubHub 0.5.0</strong></summary>

## What’s new in SubHub 0.5.0

### New

- **Add on-device subliminal messaging** — Add an independent Subliminal module with Dom-only configuration, per-app assignment, safe touch-through Accessibility overlays, phrase packs, profile persistence, Sub-mode status, aggregate statistics, and four tiered achievements.

</details>

<details>
<summary><strong>SubHub 0.4.0</strong></summary>

## What’s new in SubHub 0.4.0

### New

- **Polish core control surfaces and payment flow** — Refresh Home, achievements, and censor-style controls with stronger hierarchy, truthful per-app limit summaries, larger milestone artwork, and a confined animated border renderer.
- **Rework Help and Wallet configuration** — Move Help & Safety to a dedicated Home card available in Dom and Sub views, place PayPal second-last in Settings, and expand the in-app guide to the current feature model.
- **Add signed GitHub release updates** — Discover stable and preview SubHub releases every six hours and expose a Help-linked maintenance screen in both Dom and Sub modes.

### Improved

- **Add GNU GPL v3 license** — Added the GNU General Public License version 3 to the project.

### Documentation

- **Refresh the SubHub product guide** — Replace the dense README with a concise visual product page, document saved-wallet auto-pay boundaries, update the 58-badge catalog, and remove every Safe Browser reference and stale capture.
- **Explain PayPal Wallet setup** — Add a current setup guide for Sandbox and Live REST apps, merchant eligibility, Save payment methods and Vault capabilities, payer-wallet authorization, and Germany-safe handling of PayPal's combined PayPal and Venmo label.

</details>

<details>
<summary><strong>SubHub 0.3.0</strong></summary>

## What’s new in SubHub 0.3.0

### New

- **Polish protection settings and milestones**

</details>

<details>
<summary><strong>SubHub 0.2.0</strong></summary>

## What’s new in SubHub 0.2.0

### New

- **Add illustrated SubHub achievement collection** — Expand the catalog from 42 to 56 state-backed milestones covering App Mode, limits, pacts, Hardcore, text filtering, packs, Wallet, PayPal vaulting, and paid pauses.
- **Give every achievement unique tiered artwork**

### Fixed

- **Preserve protection state when enabling Hardcore** — Treat Device Admin as a capability grant instead of implicitly arming App Mode.

### Documentation

- **Give SubHub a polished visual README** — Replace the reconstruction-led presentation with a product-first story, paired device screenshots, scene-aware copy, and compact build details.

### Build & release

- **Make the Gradle wrapper executable** — GitHub's Linux runners could not start gradlew from a fresh checkout.

</details>

<details>
<summary><strong>SubHub 0.1.0</strong></summary>

## What’s new in SubHub 0.1.0

### New

- **Establish private APK modification workflow**
- **Add compiling source reconstruction foundation**
- **Reconstruct detector and tracking core**
- **Add consent-first capture and overlay pipeline**
- **Add live censor settings and styles**
- **Rebuild the hardened safe browser**
- **Restore local protection statistics**
- **Restore detector performance presets**
- **Restore advanced censor effects**
- **Add export and accessibility capture**
- **Restore advanced browser workflow**
- **Add profiles and hardened packs**
- **Add local runtime diagnostics**
- **Restore statistics and achievements**
- **Restore safely constrained popup storm**
- **Add gothic help and locale shell**
- **Add consent-first commitment pact**
- **Add consent-first penance ledger**
- **Add PayPal settlement service**
- **Add battery-aware app recognition mode**
- **Restore visual parity and working app limits**
- **Rebrand app as SubHub with adaptive navigation**
- **Polish SubHub controls and capture stability**
- **Complete controller-gated SubHub surfaces**
- **Complete detection and interface reliability pass**
- **Add modular capture and wallet settings**
- **Add Dom and Sub presentation modes**
- **Add consensual Hardcore Mode and Sub checkout**
- **Finish SubHub control and payment pass**
- **Add timed pacts and guarded hardcore payments**
- **Complete SubHub home and paid pause pass**
- **Add local smut model and Ultra OCR**
- **Rebuild text censoring around exact OCR lines**
- **Finish wallet authorization and copy pass**
- **Polish the SubHub control experience** — Refresh the visual system, add live censor previews, make App Mode the new-install default, gate tribute events behind active service, and add rate-limited Hardcore tamper tributes.

### Fixed

- **Audit every decoded DEX tree**
- **Validate API 35 runtime behavior**
- **Restore persistent automatic censoring**
- **Ignore transient system windows in app mode**
- **Improve native text detection and alignment**
- **Track censors during continuous scrolling**
- **Simplify settings navigation controls**
- **Enforce Hardcore protection boundary**
- **Charge censor taps at live overlay bounds**
- **Clean adaptive launcher icon**
- **Align text censors and conceal guarded controls**
- **Put Hardcore first and normalize Censor header**
- **Align text bars and stabilize video linger**
- **Anchor censor overlays to the full display**
- **Stop censoring outside assigned apps**
- **Enforce censor scope from live app windows**
- **Detect censor taps and verify PayPal credentials**
- **Fall back to standard PayPal checkout**
- **Clarify German PayPal vault eligibility**
- **Finalize PayPal wallet after browser fallback**

### Faster & smoother

- **Precompute and bound live censor rendering**
- **Keep accessibility censors aligned while scrolling**
- **Decouple Ultra capture from inference**
- **Accelerate Ultra detection pipeline**
- **Move censors with accessibility scroll events**
- **Keep Ultra inference active while scrolling**
- **Run text detection concurrently**
- **Cut APK size with ABI splits and compact assets**

### Improved

- **Refine home sessions and detection behavior**

### Documentation

- **Explain patched APK signing constraints**
- **Define full feature parity scope**
- **Record renderer and responsive QA**
- **Clarify Android geometry test placement**

### Build & release

- **Enforce monetization-free APK builds**
- **Automate signed Android releases** — Centralize Android version metadata, build universal and per-ABI artifacts, validate signing inputs, and publish verified APKs with checksums.

### Quality

- **Expand end-to-end Android coverage**

### Maintenance

- **Ignore local Jolli integration state**

</details>
