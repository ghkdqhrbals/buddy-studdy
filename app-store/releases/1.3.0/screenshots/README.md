# BuddyStudy 1.3.0 marketing screenshots

60 final PNGs across 10 locale/display sets, prepared from actual integrated
native iOS screenshots. The six-panel order is learning feedback, AI follow-up,
public feed, study tree, topic progress, and a saved custom question. Marketing
headlines are localized into Korean, English, and Japanese.

| Directory | Locales | Pixels | Images | Existing ASC display type |
| --- | --- | --- | --- | --- |
| `6.5` | ko, en-US, ja | 1242 × 2688 | 18 | APP_IPHONE_65 |
| `6.9` | ko, en-US, ja | 1320 × 2868 | 18 | APP_IPHONE_67 |
| `ipad-13` | ko, en-US, ja | 2064 × 2752 | 18 | APP_IPAD_PRO_3GEN_129 |
| `6.3` | en-US | 1206 × 2622 | 6 | APP_IPHONE_61 |

The legacy enum names are mapped from this app's previously accepted screenshot
sets, not inferred from screen diagonal. [upload-map.json](upload-map.json)
contains all 60 repository-relative paths in the exact intended order. The
release owner must confirm the current live ASC set mapping and performs upload
and processing verification separately. No live upload was performed by the
renderer. The iOS integration owner audited the final source and recaptured the 20 affected follow-up/custom screens before this package was finalized. These candidates have no prototype strips or old UI substitutions.

## Provenance

Final release source: `6a5ae34fe58fc5b02bf5396d3f4ceca3bad106b4`, version 1.3.0.
The 20 follow-up/custom captures come from this commit. The other 40 unchanged
fixtures retain captures from `8ecc999d9f219fab2416566471e63f94a3228253`; each
image records its actual capture commit separately.
The native captures use the actual iOS Simulator 26.0 Debug application (build
16), with the existing synthetic App Store fixtures, Light appearance, and a
9:41 status time. The signed CI distribution build is a separate artifact selected by the release owner.
The fixtures contain illustrative study scores, dates, and public activity, not
production metrics or promises about learning outcomes. iPad retains its actual
system-locale status date. No native content, control, metric, or image region was
painted over, replaced, or fabricated by the renderer.

All 60 unchanged native input PNGs are included in [../native](../native).
[native-capture-manifest.json](native-capture-manifest.json) records source
hashes, dimensions, and capture conditions. The final image includes that native
screen, scaled uniformly within device framing, with headline and decorative
brand layers outside it. The complete device image is inside the final canvas.

[manifest.json](manifest.json) maps every final image to its native source and
records output/source hashes, copy, dimensions, layout boxes, and browser version.
[verification.json](verification.json) records automated checks for all 60 files.
[../design/visual-review.md](../design/visual-review.md) records the visual review
of all 10 contact sheets. Reproduce via [../design/README.md](../design/README.md).
