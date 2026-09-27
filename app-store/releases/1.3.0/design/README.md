# BuddyStudy 1.3.0 marketing screenshot renderer

Six panels, in order: answer and feedback, AI follow-up, public feed, study tree,
topic progress, and a saved custom question. `copy.json` owns the Korean, English,
and Japanese marketing text. The actual native app image is composited unchanged.

`composition-concept.png` is an Image Gen **layout reference only**, not an upload
asset. Its invented mock interface is never used by the renderer. The generated
transparent `learning-tree-accent.png` is decorative and appears only behind the
device on panels 1 and 5. Final interfaces come exclusively from the integrated
1.3.0 native fixture captures, including actual iPad captures for tablet sets.

## Rendering

Requirements: Node.js, Playwright, Sharp, and a local Chrome installation. The
verified dependency versions are pinned in `package.json` (Playwright 1.62.1,
Sharp 0.35.4); the original render used Node 24.19.0 and Chrome 154.0.8037.57. Set
`NODE_PATH` if those packages are installed outside the repository. Local artifact
rendering starts a fresh headless browser without a user profile and aborts HTTP
requests. No App Store Connect or production app session is used.

```sh
npm --prefix app-store/releases/1.3.0/design ci
node app-store/releases/1.3.0/design/render.cjs \
  --native-root app-store/releases/1.3.0/native \
  --release-sha ACTUAL_INTEGRATED_SOURCE_SHA
node app-store/releases/1.3.0/design/verify.cjs
```

The unmodified 60 native inputs are included in `../native/` (the default input root); their hashes and capture conditions are recorded in `../screenshots/native-capture-manifest.json`. Rendered manifests use paths relative to the 1.3.0 release directory. Native input layout: `iphone-6.5`, `iphone-6.9`, `iphone-6.3`, and `ipad-13`, then
`ko`, `en-US`, or `ja`, then fixture name (`learning-result.png`, `follow-up.png`,
`feed.png`, `study-tree.png`, `statistics.png`, `custom-question.png`). Only English
is required for 6.3. All 60 inputs are validated before the full final run. Set
`SCREENSHOT_BROWSER_CHANNEL` to override the default `chrome` channel.

`--set 6.5/ko` renders one reviewable set. After replacing selected native captures and their capture manifest, `--fixtures follow-up,custom-question` rerenders only those panels across all ten sets and merges their output/provenance into the existing complete manifest. Unaffected final PNGs remain byte-for-byte unchanged. `--prototype` explicitly uses old 1.2.0
frames, adds a visible not-for-upload strip, and writes outside the repository into `/tmp/buddystudy-marketing-prototype`.
Never upload those prototypes. The normal command writes the final 60 PNGs into
`../screenshots/`, alongside the checksum/provenance manifest. Outputs are opaque
sRGB at 1242×2688, 1320×2868, 2064×2752, or 1206×2622. The verifier also produces
10 contact sheets in `design/qa` for visual review.

## Design and deliberate implementation choices

- Two bold headline lines and one support sentence, left aligned with generous
  margins. Copy comes directly from `copy.json`, with a subtle BuddyStudy wordmark and three-color brand cue above it. No extra badges.
- Off-white `#f6f7f3` and a cobalt blue gradient alternate across the gallery.
  Ink `#13241f` and white text retain strong contrast.
- Local Apple system fonts preserve the native brand character and support all
  three scripts. Their actual computed font and size are recorded in the output
  manifest; identical rendering requires the same OS font and Chromium versions.
- Straight device frames preserve legibility. Unlike the concept’s cropped and
  partly tilted phone, the complete final native screen and its bottom actions
  remain visible. The actual captured light/dark appearance is never altered.
- Tablet framing and sizing are separate from phone framing. Native status bars,
  topic colors, content, and controls remain part of the original image.
- No rankings, downloads, testimonials, score increases, or learning-speed claims
  are invented. Follow-ups are not described as unlimited or free, and custom
  questions are explicitly described as ungraded.

The comparison in `../benchmark.md` is a timestamped visual study of all 10 apps
on Apple’s Korean iPhone Education free chart. Competitor assets stay outside the
repository and are not used in any output.

## Native capture recipe

[capture-recipe.md](capture-recipe.md) records the exact build/capture details.
`capture-native.py` is the exact portable simulator capture helper used by the iOS
integration owner. Build and install the specified source commit's `StudyMateiOS`
Debug app onto an explicitly selected, booted iOS simulator at the required
resolution, then run, for example:

```sh
python3 app-store/releases/1.3.0/design/capture-native.py \
  SIMULATOR_UDID iphone-6.5 app-store/releases/1.3.0/native --locales ko en ja
```

Use `iphone-6.9`, `ipad-13`, or `iphone-6.3` for the other directories (English
only for 6.3). `--fixtures follow-up custom-question` recaptures only those
states. The helper sets 9:41, Light appearance and the existing fixture/language
environment flags. It does not build, install, upload, or alter a real device.
After capture, record actual source commits and native hashes in the capture
manifest before rendering. The 60 original inputs are already included, so
re-rendering the marketing PNGs does not require an installed simulator app.
