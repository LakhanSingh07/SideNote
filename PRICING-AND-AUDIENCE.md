# SideNote pricing and audience research

Research date: 16 October 2026. Public vendor pages checked directly. USD prices are kept in USD; INR recommendations below are proposed regional prices, not exchange-rate conversions. Taxes, localized store pricing and temporary promotions can differ. No live Razorpay plans or charges were created.

## Competitor evidence

| Product | Public price checked | Offer and relevance |
|---|---|---|
| [Stickies.lol](https://stickies.lol/#pricing) | $4.99 one-time launch offer, regular price shown as $14.99 | Closest screen-edge Windows competitor. Seven-day unrestricted trial, three personal PCs. Vendor says no device sync. Launch discount can change. |
| [Notezilla](https://www.conceptworld.com/Notezilla/BuyNow) | $19.95/year plus $10 first year; alternatively $29.95 one-time | Sticky notes for Windows with free mobile companions. Subscription includes continued sync; one-time plan includes one year of sync, not lifetime sync. Desktop license remains after expiry under its terms. Thirty-day trial. |
| [UpNote](https://getupnote.com/#pricing) | $1.99/month or $39.99 lifetime | Attractive full note editor, attachments and multi-device sync. Free tier includes sync with up to 50 basic notes. Important price/value benchmark for a polished notes product. |
| [Obsidian](https://obsidian.md/pricing) | Core app free; Sync $5/month or $4/month billed annually ($48/year) | Deeper knowledge-management product with encrypted cloud sync, version history and collaboration. Higher price does not justify matching it with a LAN-only companion. |
| [LocalSend](https://localsend.org/) | Free and open source | Windows/Android and other platforms; local-network transfer, arbitrary file types, no account. Strong direct substitute for the file-transfer portion of SideNote. |
| [KDE Connect](https://kdeconnect.kde.org/) | Free to use | Local device connection, files/links, notifications and controls. Windows and Android downloads are offered by the official project. Another reason file transfer alone is a weak subscription pitch. |

Pushbullet was investigated, but its official Pro page did not expose pricing to the browser tool. Third-party estimates were not used as verified current prices. Stickies.app is a collaborative wall/team product, so its per-workspace tiers were not treated as the closest personal desktop benchmark.

## Positioning conclusion

Sell a convenient daily workspace: a small screen-edge note with its checklist and actual files, available on the user's Android phone and Windows PC. Do not lead with generic file sending, which has credible free alternatives. Do not imply internet/cloud sync when the implemented transport requires the devices on the same local network. Phone and PC must reconnect to exchange changes; this is not access to an offline PC from anywhere.

Suggested headline: **Your notes and files, a little closer.** Supporting line: **Keep a note at your screen edge. Bring its checklist and files between your Android phone and Windows PC on the same network.** A short real product demonstration should show that complete workflow.

The cute visual style can help people notice the app. Reliability, readability, fast capture, recoverable notes and easy pairing are the reasons to keep and pay for it. There is no evidence here that women convert better; a gender-specific targeting claim would be speculation.

## Target audience hypotheses to validate

These are inferences from the product workflows and competitor positioning, not measured competitor demographics or a confirmed market-size estimate.

1. **Primary paid audience: solo freelancers, creators and early-career professionals using Windows, regardless of their phone OS.** Client checklist beside a browser, screenshot/reference files in the same note, phone capture back at the PC. Plausible willingness to pay when this saves repeated steps each workday.
2. **Secondary: college students who study on Windows and capture material on Android.** Assignment checklist with PDF/photo resources in one note. Useful fit and likely price sensitivity; test a student offer only if demand appears.
3. **Additional: people who prefer visual reminders and aesthetic desktop setups.** Readable pastel notes, quick capture and quiet mode. Treat these as usability preferences rather than a gender or medical segment.
4. **Poor initial fit: file-transfer-only users, Mac-only users, enterprise teams, and people needing continuous cloud sync across different networks.** File-transfer-only demand faces free substitutes. Mac-only users and enterprise/cloud requirements are outside the current supported desktop product. An iPhone owner with a Windows PC can still use standalone Windows SideNote.

Start with one useful freelancer workflow and one study workflow. Recruit Windows standalone users, Android standalone users and users of both as separate cohorts. Sync and file transfer are additional benefits; owning both platforms is not a prerequisite to product usefulness.

## Recommended test prices, not approved live prices

| Market | Monthly | Yearly | Role |
|---|---:|---:|---|
| India first launch | INR 79 | INR 599 | Suggested starting experiment; yearly equals about INR 50/month, approximately 37% below twelve monthly payments. |
| International, when supported | USD 1.99 | USD 14.99 | A later regional experiment, not an exchange-rate conversion or a confirmed payment configuration. |

Keep only one Windows Pro tier initially. Android early-access beta remains free. Windows includes seven days of all features without entering payment details; afterward Pro keeps sync/file transfer available. Existing local notes and export remain accessible. Show actual annual billing as INR 599/year, not simply INR 50/month. Make trial expiry and non-automatic charging clear.

These prices are a hypothesis, not a claim of optimal willingness to pay. The low one-time Windows competitor and free local transfer tools put real pressure on subscription value. If users repeatedly reject recurring payment despite using the product, evaluate a separate one-time desktop license later. Do not promise lifetime cloud storage or unlimited future services to sell a LAN product.

No Razorpay prices have been hardcoded or created. Once the owner chooses prices, create matching one-month and one-year Razorpay plans in test mode, verify the complete checkout, renewal/cancellation and restore flow, then configure separate live plan IDs. Public pricing must come from the configured provider. Support costs, payment costs and any applicable taxes still need a real operating budget; gross sales are not profit.

## Small validation study before scaling paid acquisition

Recruit 30-50 Windows+Android users from existing app users and relevant study/freelance communities, with permission for feedback. This is a proposed sample, not an existing user count. Ask each to pair devices, attach a real file to a note, sync both directions and return on another day. Keep the beta explicitly labeled; do not charge for a broken first experience.

Measure pairing completion, time to first successful two-way sync, completed file transfer, day-2/day-7 return, trials reaching the paywall, paid conversion, support requests and cancellation reasons. Collect optional consented feedback; do not silently add analytics. Ask what they use today and whether they would actually buy at the shown price. Record behavior and purchases rather than treating positive comments as paid demand.

If pricing is tested sequentially (for example INR 599/year versus INR 799/year), keep the offer and acquisition audience comparable and honor each user's shown price. With only 30-50 testers, directional feedback is useful but a conversion percentage is too noisy to declare an optimal price. First fix onboarding/transfer failures before interpreting refusal to pay as a price problem.

The seven-day trial, INR 79/month and INR 599/year are the recommended proposal for owner review. Domain, support identity, refund terms, Google Group/Play opt-in URLs, secure keys and live plans remain external launch configuration.


Owner clarification: Windows and Android each work standalone. Sync/file transfer are extra benefits, not the sole product or a condition for audience eligibility. See [GEOGRAPHIC-MARKET-PLAN.md](GEOGRAPHIC-MARKET-PLAN.md) for country research and pricing implications.
