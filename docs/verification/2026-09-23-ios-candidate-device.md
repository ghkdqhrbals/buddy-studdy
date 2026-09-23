# iOS 1.2.0 (121) physical-device verification

Date: 2026-09-23 (KST). Status: **partial verification**.
This records completed observations on the signed TestFlight candidate. It does
not mark the complete TestFlight guide, reviewer access, or every release check
as passed. No credentials or private answer contents are included.

## Device and candidate

- Physical device: iPhone 16 Pro (`iPhone17,1`), iOS 26.6.2 (`23G90`), as
  established by the preceding official device-details query.
- TestFlight showed **1.2.0 (121)** and changed to **Open** after installation.
  The candidate was then opened on the device.
- An independent, read-only `xcrun devicectl device info apps` query succeeded
  at **2026-09-23 15:01:26 KST / 06:01:26 UTC** and reported
  `io.github.ghkdqhrbals.StudyMate`, version `1.2.0`, bundle version `121`.
  That query did not launch the app or copy its data.

## Data preservation before replacement

The prior installed app was 1.1.0 (16). Before confirming TestFlight's replacement
dialog, its core preferences were copied to a private local directory outside
Git: `~/.codex/tmp/buddystudy-device-backups/20260923-145808`.

Both `main-preferences.plist` and
`Library/Preferences/io.github.ghkdqhrbals.StudyMate.plist` were present at
**483,911 bytes** each, parsed as valid property lists, and had identical SHA-256
digests. They contained 45 top-level keys, including one draft-related key.
A later count-only inspection of the validated `main-preferences.plist` found
an empty current `lastAnswer` and a valid `answerDraftsByRecordID` map containing
**10 entries, all 10 nonempty**. Record-specific answer drafts therefore existed
before replacement. Their contents and identifiers were not printed.
The directory uses mode 0700 and the two preferences files use mode 0600.

The whole-container copy failed on an iOS container-management file, and the
Library and individual-file copy commands timed out after delivering these
locally validated files. This is a **partial app-data backup**, not a successful
whole-container or device backup. It excludes any assurance of Keychain
preservation or restoration. No preference values or credentials were printed.

After the candidate update and interest checks, one further official read-only
copy of the same preferences file succeeded (exit 0, within its 20-second limit).
It was saved separately at
`~/.codex/tmp/buddystudy-device-backups/20260923-postcandidate-151207/main-preferences.plist`
and validated as a 482,143-byte property list. A count-only report of the parsed
comparison established **10 nonempty record drafts before and after**, with the
entire `answerDraftsByRecordID` map exactly equal, including its keys and values.
The current `lastAnswer` was empty before and after and exactly equal. This
verifies preservation of the stored answer drafts through the update and these
checks; opening each draft in the candidate UI was not tested. No draft text,
record identifier, or credential was printed, and the original backup was not
overwritten.

## Completed candidate observations

| Check | Observed result | Limit |
| --- | --- | --- |
| Existing session | The existing authenticated account remained signed in after the update. Free membership and a question allowance display of 21/30 were visible. A private comparison of its visible login identifier with the existing App Store Connect review-account identifier established that they are different accounts. | The personal session was preserved; the dedicated App Review login and paid-feature access remain unverified. No account identifier or credential is reproduced here. |
| My Studies | The existing study tree remained visible and intact. No study or tree modifications were made during this check. | Stored answer drafts were subsequently verified equal through the private preferences comparison above; opening each draft in the UI was not tested. |
| Interest editor cancellation | The initial interest count was 0. Adding one topic and choosing Cancel, then reopening the editor, still showed 0. | Confirms cancellation for this observed session. |
| Interest save | Saving `큐잉 보장 방식` succeeded, and Home displayed an interest count of 1. | Persistence across a different account/session has not been tested. |
| Following scope | Selecting Following displayed three questions matching the saved topic. | Limited to the observed topic and account. |
| Most viewed | The three displayed view counts were **33, 6, 2**, in descending order. | Confirms ordering for this observed result set. |
| Most liked control | The likes sort control could be selected and the feed rendered. All three displayed like counts were 0. | Equal counts do not prove ordering between unequal like counts. |
| Cold-launch interest persistence | BuddyStudy was force-closed through the iPhone app switcher and relaunched from TestFlight. Home still displayed interest count 1, and the interest sheet retained exactly `큐잉 보장 방식`. | Confirms persistence in this existing account on the same device. |
| Fresh-launch feed defaults | After that relaunch, the feed returned to Recommended sort and All scope. | Records the observed fresh-launch defaults, not saved filter persistence. |
| Inline unfollow and baseline restoration | The first public question's `이 주제 구독 취소` action succeeded. The interest count returned to 0 and the feed refreshed. | Restored the initial empty interest list. |
| Question allowance after interest actions | After unfollowing, Profile still displayed Free and 21/30. | No question creation was performed during the interest checks. |
| Public share preview | Sharing the same public question and opening it in Chrome on the iPhone displayed the valid `/questions/156?tl=ko` preview with question and topic only. | Does not establish an installed-app Universal Link handoff. |
| Chrome app-open action | Tapping the preview's app-open action in Chrome on the iPhone produced neither an app switch nor a prompt. | Browser-specific behavior remains unresolved; this action is not marked passed. |
| Safari app-open action | From the Safari preview for public question 156, the custom-scheme app-open button displayed a system prompt and then opened the same question in BuddyStudy. | Warm custom-scheme navigation passed for this observed question. |
| HTTPS Universal Link, warm app | A dedicated Notes test note contained `https://api.ghkdqhrbals.org/questions/156?tl=ko`. Tapping this actual HTTPS link opened the same public question 156 in BuddyStudy. | Warm installed-app Universal Link navigation passed. |
| HTTPS Universal Link, cold app | BuddyStudy was force-closed through the iPhone app switcher. Tapping the same HTTPS link in Notes launched the candidate and opened public question 156. | Cold installed-app Universal Link navigation passed. |
| Link language preservation | The app remained in Korean through these link checks. | Limited to the observed Korean-language state. |
| Free-account native advertisement | After unfollowing, the free feed displayed a native placement labeled `쿠팡 (광고)` with disclosure. The advertisement was not clicked. | Proves an actual placement was visible for this Free account, not provider-policy details or paid-account suppression. |
| Free-account Voice screen | The Profile Voice page showed Plus/Pro access as locked and no past voice history. | No microphone request, call, or purchase was initiated; paid Voice operation was not tested. |
| English presentation | English was selected and saved in Settings. Home, feed controls, Interests (including Cancel/Save and 0/30), Profile, quota-reset date, and tab labels rendered in English. | Public topic/question content retained its available source language. |
| Japanese presentation | At 16:07–16:08 KST, Japanese was selected and saved in Settings. Profile, date and quota labels, Home, and Interests with 0/30 rendered correctly in Japanese. | Topic text retained its source language; this was a presentation check, not a translation-completeness claim. |
| Korean restoration | At 16:09 KST, Korean was selected and the saved state was visible. At 16:10 KST, Home rendered in Korean with Recommended/All selected and the initial interest count of 0 retained. | The original app language and empty interest baseline were restored. |
| Empty Following scope | At approximately 16:29–16:30 KST, selecting Following with zero interests displayed the localized empty state and Interests / Browse All Topics actions. Browse All Topics returned to All / Recommended. | No subscription or study was created. |
| Topic search | At 16:35 KST, submitting `aggregate` with Return displayed one matching public-question row. At 16:36–16:37 KST, explicitly clearing the text and submitting restored the unfiltered feed, then the search bar was closed. | Closing the search bar alone retained the query; clearing the text was required to restore the baseline. This was one observed query, not exhaustive search coverage. |

The initial empty interest list has been restored. No question creation or
editing, like, deletion, publicity change, or account change was performed in
these checks. Existing studies were not deleted to force an empty-state test.
Opening the public question in the app exercised its ordinary detail/view path;
this is not a claim that those app visits left view counts unchanged.

A dedicated Notes artifact for BuddyStudy 1.2.0 (121) link verification contained
the public test URL, without a private answer. It was subsequently deleted using
Notes' normal recoverable deletion. The note count returned from 41 to 40 and
the test title was absent from the list; existing notes were unchanged. The test
did not send the link or answer content to another person.

### Membership screen

The candidate membership page visibly displayed the following monthly plans:

| Plan | Displayed allowance and benefits | Displayed price |
| --- | --- | --- |
| Free | 30 questions | Free |
| Plus | 300 questions, 60 voice minutes, ad-free feeds | First month KRW 9,900, then KRW 19,900 per month |
| Pro | 1,000 questions, 60 voice minutes, ad-free feeds | First month KRW 19,900, then KRW 39,900 per month |

Monthly auto-renewal and introductory-offer eligibility disclosures, legal and
privacy links, and a restore control were present. No subscription or restore
action was performed. These observations establish the displayed purchase
information, not transaction fulfillment, introductory-offer eligibility for
this Apple account, paid entitlement, or Voice call operation.

### Resolved input interruption

Input paused at approximately 15:24 KST after the phone switched to another app.
The user subsequently confirmed that mirroring worked. A fresh automation
session still returned `noWindowsAvailable` for coordinate clicks, while
Cmd-3, app-name search and Return successfully opened BuddyStudy. Normally
quitting and reopening the Mac iPhone Mirroring app through its UI restored
coordinate input. No authentication or security setting was changed, and the
iOS app was not reinstalled. The Japanese checks and Korean restoration above
were then completed; mirroring is not a remaining blocker in this report.

### Analytics delivery and account-preserving scope

At 16:31–16:32 KST, the existing authenticated Firebase/GA4 console showed
September 23 aggregate counts of **9 `public_feed_loaded`**, **2
`topic_subscriptions_saved`**, and **1 `public_topic_follow_changed`**. These
confirm collection of the new event types, not attribution to this individual
iPhone or verification window. No events were injected and no analytics setting
was changed. See [growth measurement evidence](../APP_STORE_GROWTH.md).

The personal account was not logged out to test another account. The candidate's
logout path detaches pending answer drafts from their remote study identifiers
and purges local voice recordings; the partial preferences backup has not been
restore-tested. The earlier exact draft-map preservation result must not be
treated as permission to alter these associations. Cross-account UI behavior
remains unverified on this candidate. A read-only attempt to open the Monitoring
Users page reached its administrator sign-in screen; no reviewer lookup or
account/entitlement change was performed.

## Remaining limitations and unverified checks

- Chrome's app-open button remains a browser-specific unresolved limitation.
  Companion source, signed-IPA and live-response-header inspection found the
  expected scheme/routing and no CSP block; the reason for the observed missing
  handoff is not established. This does not negate the separately observed
  Safari custom-scheme and Notes HTTPS cold/warm successes.
- Cross-account interest isolation, first-study
  starter creation, opening each retained answer draft, learning/grading, paid Voice,
  purchase/restore, and native review behavior are not marked passed here.
- Physical-iPad verification remains omitted under the user's release-specific
  approval. Existing iPad simulator evidence does not establish a physical-iPad
  pass.

This remains partial candidate-device evidence. The complete guide, dedicated
reviewer credentials, and paid-feature operation are not marked passed.
The completed checks above cover the changed interest-feed and growth paths;
the broader release guide is not represented as an additional implementation
requirement for this task. Apple approval, manual publication, and subsequent
acquisition/ranking outcomes remain separate from implementation verification.
