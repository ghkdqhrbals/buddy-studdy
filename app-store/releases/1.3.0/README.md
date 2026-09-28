# BuddyStudy 1.3.0 release artwork

This package contains six advertising-style App Store screenshots per set, with
short Korean, English and Japanese benefit copy and actual BuddyStudy native UI.
There are 10 display/locale sets and 60 final images: three locales each for
6.5-inch iPhone, 6.9-inch iPhone and 13-inch iPad, plus English 6.3-inch iPhone.

The panel order is:

1. Answer a short question and reflect with AI feedback.
2. Continue from an answer with an AI follow-up question.
3. Discover public questions through search and followed topics.
4. Grow a personal study tree.
5. Review topic-level learning progress.
6. Save an authored question and answer without grading.

## Package map

- [benchmark.md](benchmark.md): timestamped Apple Korea iPhone Education Top Free
  chart snapshot, all 10 official app links, and visual inspection of each app's
  first three screenshots. No competitor artwork is used in the final images.
- [screenshots/README.md](screenshots/README.md): final dimensions, display mapping,
  native provenance and validation scope.
- [screenshots/upload-map.json](screenshots/upload-map.json): exact ordered paths
  for all 60 final PNGs, grouped into 10 upload sets.
- [native/](native/): all 60 unmodified native iOS simulator input captures,
  including real tablet UI for iPad output.
- [screenshots/native-capture-manifest.json](screenshots/native-capture-manifest.json):
  source commits, capture conditions, dimensions and hashes.
- [screenshots/manifest.json](screenshots/manifest.json): every final PNG mapped to
  its native input, capture commit, localized copy, layout and output hash.
- [screenshots/verification.json](screenshots/verification.json): automated image,
  layout and provenance validation.
- [screenshots/runtime-refresh-verification.json](screenshots/runtime-refresh-verification.json):
  selective 20-image refresh and proof that the other 40 final PNGs are unchanged.
- [design/README.md](design/README.md): deterministic local HTML/CSS renderer,
  pinned dependencies, native capture recipe, and deliberate design choices.
- [design/visual-review.md](design/visual-review.md): all 10 contact-sheet reviews
  and the fidelity comparison against the approved composition direction.
- [design/qa/6.5-ko.png](design/qa/6.5-ko.png): Korean six-panel contact sheet;
  the remaining device/locale contact sheets are alongside it.

## Capture and release source

The final iOS integration source is
`6a5ae34fe58fc5b02bf5396d3f4ceca3bad106b4`. Forty unchanged native panels were
captured from `8ecc999d9f219fab2416566471e63f94a3228253`. The final integration
restored record-list behavior affecting follow-up and custom-question screens,
so those two fixtures were recaptured for all 10 sets. Each image's actual
capture commit is recorded explicitly; unchanged panels are not presented as
fresh captures. Native captures use the real Debug UI with illustrative fixture
content. The signed distribution archive is a separate release artifact.

The marketing renderer preserves the native screen and adds only external copy,
device framing and a sparse BuddyStudy brand accent. No Top 10 ranking, download,
testimonial, or learning-speed claim is made. The benchmark concerns screenshot
presentation, not control of Apple's ranking algorithm.

## Release receipts

App Store Connect accepted version **1.3.0 (126)** for App Review on
2026-09-28 at07:24:48 KST. Both the submission and app version are
`WAITING_FOR_REVIEW`; the existing manual-release setting is retained.
The public store does not switch to1.3.0 until approval and release.

- [Submission and final preflight](submission-receipt.json): exact selected
  binary, three metadata locales, and60 screenshot checksums/dimensions/order.
- [Signed build](signed-build-receipt.json): source commit, successful build/upload
  workflow, inspected IPA metadata and SHA256, and verified TestFlight notes.
- [Screenshot upload](screenshots/upload-receipt.json) and
  [API readback](screenshots/asset-readback.json): all10sets, six images each,
  COMPLETE state and intended order.
- [Backend and Push Admin deployment](deployment-receipt.json): successful
  separate workflows and immutable backend image digest.

## Answer-time replacement candidate preparation

A replacement 1.3.0 candidate is being prepared to show answer submission times
on Home cards and study records. Versioned `/api/v2/` responses provide the
localized relative labels; records without an answer timestamp use their creation
time. The existing Korean, English and Japanese release copy is retained with a
short addition, and [candidate TestFlight notes](testflight-build-localizations.json)
include checks for timestamp choice and app-language changes.

These files are preparation only. The build126 receipts above describe the
previous submission; they do not establish a new build, upload, backend rollout
or replacement review submission. The next candidate requires its own verified
build and submission receipts. Existing screenshots and manual release remain
unchanged.
