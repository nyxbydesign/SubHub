# Pairing two devices

SubHub pairs a Dom device with a Sub device using Iroh. App data stays on the devices. Connections are encrypted between the paired identities; a relay can carry encrypted traffic when a direct connection is unavailable. Pairing does not require a central account or a message-storage service. Relay availability and each phone's network and battery restrictions still affect connectivity.

## Pair

1. Configure a keyholder PIN or authenticator on each phone.
2. On the Dom phone, open People, add a Sub, and create an invitation. Share its link or show its QR code to the other person. Anyone holding an unused invitation can request pairing, so share it privately.
3. On the Sub phone, open Connection and use the invitation. A QR reader can open the invitation link; SubHub does not require a camera permission.
4. Review the device identity shown on each phone and approve the pairing explicitly. Check the fingerprints with the other person before accepting an unexpected request.

An invitation expires and is limited to one relationship. Pairing by itself preserves the Sub's current configuration and running session. Android permissions remain local: pairing cannot grant Accessibility, screen capture, usage access, or other platform permissions.

## Shared information and controls

The Dom can view the Sub's last synchronized statistics, daily activity, configured app allowances, installed-app choices, current contract, and runtime state. Observation times and missing or stale data matter: an old observation does not establish that the other phone is currently connected or that its service is running.

Open the person's Contract to edit feature settings, included apps and allowances, duration, and supported artwork. Saving retains a contract for that relationship. Applying sends a versioned configuration to the Sub; pending or delivered does not mean applied. Concurrent edits can produce a conflict that requires reloading the current configuration. Portable contracts can be imported or exported without copying local Android permission grants.

Artwork transfers as bounded, verified file content. PINs, authenticator secrets, payment credentials, and local file-access grants stay on their originating phone. Wallet settings can be part of a contract, but pairing does not transfer payment authentication or initiate payments.

End session targets the specific active session last observed on that Sub. Requests expire quickly and are rejected when the peer, relationship, or session has changed. A queued request cannot end a later session. Remote Start and Pause are unavailable.

## Offline and background behavior

Changes remain in a local outbox while the peer is unavailable. Sync sends bounded batches and resumes from acknowledged updates; interrupted transfers and duplicate deliveries can be retried safely. Pending changes need the original installation's data to survive.

Keep both apps open for initial pairing and large transfers. With an active relationship, Android schedules network-constrained background work at an approximately 15-minute cadence and can delay it substantially. One open app and one backgrounded app is best effort. Both apps closed, force-stopped, battery-restricted, or offline can prevent synchronization. There is no server mailbox to deliver data while both phones are unavailable.

## Unpair, reinstall, and recovery

Unpairing revokes the local relationship immediately and queues a revocation notice for the other device. The other phone can learn about it only when a connection becomes possible. The Sub retains its last applied configuration and returns to local configuration control; unpairing does not reset settings or end a running session.

Uninstalling or clearing app data loses the installation's identity, pairing records, and pending changes. Reinstalling requires a new invitation and approval; a matching device name does not restore trust. Identity secrets use Android Keystore protection and are excluded from app backup. If protected identity storage becomes unreadable, synchronization fails rather than silently replacing the identity.
