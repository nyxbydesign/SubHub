# SubHub pack format

`.subhubpack` is SubHub Studio's portable pack format. It is a bounded ZIP archive designed for local creation, review, sharing, and reversible application. Packs use schema version 4 and the shared typed setting catalog. Only the current schema is accepted; earlier packs must be recreated. There are no legacy setting aliases, lock groups, or identity migrations. Both pack and origin identities must be canonical UUIDs.

## Archive layout

```text
manifest.json
sections/modules.json
sections/censor.json
sections/limits.json
sections/wallet.json
sections/subliminal.json
sections/popup.json
assets/censor/*
assets/popup/*
assets/cover/*
```

Only sections declared by `includedSections` are required. The manifest records the format and schema versions, pack identity and author metadata, minimum SubHub version, included sections, non-binding recommendations, per-asset SHA-256 values, and an integrity digest covering the manifest and section data. New manifests never write lock groups.

Studio rejects duplicate or unsafe paths, unknown sections, missing entries, mismatched hashes, malformed metadata, oversized entries, archives that expand beyond the total limit, and arrangements requiring a newer SubHub build.

## Portable sections

| Section | Included data |
|---|---|
| `modules` | Feature-area enablement for Censor, Limits, Wallet, and Subliminal Messaging |
| `censor` | Detection preset, image/body and text filters, phrases, censor appearance, border/effect settings, and capture preference |
| `limits` | Generic per-app and shared allowance defaults only |
| `wallet` | Local tribute triggers, prices, batching, grace, dwell, caps, tamper cooldown, and paid-break configuration |
| `subliminal` | Preset, timing, opacity, text size, phrase groups, and custom phrases |
| `popup` | Popup Storm behavior and embedded popup images |

`PackSettingCatalog` is the single allowlist for capture, native draft controls, validation and typed preference writes. Its 115 fields cover every transferable setting across the six sections, including independent border gradient start/end colors. Unknown fields are discarded; malformed known fields, invalid choices, out-of-range values and incompatible relationships are rejected. String sets stay JSON arrays, integer preferences stay integers, timing longs stay longs and float preferences stay floats. Money controls display decimal amounts but store minor units; percentage controls preserve the runtime ratio. Detection uses `detection_quality` (`low`, `medium`, `high`) and `detection_confidence_percent`; retired detection and Wallet rule keys are not migrated.

Studio's Details → Features → Images → Review editor never writes live preferences. Section controls offer full configuration, current-setting copy and defaults. Missing fields receive catalog defaults when opened for editing, while imported partial sections remain partial until edited. Image reads, thumbnail decoding, archive I/O and draft persistence run off the UI thread. Each image is limited to 25 MiB, with at most 64 images per feature, a single cover in the creator and a 256 MiB total archive limit. Covers are presentation assets, never applied as feature images. Local library previews do not load image payloads; operations validate the complete archive before use.

## Deliberately excluded

An arrangement never carries:

- PayPal access tokens, saved payer or wallet identifiers, approval/verification state, or transaction history. Merchant client ID, secret, environment and fallback recipient link may be included **only** in the opt-in encrypted attachment below, never in ordinary sections.
- Controller PIN material, permission state, Accessibility or Device Admin state, or Hardcore activation state.
- App package names, app assignments, per-app usage, or per-app allowance overrides.
- Current service state, release time, session data, statistics, achievements, ledger history, Wallet currency, automatic-payment consent, Popup Storm photosensitivity acknowledgement, update state, or private filesystem paths.

Hardcore and service-duration fields are recommendations shown during activation. Studio never applies them automatically.

## Applying and restoring settings

Only Dom mode can apply a pack. The review flow chooses sections, shows the proposed changes, validates them against local settings, writes a recovery journal, backs up affected keys, commits typed values, installs verified selected-section assets into private storage, and then records the active pack. An interrupted application is rolled back at next startup; failed recovery retains its journal and blocks another application. Applying/restoring never enters or leaves service.

Packs no longer impose setting locks. The ordinary Dom/PIN editing boundary still applies. Restoring returns the affected keys to their pre-pack values. Replacing a pack restores the previous backup before applying the next one. Sub mode may create, edit, import, duplicate, export and share drafts, but cannot newly apply or restore a pack. A matching ordinary active-pack update retains the original backup; encrypted updates require fresh Dom review as described below.

There is one current pack system. The legacy `.bbpack`, saved-profile and settings-backup interfaces and their related profile achievements are retired. Previously saved private files remain on disk; this change does not delete or silently convert them. Internal application-recovery backups remain supported and are never portable.

## Optional encrypted PayPal attachment

In Dom mode, include Tributes in a draft and choose **Add encrypted merchant details**.
This explicitly snapshots the current merchant client ID, secret, Sandbox/Live environment,
and optional PayPal-hosted fallback link. Capturing or refreshing Wallet alone never includes
credentials. Use a strong unique 12–256-character passphrase, confirm it, and share it separately
from the pack. Re-exporting an existing encrypted draft does not recapture changed credentials.
Removing Wallet removes the attachment. Duplicating creates a new identity and explicitly
omits the old attachment; attach and encrypt again for the copy.

Only the attachment is encrypted; arrangement metadata, settings and images remain readable.
The attachment is `manifest.encryptedPayPal`, with exactly seven fields: integer `version: 1`,
`algorithm: AES-256-GCM`, `kdf: PBKDF2-HMAC-SHA256`, integer `iterations: 600000`, and base64
`salt`, `nonce`, `ciphertext`. Fresh random salt and nonce are 16 and 12 bytes. The AES key is
256 bits and GCM tag 128 bits. Additional authenticated data is UTF-8
`subhub-pack:2:paypal:1:<id>:<originDeviceId>` using canonical UUID strings. The plaintext is
a bounded binary record (DataOutputStream int version 1, followed by four modified-UTF strings:
environment, client ID, secret, recipient link). Ciphertext including tag is at most 8,192 bytes.
Unknown versions/fields, alternative work factors, invalid environment/URL, and oversized values
are rejected. Parsing performs only structural validation, never password derivation or PayPal calls.

The cryptographic choices follow [OWASP authenticated-encryption guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
and its [PBKDF2-HMAC-SHA256 work-factor guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
The outer integrity digest is not a signature. Knowing the passphrase does not establish the
sender's identity; confirm merchant details independently. Offline password guessing remains
possible against a stolen pack, so passphrase strength matters.

Import saves ciphertext only. Selecting Wallet for activation requires Dom access, successful
decryption off the UI thread, and explicit confirmation of the environment/masked merchant.
Dom access and the exact encrypted identity are rechecked before mutation. Deselecting Wallet
does not decrypt or change PayPal. Active arrangements with encrypted attachments cannot be
auto-updated: deactivate first, import, and explicitly unlock/activate again.

Activation re-encrypts credentials under the receiving device's Android Keystore, clears all
saved payer/setup/verification state even for the same merchant, and makes no network request.
The recipient must verify/connect and authorize their payer locally. Automatic settlement must
be switched off and any checkout finished/cancelled before activation or deactivation changes
the merchant. No automatic-settlement authorization is transferred or enabled.

The local recovery journal backs up existing **Keystore ciphertext**, not decrypted credentials.
Deactivation restores the prior local credential/recipient state, including only that installation's
original authorization; the backup is never exported. Failed rollback retains recovery state.
Passphrases are not saved in archives, drafts, previews, journals, preferences or instance state.
Secret-entry dialogs block screenshots/autofill, clear on leaving the activity, and reject stale
background results. Java/Android memory cannot provide guaranteed erasure of all temporary strings.

Encryption protects storage/transfer, not a secret from the recipient who unlocks and uses it.
The Sub app necessarily gains access to the merchant secret. Share only with trusted recipients
and rotate credentials in PayPal if that access should be revoked; deleting a pack cannot revoke
copies already shared. Orders go to the merchant behind the API credentials, while the fallback
link may identify a different PayPal recipient.
