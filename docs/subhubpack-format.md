# SubHub pack format

`.subhubpack` is SubHub Studio's portable arrangement format. It is a bounded ZIP archive designed for local creation, review, sharing, and reversible activation. Schema version 1 was introduced with SubHub 0.6.0 and remains the format for ordinary account-free arrangements. Schema version 2 adds an optional passphrase-encrypted PayPal merchant attachment; older apps reject it rather than silently skipping the attachment.

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

Only sections declared by `includedSections` are required. The manifest records the format and schema versions, arrangement identity and author metadata, minimum SubHub version, included sections, optional lock groups, non-binding recommendations, per-asset SHA-256 values, and an integrity digest covering the manifest and section data.

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

The schema is an explicit allowlist. Unknown fields are discarded rather than copied into application preferences.

## Deliberately excluded

An arrangement never carries:

- PayPal access tokens, saved payer or wallet identifiers, approval/verification state, or transaction history. Merchant client ID, secret, environment and fallback recipient link may be included **only** in the opt-in encrypted attachment below, never in ordinary sections.
- Controller PIN material, permission state, Accessibility or Device Admin state, or Hardcore activation state.
- App package names, app assignments, per-app usage, or per-app allowance overrides.
- Current service state, release time, session data, statistics, achievements, ledger history, update state, or private filesystem paths.

Hardcore and service-duration fields are recommendations shown during activation. Studio never applies them automatically.

## Activation and locks

Only Dom Space can activate an arrangement. The review flow chooses sections, shows the proposed changes, writes a recovery journal, backs up affected keys, commits the new values, installs verified assets into private storage, and then records the active arrangement. An interrupted activation is rolled back at next startup.

Lock groups use stable section names rather than individual UI widgets. Locked groups stay read-only until Dom Space deactivates or replaces the arrangement. Replacing an arrangement restores the previous backup before applying the next one. Sub Space may create, import, duplicate, export, and share arrangements, but cannot activate, replace, or deactivate one.

Legacy `.bbpack` archives use their existing verifier and activation path. They remain import-only and are shown in the Studio Library; they are not silently converted to `.subhubpack`.

## Optional encrypted PayPal attachment

In Dom Space, include Wallet in a draft and choose **Encrypt current PayPal into pack**.
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
