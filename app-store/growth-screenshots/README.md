# Next-update screenshot candidates

Captured from the integrated `StudyMateiOS` DEBUG UI on 2026-09-23. Each display
group has four images per included locale. The three primary groups include
Korean (`ko`), English (`en-US`) and Japanese (`ja`); the additional 6.1- and
6.3-inch groups include English only:

| Directory | Simulator | OS | Native portrait pixels |
| --- | --- | --- | --- |
| `6.5` | Dedicated 6.5-inch iPhone | iOS 26.0 | 1242 × 2688 |
| `6.9` | iPhone 16 Pro Max | iOS 26.0 (23A343) | 1320 × 2868 |
| `ipad-13` | iPad Pro 13-inch (M4) | iPadOS 26.0 (23A343) | 2064 × 2752 |
| `6.1/en-US` | iPhone 14 | iOS 26.0 (23A343) | 1170 × 2532 |
| `6.3/en-US` | iPhone 16 Pro | iOS 26.0 (23A343) | 1206 × 2622 |

Images are unretouched native simulator captures, without resizing. The new
6.9-inch, iPad, 6.1-inch and 6.3-inch captures use light appearance and a simulator
status-bar override for 9:41 and full battery. All study records, authors, activity, view/like counts and
followed topics are illustrative fixture data, not production metrics.

Proposed opening order:

1. `01-learning-result.png`: answer a question and receive feedback.
2. `02-topic-progress.png`: see progress by study/topic.
3. `03-interest-feed.png`: discover questions from followed topics.
4. `04-interest-topics.png`: choose and manage interests.

These captures include the newer Voice Tutor/canonical-record mainline integration.
The learning-result fixtures show learning records under the current account
identity rules, with dates formatted in the selected app language. The prior
2026-09-22 captures and their README remain preserved in commit `18eb97df`.
The 6.5-inch English and Japanese images were visually checked for language,
loading states, clipping and layout; their final result images were rechecked
after the record-date locale fix. Korean images were reviewed separately.
All 24 additional 6.9-inch/iPad images were individually inspected: scores,
statistics, feed icons/counts, translated relative dates and interest controls
render completely, with no login placeholder or unintended label truncation.
The iPad interest sheet retains its normal scroll boundary at the last visible
suggestion. The iPad system language was matched to each capture language so its
status-bar date is also localized.
The four English 6.1-inch and four English 6.3-inch captures also passed individual visual QA;
compact feed metadata uses its normal ellipsis while the question, score,
statistics and interest controls remain readable.

The new display groups reuse the verified DEBUG simulator binary built at
2026-09-23 01:17:25 KST from app source `4cb14b7a`. Its
`StudyMate.debug.dylib` SHA-256 is
`3665dcde3b0fc568a8a540428d15be1b58450d3ae889f8950f68eb7b6626dd0c`.
No app rebuild or layout changes were made for these captures. The new dedicated
simulators were removed after capture to recover disk space; the existing 6.5-inch
files and other simulators were left untouched.

The local DEBUG fixtures exercise the actual views but do not verify backend
authentication, live generation/grading, subscription persistence or production
feed ranking. Use the separate integration and device checks for those behaviors.

These native originals have not been uploaded; upload status of normalized
copies is tracked separately. Use only with the matching
verified iOS release and deployed subscription API. They are not screenshots of
the currently distributed 1.1.0 (119) version. Existing tree and older iPad/other-size
assets elsewhere in the repository were left untouched. Recheck the final
archive's UI, screenshot display set and all supported device requirements before
submission. The iPad images provide simulator rendering evidence only, not
physical iPad verification.

The 1170 × 2532 `6.1/en-US` candidates passed visual QA but were rejected by the
draft's existing `APP_IPHONE_61` slot with `IMAGE_INCORRECT_DIMENSIONS`; they were
not accepted or left uploaded. Existing images in that slot were directly
verified through the App Store Connect API as 1206 × 2622. The native
`6.3/en-US` captures match that observed size; API enum names are not a reliable
substitute for the actual slot metadata.

The native PNGs include an alpha channel. Preserve them as capture evidence;
use the separately normalized [1.2.0 upload copies](../releases/1.2.0/screenshots/README.md)
for App Store Connect. The 6.9-inch and 13-inch dimensions match Apple's
[screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications).

To recapture, build/install the Debug `StudyMateiOS` app into a dedicated booted
simulator, then run:

```sh
scripts/capture-growth-screenshots.sh <simulator-UDID>
scripts/capture-growth-screenshots.sh <simulator-UDID> app-store/growth-screenshots/6.9
scripts/capture-growth-screenshots.sh <simulator-UDID> app-store/growth-screenshots/ipad-13 en
```

The script launches local DEBUG fixtures and captures all twelve images by
default; an optional third argument (`ko`, `en`, or `ja`) captures only one locale.
For iPad, set the disposable simulator's system language/region to match before
each single-locale capture, then reboot it. The captures used `AppleLanguages`
and `AppleLocale` pairs `ko-KR`/`ko_KR`, `en`/`en_US` and `ja`/`ja_JP` in the simulator's
`NSGlobalDomain`; app launch arguments alone do not localize the system status bar.
The script does not change App Store Connect, install software, set the system
language or set device appearance.
Use a disposable development simulator, not a physical device or a simulator
holding personal study drafts. Inspect every output after recapture.

Compare the question/feedback lead image with the existing tree lead image using
App Store Product Page Optimization after publication and sufficient traffic.
Neither this proposed order nor sample activity counts are evidence of improved
conversion, retention or chart placement. See [the growth plan](../../docs/APP_STORE_GROWTH.md).
