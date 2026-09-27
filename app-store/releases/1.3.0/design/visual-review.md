# Screenshot visual review

All 60 final marketing images were visually inspected in the 10 contact sheets
under `qa/` on 2026-09-28. Representative full-resolution exports were inspected
as well: English follow-up, Japanese public feed, and Korean iPad public feed.
The accepted six-panel composition concept and the latest native compositions
were viewed directly with `view_image`, alongside the automatic layout checks.

Rendering used a fresh local headless Chrome process via Playwright against the
static `screenshot.html` file. This was deterministic artifact rendering, not UI
automation of a live account/browser. The release owner retained the live browser
and App Store Connect surface. All network requests from the renderer were
blocked. The deliverable is a static screenshot series, so there is no interactive
workflow to test in this HTML template.

## Fidelity ledger

| Comparison | Evidence and result | Deliberate differences / fixes |
| --- | --- | --- |
| Copy | All 18 localized headlines and support sentences match `copy.json`. The same six-panel order is used in every set. | The release owner requested a small BuddyStudy wordmark and three-color cue after the concept; it is consistent above every heading. No extra claim badges were added. |
| Typography | Two headline lines stay inside their allocated width for all 60 exports. Native-resolution samples and contact sheets retain a clear title/support/native-screen hierarchy. | Korean, English and Japanese have explicit font sizes and line breaks; Japanese text is fitted independently. |
| Palette | Off-white/ink and cobalt/white panels alternate consistently; accent colors use the supplied BuddyStudy identity. | Actual native Light appearance is preserved, instead of the old dark reference shown in the concept. |
| Device and UI | Entire native screen stays within every canvas, with all footer actions visible on iPhone and native top tab layout on iPad. Source hashes match the capture manifest. | The concept's tilted/cropped phone was replaced with straight, fully visible framing to preserve real controls and legibility. No generated concept UI is in any final file. |
| Spacing | Headline/support blocks do not collide with the device. Common left margins and consistent device centers align the gallery. | Phone and tablet frame dimensions are independently tuned to their actual native aspect ratios. |
| Decorative assets | The three-ring accent loads correctly, remains behind the device, and appears only on panels 1 and 5. It never covers app content. | No competitor asset, testimonial, ranking, or fabricated metric is included. |
| Feature proof | First panel shows actual answer/feedback; second visible continuation; third current public feed/search/interests with no removed sorting control. Tree, topic statistics and saved ungraded question are distinct actual screens. | Study-tree nodes occupy their actual current viewport. They were not enlarged by clipping side branches or changing production UI for artwork. |
| Localization | KO/EN/JA marketing and app content match each target locale. All ten complete sets were viewed. | The iPad system status-bar calendar remains the simulator's Korean system locale; native app content is localized. This small system label was not painted over. |

No material rendering mismatch remains against the accepted composition with the
recorded intentional changes. The release owner also visually approved the
branded Korean gallery, English 6.5 gallery, Japanese 6.9 gallery, and Korean iPad
gallery. Native app records/statistics are illustrative fixture data; they do not
claim a measured product outcome or validate production/backend behavior.

## Final technical checks

`verify.cjs` checked 60 opaque sRGB PNGs, exact required dimensions, matching
source/output SHA-256 values, unique native proof for all six panels per set,
headline fit, no copy/device collision, and the complete device inside the canvas.
The copied portable native inputs also match the original capture manifest.
`verification.json` records the automated results; this file records the separate
visual review. App Store upload/processing and final binary selection remain the
release owner's independent checks.

## Final runtime preservation refresh

After the iOS integration owner restored the deployed record-list behavior in
`6a5ae34fe58fc5b02bf5396d3f4ceca3bad106b4`, all 20 follow-up/custom-question
inputs were recaptured and selectively rerendered. The other 40 output PNGs
remained byte-for-byte unchanged. The full 60-file verifier passed again, and
all 10 refreshed contact sheets were viewed again, including the newly populated
custom-record history and iPad continuation history. No new layout, locale,
legibility or crop issue was found. `runtime-refresh-verification.json` records
the before/after output hashes and confirms the 40 unchanged files. The final
manifest identifies both capture commits per image and the final release source.
