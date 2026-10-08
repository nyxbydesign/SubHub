# PayPal wallet settlement

For merchant-dashboard, environment, eligibility, and payer-linking steps, see
[PayPal setup](paypal-setup.md).

SubHub keeps its Wallet ledger on the device. Dom Settings selects Sandbox or Live and accepts the
matching PayPal Client ID and secret for this installation. Both values are encrypted at rest with
an Android Keystore AES-GCM key and are never compiled into the APK. An optional PayPal.Me or other
PayPal-hosted payment link remains available as a fallback.

Studio can optionally transfer that merchant client ID, secret, environment, and fallback link
inside a passphrase-encrypted arrangement attachment. See [pack format and activation safeguards](subhubpack-format.md#optional-encrypted-paypal-attachment).
This does not transfer saved payer tokens, verification, checkout state, or automatic-payment
authorization. Only trusted recipients should receive merchant credentials: encryption does not
hide the secret from the recipient after they unlock it.

The environment toggle is an authorization boundary. Changing Sandbox/Live or changing the Client
ID clears the old credentials and saved-wallet state and cancels any active checkout. The Orders
client only accepts the two compiled PayPal API hosts; there is no user-editable server origin.

For a payment, SubHub obtains an OAuth token, creates an Orders v2 order for the exact bounded EUR
settlement, and opens PayPal's approval page. On return it captures the order and accepts completion
only if the environment boundary, local settlement reference, PayPal order, currency, amount, and
capture status all match.

Before order creation, the official PayPal Android fraud-protection module collects Magnes risk
data without requesting location. Its transaction-scoped client metadata ID is attached to create
and capture as `PayPal-Client-Metadata-Id`. The collector uses the same Sandbox or Live environment
as the Orders request, and the ID is not retained as a reusable app identifier.

Create and capture use separate settlement-derived `PayPal-Request-Id` idempotency keys. Network
timeouts, HTTP 408/429, and 5xx responses receive one bounded retry; payer, validation, and other
4xx failures do not. Capture failures leave checkout pending so retry uses the same idempotency key.
Sanitized failures include PayPal's debug ID when provided.

## Saved wallet and eligibility

Dom mode can request a saved PayPal wallet without creating a charge. SubHub creates a Payment
Method Tokens v3 setup token, stores its pending identifiers under the active credential boundary,
and opens PayPal's payer-present approval page. PayPal may finish on its own HTTPS fallback page
instead of returning to Android's custom URI. Therefore the custom URI is only a fast path: whenever
Settings resumes, SubHub reads the setup token's server-side state and exchanges an approved setup
token for a permanent payment token. The Resume button checks the token first and reopens PayPal
only while payer action is still required.

The permanent payment-token and customer IDs become ready only when PayPal returns both values.
Pending and permanent identifiers are encrypted and bound to the selected environment and Client
ID. Recognized vault/account capability errors mark the feature unavailable rather than inventing
readiness.

Live vault eligibility is reviewed and enabled in PayPal's account/developer settings; it is not a
generic preflight API result. Switching to Live therefore requires Live credentials and a fresh
payer authorization even if Sandbox was already ready.

When automatic Wallet settlement is explicitly enabled and its Hardcore/timed-protection boundary
is active, an eligible balance uses the saved payment token directly. The app creates a single-step
Orders v2 request with `paypal.vault_id` and a merchant-initiated `SUBSEQUENT` /
`UNSCHEDULED_POSTPAID` stored credential. PayPal does not support multiple line items for this saved
wallet flow, so SubHub sends the bounded settlement total and retains the itemized infractions in
its local ledger. A payer-action or approval URL is treated as expired authorization: automatic
settlement pauses and asks for the wallet to be linked again instead of silently opening checkout.

Manual settlement remains payer-present. Interactive order state cannot be reused for automatic
settlement, and enabling automatic settlement clears any stale interactive checkout before a new
background attempt. The app never falls back from configured auto-pay to an unnoticed interactive
approval page.

For payment-link fallback, PayPal.Me links receive the exact EUR amount using PayPal's documented
`paypal.me/name/10.00EUR` form. The payer returns to SubHub and marks only the local ledger paid; the
app does not represent that fallback as PayPal-side verification.

The two payment methods have different targets by PayPal design. Orders API funds go to the
merchant/payee associated with the connected API credentials. A PayPal.Me URL identifies its own
hosted-link recipient and cannot be supplied as the Orders API payee, so SubHub uses that link only
when API checkout is disconnected.

Android Keystore protects credentials and tokens at rest but cannot make a merchant secret
unextractable while the app process is running. An on-device client also cannot reliably receive a
public PayPal webhook while offline or unreachable; those are deliberate tradeoffs of this local
development architecture.
