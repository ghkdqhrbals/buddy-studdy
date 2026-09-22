# Next-update screenshot candidates

Captured from the integrated `StudyMateiOS` DEBUG UI on 2026-09-23, using the
dedicated 6.5-inch iOS 26.0 simulator. Each PNG is 1242 × 2688 pixels. The three locales
are Korean (`ko`), English (`en-US`) and Japanese (`ja`). Images are unretouched
native simulator captures. All study records, authors, activity, view/like counts
and followed topics are illustrative fixture data, not production metrics.

Proposed opening order:

1. `01-learning-result.png`: answer a question and receive feedback.
2. `02-topic-progress.png`: see progress by study/topic.
3. `03-interest-feed.png`: discover questions from followed topics.
4. `04-interest-topics.png`: choose and manage interests.

These captures include the newer Voice Tutor/canonical-record mainline integration.
The learning-result fixtures show learning records under the current account
identity rules, with dates formatted in the selected app language. The prior
2026-09-22 captures and their README remain preserved in commit `18eb97df`.
All eight English and Japanese images were visually checked for language,
loading states, clipping and layout; the final result images were rechecked after
the record-date locale fix. Korean images were reviewed separately.

The local DEBUG fixtures exercise the actual views but do not verify backend
authentication, live generation/grading, subscription persistence or production
feed ranking. Use the separate integration and device checks for those behaviors.

These candidates have not been uploaded or published. Use only with the matching
verified iOS release and deployed subscription API. They are not screenshots of
the currently distributed 1.1.0 (119) version. Existing tree, iPad and other-size
assets elsewhere in the repository were left untouched. Recheck the final
archive's UI, screenshot display set and all supported device requirements before
submission; these captures do not substitute for iPad evidence.

To recapture, build/install the Debug `StudyMateiOS` app into a dedicated booted
6.5-inch simulator, then run:

```sh
scripts/capture-growth-screenshots.sh <simulator-UDID>
```

The script launches local DEBUG fixtures and captures all twelve images. It
does not change App Store Connect, install software or set device appearance.
Use a disposable development simulator, not a physical device or a simulator
holding personal study drafts. Inspect every output after recapture.

Compare the question/feedback lead image with the existing tree lead image using
App Store Product Page Optimization after publication and sufficient traffic.
Neither this proposed order nor sample activity counts are evidence of improved
conversion, retention or chart placement. See [the growth plan](../../docs/APP_STORE_GROWTH.md).
