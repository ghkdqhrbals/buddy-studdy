# Guest Records Login Design QA

## Evidence

- Source visual truth: `/Users/ghkdqhrbals/.codex/generated_images/019f9777-580a-7303-a12b-d9e1d084f555/call_FpnGd9QOgodhOI1G9nsd5x6K.png`
- Implementation screenshot: `/Users/ghkdqhrbals/personal/study-mate/build/design-qa/records-login-v1-light.png`
- Dark-mode screenshot: `/Users/ghkdqhrbals/personal/study-mate/build/design-qa/records-login-v1.png`
- Statistics companion screenshot: `/Users/ghkdqhrbals/personal/study-mate/build/design-qa/statistics-login-v1-light.png`
- Side-by-side comparison: `/Users/ghkdqhrbals/personal/study-mate/build/design-qa/records-login-comparison-v1.png`
- Viewport: `414 x 896` points on the `StudyMate-6-5` iOS 26 simulator.
- Source pixels: `853 x 1844`.
- Implementation pixels: `1242 x 2688` at 3x simulator density.
- Density normalization: source resampled to `1242 x 2688`, then placed beside the unscaled implementation screenshot in a `2484 x 2688` comparison image.
- State: signed out, onboarding complete, Records tab selected, Korean, light appearance. Dark appearance and the companion Statistics tab were captured separately.

## Full-view Comparison

The selected direction and implementation both use the learning summary and faded record rows as the main value preview, followed by one high-contrast login action immediately above the tab bar. The implementation intentionally retains BuddyStudy's existing large, leading-aligned tab title instead of adopting the concept image's smaller centered title.

The implementation makes the weekly summary slightly taller than the concept so its metric labels and weekday activity remain legible at the app's existing type scale. This preserves the selected information hierarchy without changing the interaction model.

## Focused-region Comparison

A separate crop was not required. The normalized full-view comparison is `2484 x 2688`, and the weekly summary, preview rows, footer copy, CTA label/icon, and tab bar are all readable at full resolution.

## Findings

- No actionable P0, P1, or P2 differences remain.
- Fonts and typography: native San Francisco text styles preserve the app's existing title hierarchy, weights, line wrapping, and Dynamic Type behavior. The large leading title is an intentional product-system constraint.
- Spacing and layout rhythm: the summary, preview rows, divider, footer copy, CTA, and tab bar have clear vertical separation. The CTA remains visible above the tab bar while preview content scrolls independently.
- Colors and visual tokens: system background and secondary-system surfaces match the existing app in light and dark appearances. Semantic green remains limited to progress and score meaning; the login button uses maximum system contrast.
- Image quality and asset fidelity: the selected concept contains no required raster artwork. The implementation uses native SF Symbols for the arrow and existing app tab icons; no placeholder illustration or generated asset was introduced.
- Copy and content: outcome-gated labels such as `로그인하고 기록 보기` and `로그인하고 통계 보기` are removed. The CTA is simply `로그인`, with the benefit explained separately in calm supporting copy.
- Accessibility: sample rows are hidden from accessibility because they are illustrative, while the summary and login action remain meaningful. System colors retain light/dark contrast.

## Comparison History

- Initial comparison: no P0/P1/P2 issue found. The implementation preserves the selected bottom-action structure and intentionally adapts the title placement and summary height to BuddyStudy's current SwiftUI design language.
- Post-check evidence: light and dark Records captures show stable contrast and visible CTA placement; the Statistics capture shows the same action pattern with topic-progress content.

## Implementation Checklist

- [x] Preview learning value before asking for sign-in.
- [x] Keep one bottom-aligned primary login action.
- [x] Use a simple `로그인` label.
- [x] Preserve dedicated login-page navigation.
- [x] Verify light and dark appearances.
- [x] Verify the Statistics companion state.

## Follow-up Polish

- P3: Real user testing can determine whether three illustrative rows or two produce the best perceived density on smaller iPhones.

final result: passed
