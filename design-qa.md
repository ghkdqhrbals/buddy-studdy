# My Studies Design QA

## Evidence

- Source visual truth: `/Users/ghkdqhrbals/.codex/generated_images/019f9fc1-5e63-7fc2-a971-53daf62ad043/call_BoVadqaixUmlW4Rdxf1Ok1SS.png`
- Implementation — My Studies list: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/my-studies-outline-implemented.png`
- Implementation — study tree: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/study-tree-numeric-active-implemented.png`
- Combined comparison: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/my-studies-design-qa-comparison.png`
- Viewport: real iPhone mirrored at 322 × 718 px, dark appearance.
- Source pixels: 1704 × 923. The source contains two conceptual iOS screens side by side.
- Implementation pixels: 322 × 718 per real-device screenshot.
- CSS size and browser density: not applicable to this native SwiftUI implementation. The combined comparison retains the source and implementation at their captured pixel sizes rather than claiming pixel-perfect normalization across differently proportioned concept artboards.
- State: authenticated real-device data, My Studies selected, `mysql` root with two visible descendants; tree detail opened for the same root.

## Full-view comparison evidence

The combined comparison shows the intended hierarchy in both surfaces: a grouped root-and-descendant outline on My Studies, then circular nodes and connecting edges in the full tree. The implementation preserves the existing app chrome and real iPhone proportions while adopting the selected outline structure and numeric difficulty treatment.

The concept mock used blue as an early active accent. The implementation intentionally uses green because the latest product direction explicitly defines green as the active-for-questions state. Inactive topics remain neutral gray. Live data has different topic names, counts, and pending-question state from the mock; those content differences are expected and were not treated as visual drift.

## Focused region comparison evidence

Separate focused crops were not needed because both individual 322 × 718 captures keep the topic labels, `x/10` values, active indicators, progress arcs, and branch guides readable at original capture resolution. The individual screenshots were inspected alongside the combined full-view comparison.

## Findings

- No actionable P0, P1, or P2 mismatch remains.
- Fonts and typography: native San Francisco hierarchy is consistent with the existing app; root labels are stronger than descendants, numeric values use monospaced digits, and the observed labels do not truncate.
- Spacing and layout rhythm: the grouped outline remains compact, descendants have clear indentation and separators, and three preview rows fit without pushing persistent navigation off-screen.
- Colors and visual tokens: active topics use semantic green in both the list and tree; inactive topics use restrained neutral gray; pending-question red remains independent from activity and difficulty.
- Image quality and asset fidelity: no new raster assets, substituted logos, emoji, handcrafted SVG, or placeholder imagery were introduced. Existing SF Symbols and native shapes remain sharp at device scale.
- Copy and content: ambiguous `Lv`, `기초`, and `응용` wording is absent. Difficulty is shown directly as `x/10`; the long-tree affordance is localized as `+ N개 주제 더 보기`.

## Comparison history

- Initial implementation capture briefly showed only the root while descendant data was still loading. After the backend study-room refresh completed, the same real-device state was recaptured with both descendants visible.
- No P0/P1/P2 visual fix was required after the loaded-state comparison. The final evidence confirms active-green styling, inactive-gray styling, numeric difficulty, clear hierarchy, and bounded preview density.

## Implementation checklist

- [x] Show root and descendants as one readable outline.
- [x] Limit the My Studies preview to three descendants.
- [x] Route remaining descendants to the full tree with a localized count.
- [x] Keep descendant search discoverable through its root study.
- [x] Replace level labels with clamped `x/10` values.
- [x] Use green only for active-for-questions topics.
- [x] Preserve pan, zoom, saved positions, and node actions in the full tree.
- [x] Verify on a real iPhone and with focused iOS policy tests.

## Follow-up polish

No blocking follow-up polish remains for this scope.

final result: passed
