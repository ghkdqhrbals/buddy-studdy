# BuddyStudy 1.3.0 TestFlight

This candidate adds owned-question follow-ups and private custom question/answer
records to the latest 1.2 code. Voice Tutor, native AdMob, billing, discovery and
usability improvements are retained. This task requests internal TestFlight
availability only; it does not submit 1.3 for App Review or change the existing
1.2 submission.

Release status and exact build/source/deployment receipts are recorded in
[the integrated verification report](../../../docs/verification/2026-09-25-ios-130-testflight.md).
Localized testing notes are in [testflight-build-localizations.json](testflight-build-localizations.json).

## What to test

1. Open one of your graded questions. Request a follow-up, answer it, then
   request and answer the second follow-up. The whole conversation remains
   available; earlier turns cannot create another branch and a third follow-up
   is unavailable. Each generated follow-up uses one question allowance.
2. Skip a follow-up. Reopening preserves it as read-only context and ends that
   continuation. Existing original scores remain unchanged.
3. In a study room's More menu, create your own question and answer. Save both
   and reopen the resulting record. The score area displays the custom tag,
   without numeric score, AI grading or question allowance charge.
4. Leave an unfinished custom draft and reopen it. Retry a failed save and
   confirm that it creates only one record. An existing unanswered AI question
   and its answer draft must remain intact.
5. Find the custom record in Records and the selected topic's learning records.
   Custom/follow-up records stay private and do not contribute to ordinary
   score/ability/growth statistics.
6. Check the retained 1.2 Voice Tutor, ordinary questions, records and feed flows
   with your existing account.

TestFlight uses the production backend. Automated fixture checks and local
integration tests are recorded separately from live account observations.
