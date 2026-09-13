# Free → Plus → Pro and App Review preparation

The user corrected the final public plan order to Free → Plus → Pro.
Product IDs and server tier codes remain stable for transaction continuity:

| Public name | Existing code | Product suffix | First month (Korea) | Renewal | Monthly voice |
| --- | --- | --- | ---: | ---: | ---: |
| Free | TIER1 | — | Free | Free | Unavailable |
| Plus | TIER2 | tier2.monthly | ₩9,900 | ₩19,900 | 60 min |
| Pro | TIER3 | tier3.monthly | ₩19,900 | ₩39,900 | 60 min |

The user explicitly confirmed Free voice access remains unavailable. Existing
server plan eligibility denies Free even if a positive administrative time
override exists. Neither app-side branding nor an admin number grants entitlement.
Question allowances remain 30 / 300 / 1,000. Referral grants continue to grant the
same TIER2 entitlement, now called Plus. Apple group ordering remains level 2 for
Plus and level 1 for Pro; the one-month introductory discount is group-wide.

App changes cover all three supported languages, plan pickers, membership status,
purchase action labels, billing history, referral copy and voice upgrade notices.
App Store localization and reference names are updated to Plus / Pro. Administrator
user, tier, order and advertising displays use the same names while API writes
retain tier codes. The Free plan voice allowance input is fixed at zero and
identifies voice access as unavailable.

Verification: generic iOS Debug build passed; three focused tests passed on the
physical iPhone 16 Pro (localized public names, membership fixtures, and Japanese
billing labels). Admin Vite production build passed. All 143 administrator tests passed. All 89 backend voice service/preview tests
passed, including Free session and preview denial even with an administrative
allowance override. Backend infrastructure compilation also passed.

App Review 1.1.0 has an existing unresolved Guideline 2.1 information request.
Apple explicitly requires a physical-device recording of the core flows and an
accurate list of tested device/OS versions. Repository review-note/video fields
are still placeholders; they must not be replaced with fabricated evidence.
Release remains manual after approval. A TestFlight upload/build selection alone
must not be reported as successful submission to Apple's review queue.

Apple setup readback confirmed Korean, English and Japanese Plus/Pro product
localizations, unchanged product IDs/group levels, Korean first-month offers and
renewal prices. App Info, age rating, and version metadata synchronized. The old
unresolved submission was canceled to unlock its items; the replacement draft
`26e49ef8-1a93-41aa-bc2e-6298f6d1d5f5` contains the app version, both
subscription versions, and the subscription group version. This draft has not
been submitted and does not resolve Apple's outstanding request for evidence.
