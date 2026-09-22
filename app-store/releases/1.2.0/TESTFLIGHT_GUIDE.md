# 1.2.0 What to Test / 테스트 가이드

Source: `4cb14b7a`, prepared 2026-09-23. This is a test plan, not a list of passed
production checks. Start after the compatible backend is deployed and the
signed 1.2.0 build has finished Apple processing. Release/TestFlight uses the
production API; purchases in TestFlight use Apple's sandbox. Use designated
test content/accounts. Preserve the permanent review account and personal drafts.

Record build number/source, device model/OS, app language, account role without
credentials, backend deployment, steps, expected/actual result and redacted
evidence for each case. Test ko/en/ja and both physical iPhone/iPad layouts.

Build **1.2.0 (121)** is already in the existing internal TestFlight group
(`IN_BETA_TESTING`, verified 2026-09-23 02:15:38 KST). Use the existing tester's
TestFlight account and select that exact build. Server group membership does
not prove which account is signed into the device or that it has installed the
candidate. Record the actual installed version/build before running cases.

The backend permits one active device session per account. A successful login
on another device revokes the prior device's session
(`AccountSessionManager.saveSession`). Verify cross-device interest persistence
sequentially, signing in on each intended test device; do not expect concurrent
sessions to remain valid. Do not run a separate CLI login against the permanent
review account while testing its candidate session.

For reviewer access, use the candidate's existing review-account session where
available. A deliberate existing-email/password login needs no verification code;
if it instead requires account creation or a code, stop and correct the review
access setup without creating an account or sending an email. Verify active
account status, required-term state and topic-subscription access in that same
session. Do not accept new terms automatically. Check paid Voice eligibility
and remaining time separately: the normal Voice status read can reconcile quota
and expired sessions, so it is not a purely observational database operation.
This guide does not authorize purchases, entitlement grants or quota resets.

| Case | Steps and expected result / 확인할 동작 |
| --- | --- |
| First study / 첫 학습 | On an account with no studies, let My Studies finish loading. Select a starter, edit its title/level, cancel, then repeat and explicitly save. No study is created on selection/cancel; no question or question-quota charge occurs on save. 기존 답변 초안이 있다면 다른 학습 생성 후에도 보존되어야 합니다. |
| Learning / 질문·채점 | Generate a question explicitly, save a draft, leave/reopen, submit, wait for asynchronous feedback, and inspect Records/topic Statistics. New questions or quiet push sync must not replace an active draft. 학습기록 날짜와 주요 레이블은 선택 언어를 따라야 합니다. |
| Interests / 관심주제 | Sign in, open Home > All Studies > Interests, add/remove topics and Save. Up to 30 topics, each within the 120 UTF-16-unit input limit; duplicates normalize. Open another session/device and verify account-owned persistence. Saving/following must not create a study or spend quota. Cancel/failing requests must preserve the appropriate saved state/editable draft. |
| Feed / 정렬·페이지 | Compare Recommended, newest, most-viewed and most-liked, with All/Following scopes and search. Recommended puts followed topics first, then engagement/freshness; it is not strict descending views. Scroll beyond 20 questions and change filters during loading. Do not see old-filter pages, private/incomplete/deleted records or blocked-author content. 팔로우가 없거나 결과가 없을 때 안내와 다음 행동이 보여야 합니다. |
| Inline follow / 바로 팔로우 | Follow/unfollow from a public question menu and confirm Interests plus feed refresh after a successful save. Sign out or change accounts during a delayed request; no old account's interests/liked items should reappear. Guests receive general recommendations and sign-in entry points for account actions. |
| Public links / 공개 링크 | Use actual production share actions for eligible QUESTION and VOICE_TUTOR records. On physical iPhone/iPad, tap links from another app with BuddyStudy cold and warm; verify the same canonical public record, saved language and draft preservation. On a device without the app, verify localized preview and App Store navigation; after installation reopen the original link. No automatic deferred link attribution is promised. |
| Share privacy / 공유 범위 | Web preview contains topic/question only, never answer, score, author identity, session transcript or recording. Make the owned record private/delete it and open the URL again: expect generic 404 without question metadata. Never publish private material just to test. 음성 세션 ID가 아니라 공개 기록 ID를 사용합니다. Crawlers/web landings must not raise app view counts. |
| Voice / 음성 튜터 | Free cannot start voice; a verified Plus/Pro account with remaining time can start using Home's phone button. Verify microphone denial/recovery, speaker/headset behavior, pause/continue and explicit end. Brief background/lock follows the active-call contract; account change/interruption/terminal failure cleans up correctly. Recording is a separate per-call choice. 완료 학습 기록만 공개할 수 있고 전체 통화/녹음은 공개되지 않아야 합니다. |
| Billing / 결제 | Profile > Membership & Billing: Free 30, Plus 300, Pro 1,000 monthly questions; paid tiers each have separate 60 voice minutes and ad-free feeds. Verify displayed price/period/renewal/legal links, restore and cancellation. Open/cancel the system sheet without purchase for presentation testing. A separately authorized sandbox transaction should verify server fulfillment, restore and correct Apple-account ownership; do not assume a new app account resets introductory eligibility. |
| UGC/account / 신고·탈퇴 | Another user's question exposes Report/Block; own content exposes private/delete. Verify block persists across refresh/session and liked pages exclude unavailable content. Delete only a disposable account; old personalized state must not return. |
| Native review / 기본 평가 | No review is required to continue or unlock anything. A newly observed grading completion in a visible active study room records engagement; historical sync and opening an already graded record do not. After at least three distinct completion days and seven elapsed days from the first completion, return to signed-in, idle active Home. A two-second cancellable delay precedes a possible system request. Leaving Home/opening a sheet must not interrupt the new activity. |

Review attempts are installation-local: once per marketing version, at least
120 days apart and no more than three in a rolling 365 days. An unshown pending
opportunity expires after one hour and clears on logout. No score, satisfaction,
positive-answer filter or reward is used. `store_review_requested` records an
attempt, not prompt display or a submitted rating. The operating system controls
display; a missing prompt is not itself a failed test. Do not ask testers to post
ratings or alter their device clock to force this flow. Automated policy/lifecycle
checks supplement, rather than prove, system prompt display in a release build.

평가 요청은 점수·만족도에 관계없이 적용되며 보상을 제공하지 않습니다. 실제
평가 작성이나 별점 선택을 테스트 완료 조건으로 삼지 마세요. 운영체제의 표시
여부와 앱의 요청 시도는 구분해야 합니다.

Capture real backend persistence/ownership/link results separately from the
local screenshot fixtures. Check the iPad's supported orientations, sheets,
keyboard, safe areas and long localized topic labels; iPhone evidence does not
establish iPad verification. Record remaining failures in the
[release checklist](README.md) before selecting the candidate for submission.
