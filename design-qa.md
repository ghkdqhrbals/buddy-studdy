# Production integration verification · 2026-10-31

80 focused server tests passed after integration. The compact chooser and social
authentication were integrated onto production
`76cfae70`, preserving the current password login, required-terms stage and the
separately consented learning/profile scopes. The older read-only branch was not
deployed over newer production features. Local browser fixture verification at
`http://127.0.0.1:8797` confirms social callback → required terms → exact scoped
consent and one-time-result removal from the URL, with no console errors.
Evidence: `/Users/ghkdqhrbals/.codex/visualizations/2026/10/31/01a19155-eeb3-7af0-8a26-9142c1a18373/integrated-oauth-scoped-consent.png`.

final result: passed

---

# BuddyStudy OAuth login design QA · 2026-10-31

final result: passed

## Source and rendered evidence

- Source: `/var/folders/5q/l5nc5zhd1wq4w7m65vdr6wd40000gn/T/codex-clipboard-998947cd-d4d6-458a-a45b-0aef8b2d027a.png` (1200 × 1025 pixels; cropped native login panel).
- Implementation: `http://127.0.0.1:8796/oauth/connect.html?id=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` (local API fixture; no real accounts or provider credentials).
- Mobile screenshot: `/Users/ghkdqhrbals/.codex/visualizations/2026/10/31/01a19155-eeb3-7af0-8a26-9142c1a18373/buddy-login-mobile.png` (390 × 844 CSS/pixels, devicePixelRatio 1).
- Focused panel screenshot: `/Users/ghkdqhrbals/.codex/visualizations/2026/10/31/01a19155-eeb3-7af0-8a26-9142c1a18373/buddy-login-card.png` (390 × 334 pixels including 16px surrounding black).
- Desktop screenshot: `/Users/ghkdqhrbals/.codex/visualizations/2026/10/31/01a19155-eeb3-7af0-8a26-9142c1a18373/buddy-login-desktop.png` (1280 × 900).
- Small English consent screenshot: `/Users/ghkdqhrbals/.codex/visualizations/2026/10/31/01a19155-eeb3-7af0-8a26-9142c1a18373/buddy-login-consent-320-en.png` (320px CSS width; full scrolling page, no horizontal overflow).

## Comparison and fidelity

Reference and mobile implementation were opened together in one comparison input.
The source is a cropped native panel rather than an entire browser viewport; panel
widths were normalized conceptually (source 1110px panel to rendered 358 CSS px),
not treated as equal-density full-screen captures. The focused panel capture
makes the button/icon/paragraph geometry directly readable.

- Typography: system sans serif with native Apple fallback, 18px semibold button
  labels and 14px supporting text. The slightly larger supporting text prioritizes
  readable browser text; there is no repeated visible login heading.
- Layout: one charcoal rounded panel, three full-width rounded buttons in the
  reference order, left-aligned brand marks with centered labels. Email inputs
  appear only after choosing email; no fields clutter the first screen.
- Tokens: black page, #1e1e1e panel, white social buttons, #2c2c2e email button,
  restrained border and muted explanatory copy. Hover/focus/loading states work.
- Assets: bundled Font Awesome Apple/Google brand SVG files with attribution,
  not text glyph approximations or generated logos. Same-origin CSP permits them.
- Copy: intentionally describes profile/learning-record access through ChatGPT.
  The reference's public-question visibility text belongs to the iOS community
  login and would be inaccurate here. Language and policy links remain outside
  the compact card.

Comparison history: first visual comparison found no actionable P0/P1/P2 drift
from the requested dark-card/button treatment. Subsequent captures verified the
callback-result URL cleanup and retained the same layout. No P3 fixes are queued.

## Browser interaction checks

Browser plugin not listed; used the available Codex in-app browser through
`cua_repl` instead of installing or invoking standalone Playwright.

| Check | Result |
| --- | --- |
| Page identity / title | Passed: BuddyStudy account connection route |
| Meaningful initial content | Passed: three configured login actions |
| Error overlay / blank page | Passed: none |
| Console errors and warnings | Passed: none |
| Screenshot comparison | Passed: reference plus rendered mobile/full/focused views |
| Email interaction | Passed: chooser → email → code → invalid-code message → valid-code consent |
| Social UI interaction | Passed: Apple and Google → local callback fixture → consent |
| Browser binding UX | Passed: callback result removed from URL before login-state completion |
| Responsive / translation | Passed: 390px mobile, 1280px desktop, 320px English consent |
| Narrow-screen reachability | Passed: document scrolls to consent actions; scrollWidth = 320 |

Server tests separately verify signed JWTs, web audiences, issuer, expiry, nonce,
Apple code hash, state consumption, exact ACTIVE provider-subject ownership, and
original-cookie + one-time-result completion. Missing/wrong result cannot verify
an identity. No new native iOS login/session code was introduced.

## Remaining verification

The local fixture proves rendering and UI transitions, not Apple/Google console
configuration, SMTP delivery, real social account login, or production deployment.
Configure the dedicated Google Web application client/secret and grouped Apple
Services ID/callbacks using the plugin README, deploy through the backend workflow,
and verify real existing accounts. Unconfigured social providers are hidden.


---

# Previous design QA (retained)

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
