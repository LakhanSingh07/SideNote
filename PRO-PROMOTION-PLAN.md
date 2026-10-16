# SideNote free app -> Pro promotion plan

Owner decisions, 16 October 2026: worldwide launch, India bills in INR and all other billing regions in USD; Android is free during early access; Windows has a seven-day all-feature trial followed by paid Pro. Both apps work standalone. Sync and file transfer are extra benefits. iOS/iPadOS and macOS are future roadmap items, not current releases. Final amounts are still unapproved.

## Message and audience

Lead with the useful standalone experience: capture an idea, keep a checklist beside the current task, attach the files that belong. Show the two-device benefits as a natural next step. Windows users need not own Android. Android users need not own a PC. Ask 'Do you use a Windows PC?' before repeatedly promoting a Windows companion; a 'No / Not now' choice should suppress those contextual cards.

Use first-party product cards, not an advertising network. Do not add an ad SDK, advertising ID, cross-app tracking or silent marketing analytics just for these promotions. Never read note content or filenames to personalize an advertisement. Context may use a local event such as adding a file, not the contents of that file.

## Suggested placements

| Placement | Trigger | Message / action |
|---|---|---|
| First useful session | At least three saved notes and a later app opening, not onboarding | 'A little more room for your day. Explore what SideNote can do.' -> in-app benefit guide |
| Files screen | After first successful local attachment, when no import/transfer is running | 'Keep a reference file with its note. Have a Windows PC? See how both screens work together.' -> optional companion demo |
| Settings | Permanent small 'Explore SideNote' entry | Standalone benefits, current platforms and optional device connection; no nagging |
| Windows companion discovery | User explicitly chooses to learn about a PC | Brief real workflow demo -> pairing/setup instructions; explain trial and current requirements honestly |
| Windows trial day 5 | Trial actually started, app foreground, native signed status known | 'Two days left to explore Windows Pro.' -> plan details; no purchase without user action |
| Windows trial expiry | Verified expiry and a paid feature is requested | Explain what Pro unlocks, offer plans/restore, retain existing note access/export |
| Existing paid Windows user | Entitlement verified for the connected PC | Replace upgrade promotion with 'Make the most of Pro': useful tips, file workflow, backup |

Android's beta status is free, so it must not show an Android 'trial expiring' message or imply payment is necessary for local notes. A free beta flag is not proof the user owns Windows Pro: the paid-user suppression logic needs verified connected-PC entitlement state. Do not launch that targeting until the signal is implemented and tested.

## Frequency and controls

- One promotional card per foreground session, no more than one new impression per day and two per rolling seven days.
- Save impression/dismissal timestamps locally across restarts; normal navigation and sync polling must not count repeated impressions. Count only a card actually shown.
- 'Not now' snoozes promotional cards for seven days. A separate Settings preference can turn contextual promotions off; user-opened benefit guide stays available.
- Dismissed campaign stays dismissed for at least 30 days. Respect the longer of dismissal, snooze and global caps.
- No forced full-screen ads, startup interstitials, push campaigns, transfer interruptions, editor popups, fake countdowns, fake discounts or 'unlimited speed' claims.
- At most one card on a screen, accessible close button, readable contrast, no animation when reduce motion is enabled.

Proposed cap values are a launch design recommendation, not proven optimal conversion settings or already-installed functionality.

## Conversion flow

Useful free action -> relevant benefit card -> short in-app demo -> user-chosen next action. Android Play builds must use the billing/offer flow permitted for the user's region and the developer's actual program enrollment. Do not route Android users to Razorpay through an in-app advertisement, QR code, hidden webview, or an intermediate page to bypass Play's payment requirements. The current Android beta guide has no purchase link. A global 'Buy Pro on website' button inside the Play app is not enabled by this plan.

For an Android Pro purchase in a future paid mobile version, use verified Google Play Billing or an applicable approved regional alternative/external-offer program. Website/Windows Razorpay purchases and Android Play purchases are separate channels that can share a backend entitlement, but they cannot silently pretend to use the same store checkout. No extra purchase is requested from a verified owner solely because another device is used.

References checked: [Google Play Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en), [Payments FAQ](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en).

## Global billing behavior

Website pricing uses a visible billing-region selector: India -> INR; all other countries -> USD. Browser locale may suggest a default, but is not proof of nationality or residence. The user confirms the region; backend selects only approved Razorpay plan IDs. This is not independently verified billing residency or automatic geolocation. Do not silently change currency after a user chooses a plan. All countries receive the same product benefits; region prices may differ only through configured plans. Availability of international recurring payments must be verified on the owner's Razorpay account.

Four Razorpay plans are needed: INR monthly/yearly and USD monthly/yearly. Prices are obtained from provider metadata; incorrect currency or period blocks checkout. Neither this plan nor the code creates live plans, approves prices, enables international payments, or publishes an app. Initial INR 79/599 and USD 1.99/14.99 remain research proposals.

## Measurement and future platforms

If optional analytics is introduced later, disclose it and obtain appropriate consent. Useful aggregate events: eligible card shown, dismissed, guide opened, companion setup completed, trial started and verified purchase. Exclude note bodies, titles, filenames and file contents. Compare paid conversion and day-7 usage with a cohort receiving no promotions; also monitor dismissal and app abandonment. More ad clicks alone do not establish improvement.

Future iOS and macOS need their own native experience, sync interoperability, recovery tests and store/billing review. The website may say 'planned', without working download buttons or a promised date. Preserve the portable backup format and versioned sync protocol to make future clients possible. No Apple app, Mac binary or App Store listing has been built by this work.

## Implementation status

Implemented locally: INR/USD Razorpay catalog and metadata validation, regional website selector, standalone-first website positioning and Windows Pro benefit explanation. Planned only: Android contextual house-ad campaigns, persistent frequency controls, paid-owner suppression, consented conversion analytics and Apple/Mac clients. Existing Android beta guide remains informational and free.
