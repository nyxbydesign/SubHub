# Pairing two devices

SubHub pairs a Dom device with a Sub device using Iroh. App data stays on the devices. Connections are encrypted between the paired identities; a relay can carry encrypted traffic when a direct connection is unavailable. Pairing does not require a central account or a message-storage service. Relay availability and each phone's network and battery restrictions still affect connectivity.

Choose Dom only, Sub only, or Both for each device. Dom only provides the management workspace without setting up local protection. Device usage is separate from unlocking controller access. Existing installations keep Both until changed; an assigned Dom or active local protection can prevent a capability change.

## Pair

1. On the Dom phone, set up local control with a PIN under Settings → Keyholder and enter Dom mode.
2. Open People and choose Add Sub. Its QR code opens directly; share or copy the pairing code from that page. The app keeps a usable invitation or automatically replaces an unusable pending attempt. Anyone holding an unused invitation can request pairing, so share it privately.
3. On the Sub phone, choose Pair with Dom in setup or Settings → Keyholder. Paste the pairing code. Existing invitation links also work, including links opened by a QR reader; SubHub does not require a camera permission. The Sub does not need to create a local PIN first. If it already has local credentials, authenticate before assigning a Dom.
4. Review the device fingerprint and shared controls on both phones, then approve the pairing explicitly. Compare fingerprints with the other person before accepting an unexpected request.

The Sub automatically creates a separate authenticator credential for this relationship. Its paired Dom stores it over the encrypted, identity-bound connection before pairing completes. On the Dom phone, open that Sub’s Connection → Keyholder code and authenticate to view a short-lived code. Codes work on the Sub without an active network connection; a successfully used code cannot be reused. Both phones need the correct time.

For an existing pairing, update both phones and let them sync once to provision the relationship’s keyholder code. If protected credential storage is missing or unreadable, the app refuses to replace it silently. When the devices can still connect, unpair from the Dom and approve a new pairing to establish a new credential.

While a Dom is assigned, the Sub’s keyholder screen offers code-based recovery and hides local PIN controls. Old local credentials cannot replace the assigned Dom. Recovery access allows unpairing; ordinary local Sub configuration remains managed until the relationship is revoked.

On a Both device, its existing local PIN can independently open its Dom profile and manage its outgoing Sub relationships, including while its own Dom is offline. That workspace unlock leaves the incoming Sub session, timer, configuration, and protection unchanged. It does not authorize stopping local Sub protection, changing its managed configuration, or unpairing its assigned Dom. Settings → Keyholder remains available for inspecting status when connection or credential checks are unavailable.

The Dom's lists, Activity, Tasks, and aggregate statistics include the local Sub only after an explicit local relationship is approved. The Sub's own profile, history, and tasks remain independent of that relationship.

An invitation expires and is limited to one relationship. Pairing by itself preserves the Sub's current configuration and running session. Android permissions remain local: pairing cannot grant Accessibility, screen capture, usage access, or other platform permissions.

## Pending pairing and cancellation

An invitation has an original public lifetime of ten minutes. Reopening the page, rotating the phone, or resuming background work does not restart that clock. A request already approved within its original bounds has a separate bounded completion period. The page shows the backend's sampled remaining time; an established relationship is not revoked when an invitation expires.

Add Sub checks outgoing pending attempts under the current Dom authentication. A usable code keeps its original identity, token, approval, and deadline. Expired codes, missing codes, and older pending attempts without verifiable clocks are retired automatically before a fresh invitation is created. Multiple stale attempts are handled together within a bounded operation. Active relationships and their retained data are preserved. Storage or authorization failures are reported instead of silently resetting data.

Creating an invitation or accepting the existing fingerprint review can start pairing work while the page is visible. Returning to the Dom QR page checks the request again and automatically resumes eligible work. Rotation retains an accepted creation that is still in progress. A result arriving while the page is hidden cannot start the service. Back and leaving the page do not cancel the request or renew its original lifetime.

Starting, running, and unavailable describe pairing work, not proof that the other phone is connected or has approved. Both devices must still approve the pinned identities. An expired approval is never revived by reopening the page.

Cancel on the Dom QR page durably retires its pending request and returns to the Subs menu, including when Add Sub was opened from Tasks. The next Add Sub opens a fresh QR code. Each ongoing pairing notification has a Cancel action for its own pending operation. A stale action cannot cancel a replacement or revoke an active relationship. A Both device can keep an outgoing invitation and an incoming request independently; cancelling one does not stop the other's background work.

Pairing notifications use generic text and a low-importance channel. If notification permission or the channel is blocked, Android can still allow the foreground service and show work in its active apps list, but notification drawer controls may be unavailable. The page shows that limitation, retains Cancel and status, and links to pairing notification settings. Pairing does not request a notification grant or a battery exemption.

## Shared information and controls

The Dom can view the Sub's last synchronized statistics, daily activity, configured app allowances, installed-app choices, current contract, and runtime state. Observation times and missing or stale data matter: an old observation does not establish that the other phone is currently connected or that its service is running.

Open the person's profile to adjust session duration directly, or its Contract to edit feature settings, included apps and allowances, and supported artwork. Apply sends a versioned configuration to that Sub; pending or delivered does not mean applied. Concurrent edits can produce a conflict that requires reloading the current configuration.

Artwork transfers as bounded, verified file content. Existing PINs and manual authenticator secrets, payment credentials, and local file-access grants stay on their originating phone. The separate relationship authenticator credential is shared only with the approved Dom, stored encrypted, and excluded from ordinary synchronization records. Wallet settings can be configured remotely, but pairing does not transfer payment authentication or initiate payments.

Compatible devices expose Start and Stop beside the Sub profile’s applied service duration. Start requires current Sub-approved Hardcore control consent and a ready App Mode service; it uses the already applied configuration and never applies an unsaved draft. A new Start waits until the required configuration artwork has arrived and been verified. Stop remains available for the specific active session last observed on that Sub, including after Hardcore is turned off; it does not require new artwork. Requests expire quickly and are rejected when the peer, relationship, or session has changed. A queued Stop cannot end a later session. Older peers retain only their supported controls. Remote Pause is unavailable.

## Full Access

On the Sub’s phone, open Profile → Edit profile → Full Access. Hardcore Mode must already be enabled. The setup screen identifies the paired Dom and describes the data available to them. The Sub can grant each supported data permission separately or choose several in the permission setup checklist. Ordinary startup and pairing do not ask for these permissions. Android may deny SMS access on some devices or installers. The Sub can then share a transcript they select instead.

The Dom opens Full Access from the Sub’s profile. Contacts, notification history, SMS, and apps reflect the Sub’s enabled sources and Android permissions. Notification history covers up to seven days after enabling that scope. Protected notifications and credential content are excluded. App usage is based on foreground activity reported by Android and can cover a shorter period than the selected range.

Files are read-only on the Sub’s phone. The Dom can browse Android-permitted shared folders, preview supported media, and explicitly download files or folders. Opening Screen sends a request to the Sub. Screen sharing uses SubHub notifications for requests and the local Stop sharing action. Android asks before each new screen-sharing session. The live view has no audio or saved recording and stops when the Dom leaves the tab or the Sub stops sharing.

The Sub can turn off Full Access locally even during Hardcore Mode. Turning off Hardcore Mode or ending the pairing also ends the grant. Already completed downloads remain with the recipient.

## Activity

The Dom's Activity tab shows tracked-app entries, triggered Tributes, verified Tribute payments, and reached app or shared limits. Triggered and paid are separate facts: a triggered Tribute describes an assessed charge; paid requires a completed PayPal capture matching the original checkout amount, currency and settlement. Manual payment-link confirmation does not create a verified paid event. Paid-pause purchases do not create Tribute-paid activity. App entries and limits require the Sub's Accessibility service and the corresponding settings.

Both devices must negotiate Activity version 2 before exchanging the Events stream. Older peers continue compatible statistics, configuration and pairing operations while Events queues retain their existing cursors and pending records.

Events retain their source time and local receipt time. Interrupted synchronization can delay arrival; replayed receipts preserve read state and do not create another notification. Android notification permission and channel settings control alerts. OS notifications identify the event type without app names or payment details; lock-screen public copy remains generic, and discreet mode redacts the type. Unlock the Activity feed to view details. Only entries visible on screen become read.

Profiles show recent events for the selected person. This device's history stays local unless a new event was recorded for its currently approved Dom. Pairing with another Dom does not send earlier local history. Unpairing stops accepting events and cancels that relationship's notifications.

## Notifications

Settings → Notifications shows only the current Dom workspace or Sub view. Dom choices are tracked app opened, app limit reached, shared limit reached, Tribute triggered, Tribute paid, and task reviews. Sub choices are contract changes, session starts, and session stops. Choices are saved separately on this device, stay unchanged when switching workspaces, and are never sent to the other phone. An earlier disabled Activity choice remains the default for each new individual activity choice until that choice is changed. Disabling a type cancels its optional notices and consumes incoming disabled events without replaying them when enabled again. Required protection, pairing, security and payment-consent notices retain their own controls.

Verified paid activity keeps the audience recorded when the checkout began. A delayed payment cannot be reassigned to a replacement Dom. Its capture reference is hashed for event identity; provider credentials, payer information and raw capture identifiers are not shared. Paid activity is advisory and never credits the receiving Dom's Wallet.

## Offline and background behavior

Changes remain in a local outbox while the peer is unavailable. Each stream resumes from its durably acknowledged cursor and queues only changed records. An unchanged runtime sample does not resend the same state just to update its timestamp; authenticated stream-head confirmation supplies freshness. Interrupted transfers can retry content whose receipt was lost without applying it twice. Pending changes need the original installation's data to survive.

Compatible devices can reuse unchanged feature sections from a document retained for the same peer and pairing generation. SHA-256 references replace section content only when the encoded batch is smaller; idle synchronization sends no section manifest. The receiver reconstructs the complete original batch and verifies its digest before acknowledging it. A missing or stale section basis permits one bounded request for that exact full batch, still requiring its original generation, stream, range and digest. Configuration commands retain their identity and revision checks. Artwork is independently content-addressed, so verified files already present are not requested again.

Keep both apps open for the fingerprint reviews and large transfers. Pending pairing can continue through its user-started foreground service within the original operation's bounds. Android may stop the service; it does not automatically restart after process loss or reboot. Open Add Sub again to check the outgoing request and automatically resume eligible work or receive a fresh code. This is best effort and does not guarantee progress during Doze, under battery restrictions, or while either phone is offline.

With an active relationship, opening SubHub can start a persistent connection service that keeps the peer endpoint available after the app goes into the background. It uses an ongoing connection notification, subject to Android notification controls. Network-constrained background jobs provide a fallback at an approximately 15-minute cadence; Android can delay them substantially. Each job opens a listening and connection window of up to 55 seconds, then releases its temporary endpoint when no other work owns it.

While that persistent service is active, quiet background peers receive a catch-up check about every 15 minutes as a recovery fallback. New local changes to a reachable peer, opening the app, and a stable network return still request prompt synchronization. A successful incoming exchange covers both directions and postpones a redundant outgoing check. Unreachable active peers back off from about 30 seconds to at most 15 minutes; repeated local activity does not restart that retry loop. Opening the app or recovering the network resets that delay. Pairing, revocation delivery, visible-app retries, and the bounded background-job windows retain their shorter retry timing.

Existing selected-app opens, limit events, and other recorded Activity facts trigger a short coalesced sync. Recorded Limits usage, statistics counters, and actual session changes request one catch-up within about a minute, combining repeated updates rather than connecting for every accounting tick. When source data stops changing, that source wake stops too. These are notifications from existing local recording, not new device monitoring or Android Usage Access. Source recording, selected-app scope, local consent, and background-sync opt-out remain unchanged. Commands still use their immediate path. Activity that a device does not record cannot provide an event wake; opening SubHub, network recovery, and the periodic fallback still catch up available data.

These intervals reduce scheduled exchange work, not a guarantee of battery savings or delivery time. The listening endpoint remains available, the transport has its own network maintenance, and Android can delay execution. No permanent wake lock is held for an idle listener. Foreground polling stops when the app is hidden, and pairing polls stop when their last operation lease ends.

Settings → Background sync is on by default and saves a choice for this device. Turning it off stops the persistent connection service and its background jobs. Sync continues while SubHub is open; pairing, queued changes and acknowledged progress are preserved. User-started pending pairing work retains its separate controls and original bounds. Turning Background sync back on restores eligible background syncing, subject to Android's service-start, network and battery restrictions.

Background syncing is best effort. Force-stopping an app, Doze, battery restrictions, unavailable networks or nonoverlapping connection windows can prevent delivery. There is no server mailbox to deliver data while both phones are unavailable.

## Unpair, reinstall, and recovery

Unpairing revokes the local relationship immediately and queues a revocation notice for the other device. The other phone can learn about it only when a connection becomes possible. The Sub retains its last applied configuration and returns to local configuration control; unpairing does not reset settings or end a running session. The revoked relationship’s authenticator credential stops working locally and is removed. Previous local credentials become available again; a Sub that never configured local control can set it up afterward.

Uninstalling or clearing app data loses the installation's identity, pairing records, and pending changes. Reinstalling requires a new invitation and approval; a matching device name does not restore trust. Identity secrets use Android Keystore protection and are excluded from app backup. If protected identity storage becomes unreadable, synchronization fails rather than silently replacing the identity. The phone cannot receive a revocation notice or verify a relationship code whose wrapping key is lost. Clearing its app data establishes a new identity and requires pairing again, but also loses its local configuration, history, and pending changes.
