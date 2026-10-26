# Pairing two devices

SubHub pairs a Dom device with a Sub device using Iroh. App data stays on the devices. Connections are encrypted between the paired identities; a relay can carry encrypted traffic when a direct connection is unavailable. Pairing does not require a central account or a message-storage service. Relay availability and each phone's network and battery restrictions still affect connectivity.

Choose Dom only, Sub only, or Both for each device. Dom only provides the management workspace without setting up local protection. Device usage is separate from unlocking controller access. Existing installations keep Both until changed; an assigned Dom or active local protection can prevent a capability change.

## Pair

1. On the Dom phone, set up local control with a PIN under Settings → Keyholder and enter Dom mode.
2. Open People, add a Sub, and create an invitation. Share or copy its pairing code, or show its QR code. Anyone holding an unused invitation can request pairing, so share it privately.
3. On the Sub phone, choose Pair with Dom in setup or Settings → Keyholder. Paste the pairing code. Existing invitation links also work, including links opened by a QR reader; SubHub does not require a camera permission. The Sub does not need to create a local PIN first. If it already has local credentials, authenticate before assigning a Dom.
4. Review the device fingerprint and shared controls on both phones, then approve the pairing explicitly. Compare fingerprints with the other person before accepting an unexpected request.

The Sub automatically creates a separate authenticator credential for this relationship. Its paired Dom stores it over the encrypted, identity-bound connection before pairing completes. On the Dom phone, open that Sub’s Connection → Keyholder code and authenticate to view a short-lived code. Codes work on the Sub without an active network connection; a successfully used code cannot be reused. Both phones need the correct time.

For an existing pairing, update both phones and let them sync once to provision the relationship’s keyholder code. If protected credential storage is missing or unreadable, the app refuses to replace it silently. When the devices can still connect, unpair from the Dom and approve a new pairing to establish a new credential.

While a Dom is assigned, the Sub’s keyholder screen offers code-based recovery and hides local PIN controls. Old local PIN and manually configured authenticator data are retained privately, but cannot unlock or replace the assigned Dom. Recovery access allows unpairing; ordinary local configuration remains managed until the relationship is revoked.

An invitation expires and is limited to one relationship. Pairing by itself preserves the Sub's current configuration and running session. Android permissions remain local: pairing cannot grant Accessibility, screen capture, usage access, or other platform permissions.

## Shared information and controls

The Dom can view the Sub's last synchronized statistics, daily activity, configured app allowances, installed-app choices, current contract, and runtime state. Observation times and missing or stale data matter: an old observation does not establish that the other phone is currently connected or that its service is running.

Open the person's profile to adjust session duration directly, or its Contract to edit feature settings, included apps and allowances, and supported artwork. Apply sends a versioned configuration to that Sub; pending or delivered does not mean applied. Concurrent edits can produce a conflict that requires reloading the current configuration.

Artwork transfers as bounded, verified file content. Existing PINs and manual authenticator secrets, payment credentials, and local file-access grants stay on their originating phone. The separate relationship authenticator credential is shared only with the approved Dom, stored encrypted, and excluded from ordinary synchronization records. Wallet settings can be configured remotely, but pairing does not transfer payment authentication or initiate payments.

End session targets the specific active session last observed on that Sub. Requests expire quickly and are rejected when the peer, relationship, or session has changed. A queued request cannot end a later session. Remote Start and Pause are unavailable.

## Activity

The Dom's Activity tab shows selected-app entries, triggered Tributes, and reached app or shared limits. A Tribute event includes its assessed amount and rule. App entries and limits require the Sub's Accessibility service and the corresponding settings. Both devices must support Activity; older peers continue compatible statistics and configuration synchronization without sending Activity events.

Events retain their source time and local receipt time. Interrupted synchronization can delay arrival; replayed receipts preserve read state and do not create another notification. Android notification permission and channel settings control alerts. OS notifications use generic text; unlock the Activity feed to view details. Only entries visible on screen become read.

Profiles show recent events for the selected person. This device's history stays local unless a new event was recorded for its currently approved Dom. Pairing with another Dom does not send earlier local history. Unpairing stops accepting events and cancels that relationship's notifications.

## Offline and background behavior

Changes remain in a local outbox while the peer is unavailable. Sync sends bounded batches and resumes from acknowledged updates; interrupted transfers and duplicate deliveries can be retried safely. Pending changes need the original installation's data to survive.

Keep both apps open for initial pairing and large transfers. With an active relationship, Android schedules network-constrained background work at an approximately 15-minute cadence and can delay it substantially. One open app and one backgrounded app is best effort. Both apps closed, force-stopped, battery-restricted, or offline can prevent synchronization. There is no server mailbox to deliver data while both phones are unavailable.

## Unpair, reinstall, and recovery

Unpairing revokes the local relationship immediately and queues a revocation notice for the other device. The other phone can learn about it only when a connection becomes possible. The Sub retains its last applied configuration and returns to local configuration control; unpairing does not reset settings or end a running session. The revoked relationship’s authenticator credential stops working locally and is removed. Previous local credentials become available again; a Sub that never configured local control can set it up afterward.

Uninstalling or clearing app data loses the installation's identity, pairing records, and pending changes. Reinstalling requires a new invitation and approval; a matching device name does not restore trust. Identity secrets use Android Keystore protection and are excluded from app backup. If protected identity storage becomes unreadable, synchronization fails rather than silently replacing the identity. The phone cannot receive a revocation notice or verify a relationship code whose wrapping key is lost. Clearing its app data establishes a new identity and requires pairing again, but also loses its local configuration, history, and pending changes.
