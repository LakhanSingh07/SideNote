# SideNote Windows trial and Razorpay subscriptions

Selected launch model: Android free early access via Play testing; Windows 7-day all-feature trial, then monthly/yearly Windows Pro for sync and file transfer. Existing local notes and export remain available. The owner confirms Razorpay is active. No real checkout has been performed by this implementation.

## Private configuration

Use Node 24 or later. Copy `.env.example` to a private file outside Git. Set `WEB_PAYMENT_PROVIDER=razorpay`, HTTPS `PUBLIC_URL`, a persistent `DATABASE_PATH`, `LICENSE_SIGNING_KEY_FILE` containing a private P-256 PEM key, and a random 32-byte hex `LICENSE_MASTER_KEY`. Preserve and back up these secrets; changing the master key makes encrypted license credentials unreadable.

Set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_INR_MONTHLY_PLAN`, `RAZORPAY_INR_YEARLY_PLAN`, `RAZORPAY_USD_MONTHLY_PLAN`, `RAZORPAY_USD_YEARLY_PLAN`, and `RAZORPAY_WEBHOOK_SECRET`. Begin with the Razorpay account's test keys and test plan IDs; live and test objects are separate. Never embed payment secrets or the signing private key in Android/Windows builds, website JavaScript, Git, or chat. Plans must be one-month and one-year recurring plans in the intended currency. Prices are fetched from Razorpay rather than typed into the page.

Run `node --env-file=<private-file> server.mjs` behind an HTTPS reverse proxy. The service binds to loopback by default. Provide persistent database storage, encrypted backups, and monitoring. Configure Razorpay subscription webhooks to `https://<domain>/v1/hooks/razorpay` with the same webhook secret. Webhook signatures are verified and entitlement state is fetched from Razorpay, not accepted from browser callbacks.

## Windows build

Set `SIDENOTE_COMMERCIAL_BUILD=1`, `SIDENOTE_API_BASE=https://<domain>`, `SIDENOTE_WEBSITE=https://<domain>`, and `SIDENOTE_LICENSE_PUBLIC_KEY` (base64 uncompressed 65-byte P-256 public key matching the backend). Internal builds deliberately label themselves as previews and do not pretend to be production trials. The trial start button requires a configured HTTPS verification service.

A trial starts once per SideNote-specific hash of the Windows installation ID, based on server time. Normal reinstall does not reset the stored seven-day deadline. The raw Windows installation ID is never sent. Signed cached access lasts at most 72 hours and never past trial/paid expiry. A modified client or replaced OS identity is outside this protection; this is not hardware attestation. Preserve the trial database to prevent accidental resets.

Android beta sets `ANDROID_FREE_BETA=true`: Android has no purchase buttons/paywall and no website-checkout steering. Sync is checked at the Windows server. Obtain the Google Group and Play opt-in URLs from the owner's Play Console; do not claim a visitor is enrolled just because they download Windows.

## Checkout and acceptance

The website creates a private checkout claim in the same tab. Razorpay hosted checkout opens from its verification page in another tab; return and press Verify payment. Only the server-confirmed active subscription returns an activation code. Keep the original verification tab and code private. `/manage.html` supports device removal and cancellation at the current cycle end. Removing a device or revoking access can leave previously signed offline access valid for up to 72 hours.

Before public launch, verify in Razorpay test mode: monthly/yearly purchase, abandoned and pending checkout, invalid signature, duplicate webhook, expiry, renewal, cancellation, activation on another PC, lost connection and restoration. Full refund/dispute access policy and operational handling need owner definition; unit tests do not constitute a real refund verification. Then repeat a controlled live transaction using live plans/keys. The service will not charge anyone without configured provider credentials.

Complete legal identity, refund policy, support address, privacy retention/deletion details, release download URLs, code signing and update signing before publishing the draft site. No price, domain, Play Group, signing key or payment secret is fabricated.

Run `node --test test/*.test.mjs` for verification tests.

## Regional catalog

The website asks for billing region: IN maps to INR, GLOBAL maps to USD. Browser locale is a default suggestion only; residency is self-declared, not verified by this implementation. Both monthly/yearly IDs must be configured for a region. The backend fetches and validates plan currency, billing period, interval and amount before displaying prices or creating checkout. Client amounts/currency/plan IDs are never used. Missing USD plans do not fall back to INR. Legacy INR plan IDs can remain in RAZORPAY_MONTHLY_PLAN/RAZORPAY_YEARLY_PLAN for restoration. Keep retired IDs configured while they have active subscribers. See the root PRO-PROMOTION-PLAN.md for the free-app promotion strategy and Play billing constraints.
