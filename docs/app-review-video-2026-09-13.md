# App Review recording — 2026-09-13

## Uploaded evidence

- Actual TestFlight BuddyStudy 1.1.0 (118), physical iPhone 16 Pro,
  iOS 26.6.2 (23G90).
- Continuous recording from Home-screen app launch through the final Profile
  version display. Captured the real iPhone Mirroring display, not a simulator.
- Local file: `artifacts/app-review/2026-09-13/BuddyStudy-1.1.0-build118-iPhone16Pro-iOS26.6.2.mp4`.
- Duration: 904.133 seconds (15 minutes 4 seconds); H.264, 616 × 1344, 30 fps;
  10,917,271 bytes; no audio track.
- No cuts, speed changes or staged application screens. The purchase-sheet
  footer region containing the Apple account information is masked for privacy
  between 770 and 860 seconds. Original timing is preserved.
- MD5: `d0dadf88e7c227415c37581cdf24d9df`.
- App Review attachment: `7c22496e-2be4-43e4-a5b5-48ba8e4e1053`.
- Apple API readback: `assetDeliveryState.state = COMPLETE`, errors empty;
  filename, file size and checksum match the local artifact.
- The video is attached to the review details for App Store version 1.1.0.
  Submission `26e49ef8-1a93-41aa-bc2e-6298f6d1d5f5` was submitted at the user's
  explicit request on 2026-09-13 at 21:05:39 KST. Apple API readback and the web
  UI confirm WAITING_FOR_REVIEW. Release mode remains MANUAL.

## Actual walkthrough

1. Registered a disposable account with real email verification, accepted only
   required terms, opened account settings, then deleted that account.
2. Logged into the existing Apple review account. That account was preserved.
3. Opened Redis study, answered an existing question, submitted it, received
   98/100 with feedback, and inspected the saved record.
4. Opened statistics, returned to the study, and generated a new question.
5. Opened public study content, reported a question, and blocked its author.
6. Compared Plus and Pro subscription information, including first-month and
   renewal prices, legal links and restore control. Opened the real TestFlight
   purchase sheet and cancelled it without confirming a purchase.
7. Inspected notification settings and the app version on Profile.

The author block was removed after filming through the authenticated account API;
the response confirmed `blocked: false`. The report remains a review walkthrough
test report. A pre-existing answer draft was preserved without editing or
submitting it. No permanent review account was deleted and no purchase was made.

## Readiness limits and follow-up

- Physical-iPad verification is still missing. Do not fill the final submission
  templates with unverified device claims or bypass their validation.
- Notification permission had already been decided on this device; the recording
  does not show a fresh system permission prompt. Comment-author blocking was
  not separately demonstrated.
- Statistics displayed no records after the newly graded answer appeared in
  Records; refreshing did not resolve the discrepancy during filming.
- The newly generated Redis question displayed an unrelated pre-existing answer
  draft. Investigate account/question draft isolation without deleting drafts.
- The app advertised Plus at ₩9,900 for the first month, while the TestFlight
  purchase sheet displayed ₩19,900/month. Check introductory-offer eligibility
  and its presentation before claiming the discount flow is fully verified.
- The disposable account initially displayed Plus entitlement. Investigate
  entitlement attribution in the existing TestFlight receipt context; the
  recording alone does not establish the cause.

The user explicitly requested final submission after receiving the outstanding
verification issues. The app, Plus, Pro and membership subscription group were
submitted together. These observations remain unresolved; submission does not
mean they were fixed. The submitted notes explicitly identify the physical
iPhone evidence and do not claim physical-iPad testing.

The outdated live review notes were replaced with the current membership names,
allowances, voice eligibility, introductory prices, video/device evidence and
privacy links. A credential-free API readback is saved in
`app-store/metadata/submitted-review-notes-2026-09-13.txt`. The browser submit
button remained disabled without an explanation; the official submission API
accepted the authorized request and returned WAITING_FOR_REVIEW. A subsequent
GET and browser reload confirmed the submitted state and MANUAL release mode.
