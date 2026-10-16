# Paid launch implementation

User decisions: Android free early-access beta through Google Play testing; Windows website distribution with a 7-day all-feature trial, then monthly/yearly Windows Pro including sync and file transfer. Razorpay is the selected provider; owner confirms their Razorpay account is active. Local notes and data export remain accessible without Pro.

## Work in progress

- [ ] Encrypted LAN transport, pinned identity, secure QR pairing and peer revocation.
- [ ] Multi-device discovery, saved-device selection, IP-change recovery and truthful status.
- [ ] Trash/restore, complete notes-and-files backups, visible conflict review.
- [ ] Readable/compact dock modes, meaningful badges, keyboard navigation, templates and reduced motion.
- [ ] Shared Files UX: search/filter, thumbnails, selection and desktop file drop.
- [ ] Verified Razorpay Windows subscriptions, restore, 7-day server trial and expiry handling.
- [x] Android early-access mode: no Android paywall or purchase buttons.
- [ ] Production signing configuration, release builds, safe update flow and consistent versions.
- [ ] Privacy/support drafts, diagnostics and expanded tests.

## External launch dependencies

Production Windows subscriptions require configured Razorpay plans/prices, securely supplied API keys/webhook secret, production backend/domain, and signing credentials. The owner has Play Console and an active Razorpay account; keys and plan IDs have not been configured. Android beta has no purchase requirement. Group and opt-in URLs are needed for the Play testing entry point. These are not supplied by the workspace. No live charge, app-store submission or public deployment is implied by local implementation.

Physical testing covers the available phone and PC; wider device, sleep/reboot and multi-gigabyte tests will be recorded separately from unit tests. Items are checked only after implementation and relevant verification.

## Pricing research

See [PRICING-AND-AUDIENCE.md](PRICING-AND-AUDIENCE.md). Suggested test pricing is INR 79/month and INR 599/year for India; prices are a proposal, not approved or configured Razorpay plans. The owner requested competitor research before selecting prices.

## Worldwide launch and own-product promotion

Owner confirms worldwide launch; India INR, other billing regions USD. Regional Razorpay catalog/website selection implemented locally; final amounts and four provider plan IDs remain unset. Both clients work standalone; sync/files are additional benefits. Android house-ad placements/caps and future Apple/Mac work are specified in [PRO-PROMOTION-PLAN.md](PRO-PROMOTION-PLAN.md), not yet implemented.
