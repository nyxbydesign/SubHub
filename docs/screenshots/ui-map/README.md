# Current UI gallery

Real Android UI captures from October 8, 2026. These use synthetic emulator data, not a personal phone or live payment account. They show development build 0.6.4 (20), based on `64ba54df`, and may differ from the latest published APK.

The capture suite covers 42 screenshots: 34 distinct page/state captures below and eight repeated foundation/navigation checks. Screenshots document appearance and navigation, not live detection accuracy, scrolling performance, or payment execution.

## Home & controller spaces

<p>
  <a href="page-map/00-sub-home.png"><img src="page-map/00-sub-home.png" alt="sub home" width="30%" /></a>
  <a href="page-map/01-dom-home.png"><img src="page-map/01-dom-home.png" alt="dom home" width="30%" /></a>
</p>

## Settings & app assignments

<p>
  <a href="page-map/00b-sub-settings.png"><img src="page-map/00b-sub-settings.png" alt="sub settings" width="30%" /></a>
  <a href="page-map/04-global-settings.png"><img src="page-map/04-global-settings.png" alt="global settings" width="30%" /></a>
  <a href="page-map/04b-app-assignments.png"><img src="page-map/04b-app-assignments.png" alt="app assignments" width="30%" /></a>
</p>

## Censor & appearance

<p>
  <a href="page-map/05-censor-settings.png"><img src="page-map/05-censor-settings.png" alt="censor settings" width="30%" /></a>
  <a href="page-map/05a-settings-appearance.png"><img src="page-map/05a-settings-appearance.png" alt="settings appearance" width="30%" /></a>
  <a href="page-map/05b-settings-detection-categories.png"><img src="page-map/05b-settings-detection-categories.png" alt="settings detection categories" width="30%" /></a>
  <a href="page-map/05c-settings-phrases-and-tools.png"><img src="page-map/05c-settings-phrases-and-tools.png" alt="settings phrases and tools" width="30%" /></a>
  <a href="page-map/07-censor-photos.png"><img src="page-map/07-censor-photos.png" alt="censor photos" width="30%" /></a>
  <a href="page-map/11-custom-images.png"><img src="page-map/11-custom-images.png" alt="custom images" width="30%" /></a>
  <a href="page-map/21-color-wheel.png"><img src="page-map/21-color-wheel.png" alt="color wheel" width="30%" /></a>
</p>

## Limits & Wallet

<p>
  <a href="page-map/02-limits.png"><img src="page-map/02-limits.png" alt="limits" width="30%" /></a>
  <a href="page-map/03-wallet.png"><img src="page-map/03-wallet.png" alt="wallet" width="30%" /></a>
  <a href="page-map/03b-wallet-rules-and-safety.png"><img src="page-map/03b-wallet-rules-and-safety.png" alt="wallet rules and safety" width="30%" /></a>
  <a href="page-map/03c-wallet-checkout-and-history.png"><img src="page-map/03c-wallet-checkout-and-history.png" alt="wallet checkout and history" width="30%" /></a>
</p>

## Atmosphere

<p>
  <a href="page-map/14-atmosphere.png"><img src="page-map/14-atmosphere.png" alt="atmosphere" width="30%" /></a>
  <a href="page-map/15-whispers.png"><img src="page-map/15-whispers.png" alt="whispers" width="30%" /></a>
  <a href="page-map/16-popup-storm.png"><img src="page-map/16-popup-storm.png" alt="popup storm" width="30%" /></a>
</p>

## Pack Maker

<p>
  <a href="page-map/00c-sub-arrangements.png"><img src="page-map/00c-sub-arrangements.png" alt="sub arrangements" width="30%" /></a>
  <a href="page-map/17-studio-library.png"><img src="page-map/17-studio-library.png" alt="studio library" width="30%" /></a>
  <a href="page-map/17b-studio-drafts.png"><img src="page-map/17b-studio-drafts.png" alt="studio drafts" width="30%" /></a>
  <a href="page-map/17c-studio-create.png"><img src="page-map/17c-studio-create.png" alt="studio create" width="30%" /></a>
  <a href="page-map/22-wizard-details.png"><img src="page-map/22-wizard-details.png" alt="wizard details" width="30%" /></a>
  <a href="page-map/23-wizard-features.png"><img src="page-map/23-wizard-features.png" alt="wizard features" width="30%" /></a>
  <a href="page-map/24-wizard-images.png"><img src="page-map/24-wizard-images.png" alt="wizard images" width="30%" /></a>
  <a href="page-map/25-wizard-review.png"><img src="page-map/25-wizard-review.png" alt="wizard review" width="30%" /></a>
  <a href="page-map/26-pack-section-editor.png"><img src="page-map/26-pack-section-editor.png" alt="pack section editor" width="30%" /></a>
</p>

## Progress & support

<p>
  <a href="page-map/09-statistics.png"><img src="page-map/09-statistics.png" alt="statistics" width="30%" /></a>
  <a href="page-map/10-achievements.png"><img src="page-map/10-achievements.png" alt="achievements" width="30%" /></a>
  <a href="page-map/08-help-safety.png"><img src="page-map/08-help-safety.png" alt="help safety" width="30%" /></a>
  <a href="page-map/18-updates.png"><img src="page-map/18-updates.png" alt="updates" width="30%" /></a>
  <a href="page-map/19-diagnostics.png"><img src="page-map/19-diagnostics.png" alt="diagnostics" width="30%" /></a>
  <a href="page-map/20-service-lock.png"><img src="page-map/20-service-lock.png" alt="service lock" width="30%" /></a>
</p>

## Capture verification

Use meaningful synthetic configurations in every future documentation pass. Limits must show one or two selected installed apps with distinct custom daily allowances, seeded and asserted by the reusable capture test. This set shows Chrome at 20 minutes, Instagram at 10 minutes, and a 45-minute shared allowance.

Both the application and instrumentation APK were built together from an exact, isolated source snapshot. The capture runner asserts named panels/dialogs, waits for asynchronous app rows and compositor settling, and scrolls to named sections. Each published image was visually reviewed. See [capture provenance](capture-provenance.json) for the source tree, emulator, dimensions, capture checks, and SHA-256 inventory.

Foundation checks: [Dom Home](page-map/foundation-01-dom-home.png), [Censor](page-map/foundation-02-censor.png), [Limits](page-map/foundation-03-limits.png), [Wallet](page-map/foundation-04-wallet.png), [Atmosphere](page-map/foundation-05-atmosphere.png), [Dom Settings](page-map/foundation-06-settings.png), [Sub Home](page-map/foundation-07-sub-home.png), and [Sub Settings](page-map/foundation-08-sub-settings.png).
