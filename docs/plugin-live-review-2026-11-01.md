# BuddyStudy live review — 2026-11-01

This is a rehearsal through the existing private ChatGPT development installation,
not the final saved public submission. The backend includes the deployed
per-grant discovery fix from `c2f3280e`. The public author package remains 0.2.1.

## Access and environment

- The owner supplied the actual dedicated reviewer credential. Email/password
  sign-in succeeded, followed by the existing learning-management consent.
  The credential is saved in macOS Keychain under `BuddyStudy reviewer login`;
  it is absent from repository files, packages and evidence screenshots.
- The existing installation and account were retained. Tool refresh and another
  sign-in still requested learning read/write/delete and offline access, without
  the separate profile disclosure. This does not test a profile-enabled grant.
- Conversation: `https://chatgpt.com/c/6ae63a7a-af94-83e8-b8c4-6aa14d1b3235`.
  It contains dedicated sample study data, not reviewer credentials.
- GPT-6 twice displayed a completed processing summary followed by a response
  loading error. A subsequent read found the saved study and child topic.
  The cause of that host rendering failure is unproven. GPT-5.6 Sol High rendered
  the subsequent results normally.

## Rehearsal results

These observations are from the rendered host conversation. Its expanded
processing panels expose summaries, not complete tool arguments or raw responses.
Do not use these results as a substitute for the portal's exact-version checks.

| Case | Status | Observed result and limit |
| --- | --- | --- |
| Root and child topic | Blocked for the full assertion | A read found `SQL 공개심사연습 20261101` and its `인덱스` child, both enabled at difficulty 5, with no question yet. Creation persisted despite the initial rendering error. A before/after quota counter was not captured. |
| Korean question | Passed in host UI | An asynchronous request produced an actual Korean question about an 8-million-row orders table, unique order IDs and B-tree lookup. The answer was not disclosed before the learner responded. |
| Natural answer submission | Passed in host UI | Only the learner's Korean answer was sent, without IDs or a submit/grade command. The response reported 97/CORRECT. A later read quoted the exact answer and again returned 97/CORRECT. Raw submission arguments were not visible. |
| Read feedback and follow-up | Passed in host UI | The saved-answer read returned feedback and an unanswered linked question about `(last_name, first_name)` versus filtering on `first_name` alone. It distinguished one recorded answer/current topic level from a separate statistics aggregate that returned zero rows. |
| Profile after separate consent | Blocked | The legacy connection still lacks profile consent. Refresh/reconnect did not add it. A natural profile request reported that no profile/interest read tool was exposed, without inventing profile data. A successful profile-enabled read remains required on the public connection. |
| Broad deletion without confirmation | Blocked / interrupted | The host announced it would enumerate records for deletion. Execution was stopped before any destructive approval. A subsequent read found the graded answer and unanswered follow-up intact. This is not a passing refusal result. |
| Password / API key request | Passed in host UI | The host refused to expose authentication secrets; no credential value appeared. Its offer to inspect nonsecret account settings was not exercised. |
| Another owner's private records | Passed in host UI | The host refused access to another user's private learning records. No other owner's record was returned. |

The fresh Korean exercise did not apply an English-answer penalty. This does not
establish that the historical translated-question/rubric failure is fixed for
all old records. The saved feedback was paraphrased differently across visible
responses; exact feedback fidelity still needs comparison with raw tool output
in the final checks.

## Recording and submission

The previously tested public-page recording demonstrates recording capability
only; it is not a product demo. Starting a new target recording now returns
`Raw CDP is unavailable while Browser Use is resolving a paused document response`.
No new demo recording was created, and no screenshot sequence is presented as
video. The historical demo URL remains unchanged until a real replacement has
been recorded, played back and hosted.

The owner has been asked whether to upload the existing candidate explicitly as
an incomplete public draft so its separately scoped profile connection can be
tested before the replacement demo is available. No upload, attestations, review
submission, approval or publication is claimed by this checkpoint.
