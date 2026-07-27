# My Studies Deep-Tree Drill-Down Design QA

## Evidence

- Source visual truth: `/Users/ghkdqhrbals/.codex/generated_images/019f9fc1-5e63-7fc2-a971-53daf62ad043/call_Du66tXkgbfqHf399nShBUvys.png`
- Implementation — root level: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/my-studies-drilldown-root.png`
- Implementation — second-level branch: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/my-studies-drilldown-depth-2.png`
- Combined comparison: `/Users/ghkdqhrbals/.codex/visualizations/2026/07/26/019f9fc1-5e63-7fc2-a971-53daf62ad043/my-studies-drilldown-design-qa-comparison.png`
- Viewport: real iPhone mirrored at 322 × 718 px in dark appearance.
- Source pixels: 853 × 1844.
- Implementation pixels: 322 × 718 per real-device screenshot.
- CSS size and browser density: not applicable to this native SwiftUI implementation.
- Density normalization: the combined comparison scales the source to 394 × 852 and the framed real-device capture to 382 × 852. The existing iOS status bar, tab bar, and device frame are excluded from fidelity findings because they are app/runtime context absent from the concept image.
- State: authenticated `내 학습`, `mysql` expanded, drilled into `SQL 기본 문법`, with `WHERE 조건 필터링` shown as the current branch's immediate child.

## Full-view comparison evidence

The combined comparison shows the same selected interaction model in both artifacts: a compact root header, a single-line ancestor path with an `상위로` affordance, a flat list of only the current branch's immediate children, and a persistent `전체 트리 보기` exit. The implementation uses live data with one immediate child while the source mock intentionally demonstrates several siblings; this content-count difference is expected.

The implementation keeps the product's existing title, segmented control, profile/search controls, and bottom navigation. It deliberately uses the app's established compact row density rather than the concept image's taller showcase spacing.

## Focused region comparison evidence

The original-resolution second-level implementation capture was inspected for the breadcrumb, `상위로` control, active indicator, `3/10` value, pending badge, chevrons, separators, and full-tree footer. These controls remain readable at real-device scale, so an additional crop would not reveal material detail beyond the original capture.

## Findings

- No actionable P0, P1, or P2 mismatch remains.
- Fonts and typography: native San Francisco weights preserve the source hierarchy. Topic titles stay at a constant readable width regardless of depth; the path uses compact caption text with leading truncation so the current branch remains visible.
- Spacing and layout rhythm: the root, path, immediate children, and full-tree footer form one grouped surface. Depth no longer consumes horizontal space, and a branch is capped at five visible children.
- Colors and visual tokens: green is reserved for active-for-questions topics and their `x/10` values. Inactive topics remain neutral gray, while pending-question red remains a separate semantic signal.
- Image quality and asset fidelity: the screen uses existing app assets and SF Symbols only. No placeholder imagery, emoji, generated raster UI, handcrafted SVG, or approximate icon drawing was introduced.
- Copy and content: `상위로`, localized breadcrumbs, `전체 트리 보기`, and numeric `x/10` values communicate navigation and difficulty without `Lv`, `기초`, `응용`, or numeric depth labels.
- Interaction: tapping a branch replaces the child list and advances the path; a leaf retains the existing study-opening behavior; `상위로` resolves one parent at a time; the root can collapse; `전체 트리 보기` opens the existing movable and zoomable tree.

## Comparison history

- Initial pass: no P0/P1/P2 visual difference was found after normalizing the concept art and real-device frame. The main spacing difference is an intentional use of BuddyStudy's existing compact iPhone density.
- A hidden-count edge case for search results was corrected before final verification so the footer count reflects filtered results rather than the current branch's unrelated child count.
- Post-fix evidence: the visible current state is unchanged because the live branch has fewer than five items; the corrected path is covered by the same bounded-count policy used by normal branches.

## Implementation checklist

- [x] Keep indentation constant at every depth.
- [x] Show only immediate children of the current branch.
- [x] Show a single-line ancestor path and `상위로`.
- [x] Open leaf topics through the existing study route.
- [x] Keep a persistent route to the full tree.
- [x] Cap wide branches at five visible children.
- [x] Surface descendant search matches with their ancestor path.
- [x] Verify deep ancestor-path behavior with automated tests.
- [x] Verify the root and drilled-in states on a real iPhone.

## Follow-up Polish

No blocking follow-up polish remains for this scope.

final result: passed
