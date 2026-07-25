# Design QA

## Comparison target

- Source visual truth:
  - `build/design-qa/records-login-v2.png`
  - `build/design-qa/statistics-login-v3.png`
  - `build/design-qa/my-study-login-v2.png`
- Rendered implementation:
  - `build/design-qa/design-refresh-records-v2.png`
  - `build/design-qa/design-refresh-statistics-v2.png`
  - `build/design-qa/design-refresh-my-studies-v2.png`
  - `build/design-qa/design-refresh-settings-v2.png`
- Combined comparison evidence:
  - `build/design-qa/qa-records-reference-vs-actual.png`
  - `build/design-qa/qa-statistics-reference-vs-actual.png`
- Viewport: iPhone simulator, 414 × 896 points.
- Source and implementation captures: 1242 × 2688 pixels at 3× density.
- Density normalization: source and implementation pairs were both downsampled to 621 × 1344 pixels and placed side by side without changing aspect ratio.
- State: light mode; source is the signed-out illustrative preview, implementation is the signed-in populated-data state.

## Findings

- No actionable P0, P1, or P2 visual differences remain.
- The signed-in screens intentionally omit the preview marketing headline and login footer so the same space can show real data. The summary-card proportions, green activity palette, card radii, metric hierarchy, and section rhythm remain consistent with the source.
- Settings had no exact source frame. It was checked against the shared visual language established by the selected previews: large grouped cards, 22-point continuous radii, secondary grouped backgrounds, green semantic accents, and clear title/value hierarchy.

## Required fidelity surfaces

- Fonts and typography: native system typography is consistent across source and implementation. Large numeric metrics, bold section headings, secondary metadata, wrapping, and truncation remain legible at the target viewport.
- Spacing and layout rhythm: 16–20 point card padding, 12–18 point section gaps, and large rounded groups match the selected preview density. Persistent tab controls remain visible and scrollable content is not trapped underneath them.
- Colors and visual tokens: system backgrounds and secondary grouped surfaces are consistent. Green is reserved for activity, progress, scores, and study icons; blue remains the native selected-navigation color.
- Image and icon fidelity: the visual target uses system icons and code-rendered activity cells rather than bespoke raster imagery. The implementation uses the same native icon language and contains no missing image assets.
- Copy and content: sample study topics now use System Design, Kafka, and Microservice Architecture. Signed-in records and statistics use real-data labels rather than preview wording.

## Focused comparison

- Native-resolution inspection was used for record metadata, score alignment, long Microservice Architecture titles, settings controls, grass-cell spacing, and tab-bar clearance.
- Separate cropped comparisons were not needed because these details remained readable in the 1242 × 2688 implementation captures.

## Comparison history

1. First implementation capture:
   - Records and statistics mock data were replaced by live refresh before capture, preventing populated-state comparison.
   - Settings menu pickers rendered only their selected values and hid the intended row labels.
   - My Study repeated the level label.
2. Fixes:
   - Froze network refresh only inside the temporary design-QA runtime.
   - Replaced picker labels with full-width menu rows.
   - Removed the duplicate My Study level text.
3. Post-fix evidence:
   - `build/design-qa/design-refresh-records-v2.png`
   - `build/design-qa/design-refresh-statistics-v2.png`
   - `build/design-qa/design-refresh-my-studies-v2.png`
   - `build/design-qa/design-refresh-settings-v2.png`

## Implementation checklist

- [x] Large signed-in statistics summary and readable activity grass.
- [x] Real weekly record summary followed by large record cards.
- [x] System Design, Kafka, and Microservice Architecture study examples.
- [x] Card-based settings with compact iCloud control at the bottom.
- [x] Light-mode iPhone viewport verification.

## Follow-up polish

- P3: Dark-mode screenshots can be added in a later visual pass.

final result: passed
