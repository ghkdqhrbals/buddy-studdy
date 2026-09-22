# BuddyStudy App Store growth

Prepared 2026-09-22 for iOS. Read-only App Store Connect inspection on this date
confirmed app `6774108938`, BuddyStudy version `1.1.0`, build `119`, in
`READY_FOR_DISTRIBUTION`. The live English keyword list still contained
`flashcards`; local metadata edits have not been published. A new Apple Developer
agreement awaits acceptance by the account owner before the next release can
proceed. App Analytics inspected on the same date, for 2026-06-23 through
2026-09-20 (90 days), reported **one first-time download**, dated September 17.
Other dates displayed `-`, which is not evidence of zero downloads. Retention
and crash overviews reported insufficient data; there is no meaningful conversion
or retention estimate from this sample. This document is not evidence of chart
placement or improved acquisition.

The same App Store Connect inspection confirmed primary category Education,
secondary category Productivity, and live English subtitle `Daily Study with
Buddy`. Those categories remain appropriate. The App Info UI requires a new
app version to edit the disabled name/subtitle and related metadata; local
candidate copy has not changed the distributed listing.

Integration follow-up: this source was reconciled with the newer iOS Voice Tutor,
Plus/Pro membership and canonical-record implementation on 2026-09-23. The
September 22 observations and test counts below remain historical evidence; see
[the integration report](verification/2026-09-23-app-store-growth-integration.md)
for the current source boundary and release checks.

Release preparation follow-up on September 23: the integrated backend is
deployed, and signed iOS 1.2.0 (121) is uploaded, processed as VALID and selected
on the editable 1.2.0 draft. Korean, English and Japanese names/subtitles,
promotional text, descriptions, keywords and release notes match the source
files. Forty screenshots across 10 display sets, custom TestFlight notes and
current review notes were saved and read back. The published 1.1.0 listing has
not been replaced. Manual release and existing ratings are retained; see the
[candidate package](../app-store/releases/1.2.0/README.md) for the remaining
owner-agreement, signed-candidate device and review-access checks.

## What can be influenced

Apple describes search relevance using the app name, subtitle, keywords and
category, alongside behavior including downloads, ratings and reviews. It does
not give a Top Charts formula or weights in the linked guidance. Treat chart
appearance, search visibility and editorial featuring as separate outcomes.
No feature or metadata change guarantees a chart position.
[Apple: App Store search](https://developer.apple.com/app-store/search/)

The product hypothesis is that useful topic subscriptions help people return,
clear store copy helps relevant learners choose the app, and considerate native
review requests give experienced users an opportunity to provide feedback.
Retention is an outcome to measure, not a claimed Apple ranking coefficient.

## Metadata prepared in source

The canonical files are [App Info](../app-store/metadata/app-info-localizations.json)
and [version localizations](../app-store/metadata/version-localizations.json).

| Locale | App name | Subtitle | Keyword bytes |
| --- | --- | --- | --- |
| Korean | 버디스터디 BuddyStudy: AI 퀴즈 | 질문에 답하고 주제별 실력을 쌓아요 | 95 |
| English | BuddyStudy: AI Study & Quiz | Active Recall & Topic Progress | 91 |
| Japanese | BuddyStudy: AI学習クイズ | 回答・解説・復習で理解を深める | 92 |

The localized names preserve the BuddyStudy brand and state the app's purpose.
Copy explains the question → answer → AI
feedback → topic progress flow, Voice Tutor conversations, existing public
questions and liked collections. Plus and Pro include separate question and voice
time allowances; topic following is not a paid membership or a voice-quota action.
Keywords cover relevant uses, omit duplicated subtitle concepts and remove
the previous unsupported `flashcards` claim. These are hypotheses about audience
intent; no keyword-volume research or ranking uplift has been measured.

Apple recommends specific, accurate metadata and showing the app's main benefit
in the first screenshots. Promotional text helps explain value but is not a
search-ranking keyword field. Descriptions preserve the paid-membership and
free-feed advertising disclosures. Privacy URLs follow the integrated app
configuration; validate both privacy copy and advertising behavior against the
final archive before upload.
[Apple: Product page](https://developer.apple.com/app-store/product-page/)

The validator keeps the stricter 100 **UTF-8 byte** keyword limit documented by
App Store Connect. Names/subtitles allow 30 characters; promotional text allows
170. Version `1.1.0` is already distributed; the next update needs a `whatsNew`
field for each locale. The earlier first-release assumption in the repository's
review documents is obsolete. Attach only copy describing the verified candidate
build and deployed API to the next editable version.
[Apple: Platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)

Run these without App Store credentials or network access:

```sh
APP_STORE_METADATA_VALIDATE_ONLY=1 ruby scripts/update-app-store-metadata.rb
APP_STORE_METADATA_VALIDATE_ONLY=1 ruby scripts/update-app-store-app-info.rb
```

The readiness script also runs these checks. A valid metadata file does not prove
the app is ready for review. Default script dry runs still read App Store Connect;
only the validation mode is completely local.

## Release copy — next-update candidate

The metadata JSON now contains localized interest/share feature bullets and
`whatsNew` covering interests, sharing and first-study topic suggestions for the
next update; use those files as the single source of release
copy. They are saved on the unpublished 1.2.0 draft. Complete the exact signed
iOS candidate's subscription, account-isolation and real-device checks against
the deployed API before review submission. Release history for distributed
version `1.1.0` remains unchanged. Topic following must not be confused with
paid membership.

## App behavior and release gates

The first-study starter is an activation aid for an empty, successfully loaded
My Studies screen with no search filter. Three localized choices prefill the
existing study editor; the user can change the topic/difficulty and must save
explicitly. A custom-topic action remains available. Selection and study creation
do not generate questions, create child topics, or consume question quota.

Public-question acquisition now has a source implementation at
`https://api.ghkdqhrbals.org/questions/{numericId}?tl=ko|en|ja`. Its server landing
shows only a bounded public question/topic preview, localized install/open
actions, social preview metadata and an iOS Smart App Banner. Store identity
comes from the existing release configuration (App Store ID `6774108938`).
The page reads a dedicated projection: it never retrieves the shared answer,
grade, author identity or private voice-session data, and never increments
ranking views. The URL carries the canonical public record ID for both completed
QUESTION and VOICE_TUTOR exchanges. Voice previews keep the original question
and source language; ordinary questions may use the available translation.
No preview read enqueues translation work. Every request
rechecks public/completed/nondeleted/author-public state. Unavailable content
returns the same generic `404` page with no question metadata. HTML escaping,
restrictive CSP, no-referrer and no-store headers protect the landing.

The deployed AASA includes `/questions/*` and preserves referral links. Local
production GET checks verified the landing and AASA routes; see
[production evidence](verification/2026-09-23-personalized-feed-production.md).
The complete acquisition flow still requires an iOS release that parses the
new HTTPS route; live `1.1.0 (119)` support is not assumed.
After installation, visitors must return to the original link to open that
question. Neither automatic post-install routing nor removal of previews already
cached by external messengers is guaranteed. See [the shared-link contract](DEEPLINKS.md#공개-질문-공유-universal-link).

Local verification includes eight focused share/referral tests and two additional
anonymous HTTP/MySQL share cases. They cover translated/original preview fallback,
escaping, current visibility changes, identical unavailable responses, AASA paths
and unchanged ranking views. The actual renderer's Korean, English and Japanese
fixtures were visually inspected at a 390 × 844 browser viewport; this is not
evidence that Smart App Banners or Universal Links work on a physical iPhone.

1. Deploy the integrated topic-subscription migration as `V120`, not the old
   branch's `V99` (already occupied on the newer mainline). Topic subscriptions
   should survive relaunch and remain account-owned. The
   feed must exclude private/deleted/blocked content, paginate reliably, and
   preserve an active answer draft while preferences or background data change.
   Existing global browsing must remain available when no interests are selected.
2. The implemented native review policy records a new visible transition from
   ungraded to graded for the same question; simply opening an old result does
   not count. `SettingsStore` retains installation-local completion dates and
   attempt history. Eligibility requires completion on three distinct days and
   at least seven elapsed days since the first completion. The request waits
   until the user returns to idle Home, followed by a cancelable two-second
   delay. It allows one attempt per version, at least 120 days between attempts,
   and at most three attempts in a rolling 365-day window. The in-memory pending
   request expires after one hour and is cleared on logout. No grades, payment
   tier, incentives or satisfaction survey affect eligibility. Verify navigation,
   backgrounding, sheets and logout on a physical iPhone. A request does not
   prove that Apple displayed a prompt or received a review; presentation remains
   controlled by StoreKit.
   [Apple: Ratings and reviews](https://developer.apple.com/app-store/ratings-and-reviews/)
3. Any explicit review action should use the verified app's App Store review
   link. Sharing must remain user-initiated, use a verified public URL and expose
   no private answers or credentials. Support should be reachable independently
   of rating choices.
4. Run the required generic iOS build and physical iPhone checks. Record the
   exact build/device/OS and verify Korean, English and Japanese text. Follow
   [existing App Review requirements](APP_STORE_REVIEW_1.1.0.md), including
   physical iPad evidence if the submitted binary supports iPad.
5. Deploy backend changes through the module-specific GitHub Actions path,
   then verify the actual release candidate. Source validation never replaces
   deployment or review evidence. Do not add runtime health gates to Actions.

## Measurement and next store actions

Retain the observed baseline above before applying metadata, then compare
equivalent 14-day windows after publication when reporting volume permits.
The windows below are a team experiment plan, not Apple thresholds.

| Outcome | Evidence to retain | Decision |
| --- | --- | --- |
| Search discovery | App Analytics impressions, conversion rate and first-time downloads, filtered by source and territory | Check whether relevant traffic and acquisition increase; note Apple Ads activity separately |
| Store conversion | Product Page Optimization results for one screenshot hypothesis at a time | Apply a treatment only after Apple's reported evidence is sufficient; low traffic means inconclusive |
| Returning learners | Available App Analytics retention, active devices and sessions by release cohort | Check whether new discovery brings people who return; record reporting coverage |
| Learning value | Aggregate completed grading sessions and repeat-use counts, if available through approved internal reporting | Identify a useful learning session instead of optimizing empty feed opens |
| Feedback and quality | New ratings/reviews by territory, crashes, recurring support issues | Fix common failures and answer relevant reviews |
| Charts | Dated App Store evidence of country, iPhone chart, category, free/paid list and position | Record observed placement; absence from an inspected list is not a numeric rank |

App Analytics provides acquisition and engagement reporting; availability and
privacy coverage can differ across metrics. Do not infer individual review
completion from a local `requestReview` call, or describe a request counter as
new ratings. Do not claim per-keyword attribution without a source that actually
provides it. The read-only baseline observation above is small; no statistically
useful before/after result has been established.
[Apple: Analytics](https://developer.apple.com/app-store-connect/analytics/)

The app also defines these bounded product analytics events; verify delivery
in the configured analytics project before using them as experiment evidence.
These are not App Store Connect metrics and must not contain question text,
answers, search terms, or topic names.

| Event | Fields | Interpretation |
| --- | --- | --- |
| `public_feed_loaded` | `sort`, `scope`, `personalized` | Successful first-page or refreshed feed load; not every pagination request |
| `topic_subscriptions_saved` | `count_bucket`: `none`, `1_to_3`, `4_to_10`, `11_plus` | Successful saved preference set; not a paid subscription conversion |
| `public_feed_question_opened` | None | User opens a public question; not proof that they answered it |
| `public_question_share_opened` | None | Tap on the share action; not proof of sheet presentation, sending, installation or referral conversion |
| `public_topic_follow_changed` | `following`: `0` or `1` | Successful inline follow/unfollow save; not the topic name |
| `first_study_starter_selected` | None | Tap on a suggested first-study topic; not proof that the editor was saved or a question generated |
| `store_review_requested` | None | A StoreKit request attempt only; never count this as a shown prompt, rating or review |

Compare feed opens per successful load alongside returning use and completed
learning. These counters alone cannot establish causation or chart improvement.

For the first screenshot experiment, compare a question-and-feedback lead image
with a topic-tree lead image, holding other artwork and copy steady. Use actual
release UI in each locale. A future subscription-feed image belongs in an
experiment only after that capability is live. Product Page Optimization is
Apple's mechanism for comparing icons, screenshots and previews.
[Apple: Product Page Optimization](https://developer.apple.com/app-store/product-page-optimization/)

Forty of the 44 [native screenshot candidates](../app-store/growth-screenshots/README.md)
were accepted on the unpublished 1.2.0 draft. They show question/feedback, topic
progress, the interest feed and interest management, using illustrative fixture
data rendered by the real iOS UI. The four unused 1170-pixel images did not match
the existing legacy-named display slot and are labelled rendering evidence only.
The [draft verification record](verification/2026-09-23-app-store-connect-draft.md)
and [accepted-asset manifest](../app-store/releases/1.2.0/screenshots/asc-upload-verification.json)
record the exact files, order, dimensions and COMPLETE processing state.

Read-only checks confirmed Education/Productivity categories and the existing
eight available storefronts; those settings were preserved. Remaining work is
owner agreement acceptance, candidate-specific physical iPhone/iPad and review
access checks, review submission/approval, then manual publication. A screenshot
experiment has not been configured, and the small historical baseline cannot
establish improved acquisition or retention.

An English/Korean [featuring nomination draft](../app-store/releases/1.2.0/FEATURING_DRAFT.md)
describes the topic tree and learning flow. Launch timing and candidate evidence
remain to finalize; no nomination has been submitted. Record any later submission
separately from charts and search. Apple editors decide whether to feature a
nomination; submission is not selection.
[Apple: Featuring nominations](https://developer.apple.com/help/app-store-connect/manage-featuring-nominations/nominate-your-app-for-featuring/)
