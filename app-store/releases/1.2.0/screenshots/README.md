# 1.2.0 screenshot upload candidates

These 44 PNGs are lossless RGB exports of the verified
[native DEBUG fixture captures](../../../growth-screenshots/README.md), prepared
on 2026-09-23. The app source is `4cb14b7a`; the later release-package commit does
not change the app code. File-format validation is separate from App Store
Connect's display-slot validation and release submission.

| Directory | Capture device | OS | Portrait pixels | Files |
| --- | --- | --- | --- | --- |
| `6.5` | Dedicated 6.5-inch iPhone simulator | iOS 26.0 | 1242 × 2688 | 12 |
| `6.9` | iPhone 16 Pro Max simulator | iOS 26.0 (23A343) | 1320 × 2868 | 12 |
| `ipad-13` | iPad Pro 13-inch (M4) simulator | iPadOS 26.0 (23A343) | 2064 × 2752 | 12 |
| `6.1/en-US` | iPhone 14 simulator | iOS 26.0 (23A343) | 1170 × 2532 | 4 |
| `6.3/en-US` | iPhone 16 Pro simulator | iOS 26.0 (23A343) | 1206 × 2622 | 4 |

The three primary groups contain `ko`, `en-US` and `ja`; the two additional iPhone
groups contain English only. The 6.3-inch group targets the draft's existing
English `APP_IPHONE_61` set, whose legacy enum name does not describe its size.
Every locale uses this order:

1. `01-learning-result.png`
2. `02-topic-progress.png`
3. `03-interest-feed.png`
4. `04-interest-topics.png`

Apple requires screenshots without alpha channels and specific dimensions for
each display group. See the current
[screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications).
Select the matching display group explicitly when uploading. The 1.2.0 draft's
App Store Connect API display types were confirmed as `APP_IPHONE_67` for the
6.9-inch UI slot and `APP_IPAD_PRO_3GEN_129` for the 13-inch UI slot; use
`APP_IPHONE_65` for a new 6.5-inch set. The actual existing English
`APP_IPHONE_61` image assets were verified through the API as **1206 × 2622**;
upload `6.3/en-US` to that set. Do not infer dimensions from legacy API enum names.
The 36 primary images and four `6.3/en-US` images were uploaded to the 1.2.0
draft, processed as COMPLETE, reordered and read back. The published 1.1.0
sets were not changed. [asc-upload-verification.json](asc-upload-verification.json)
records all 40 accepted assets across 10 display sets, including their remote
dimensions, source checksums and local file SHA-256 values.

The `6.1/en-US` files are retained as rendering candidates only. App Store Connect
rejected 1170 × 2532 for this specific `APP_IPHONE_61` set with
`IMAGE_INCORRECT_DIMENSIONS`; they were **not accepted or left uploaded**, and the
helper removed the failed new asset while preserving the prior four images.

Normalization used the standard macOS CoreGraphics/ImageIO PNG encoder with the
source color space. Every source alpha byte was verified as 255 before removal.
The output is 8-bit RGB PNG (PNG color type 2), with no alpha channel. No cropping,
resampling, resizing or visual-content changes were performed. The source and
output decoded RGBA pixels match exactly when decoded in the source color space,
and each source file remained byte-for-byte unchanged.

[verification.json](verification.json) records dimensions, opaque-alpha and pixel
equality assertions, the decoded RGB SHA-256, and the source/output file SHA-256
for every image. All 44 images passed these checks. Additional 6.1- and 6.3-inch
entries were appended without rewriting the first 36 exports. Native originals remain under
`app-store/growth-screenshots`.

The fixture screenshots show illustrative study/activity/feed data, not live
production metrics. Their visual QA verifies rendered UI only. These exports do
not establish backend behavior, release-archive behavior or physical iPad
verification; those remain separate release checks.
