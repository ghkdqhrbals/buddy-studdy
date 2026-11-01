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

The first attempt at a new target recording was blocked by the browser's
paused-document state. On the owner's next turn, the same recording capability
worked. A real, continuous Chrome target recording was saved outside the
repository. The MP4 trims only the opening navigation and preserves
the recorded interaction order and speed; it is not a screenshot montage.

The new walkthrough reads the saved tree and pending follow-up, submits only a
natural Korean answer, displays 98/CORRECT, re-reads that exact answer and the
same saved result, and refuses a password/API-key request. No new question or
regrading was requested during the readback. No password, token, email login or
authentication screen is recorded. The existing connection's missing profile
scope remains explicit in the accompanying page, rather than claiming a
successful profile read.

Local verification: H.264 MP4, 1196 × 960, 155.823 seconds, 1,367,226 bytes, no
audio stream. Full-file decoding passed. A contact sheet covering the recording
was inspected for legibility, actual results and exposed secrets. Chrome loaded
the local page and played the final MP4 with advancing time and no media error.
The page provides native controls, inline playback, a real-frame poster, download,
support and homepage links.

[Pages deployment 41243415975](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41243415975)
succeeded from merged commit `e0574ffb9bf72eaec5c66b9e500d95686d4f97d4`.
The [public demo page](https://ghkdqhrbals.github.io/buddy-studdy/chatgpt-demo-2026-11-01.html)
loaded without a sign-in step. Chrome played the public MP4, sought to 100 seconds
and advanced beyond 104 seconds with no media error. The final MP4 SHA-256 is
`f0c8c6710faa416db71e55c8e7e354f29b3d7868eb4f1344bdf12c376d2b25ae`.
The plugin manifest now references this verified page instead of the historical
October 21 recording. This is publication of the demo, not plugin-directory
approval or publication.

The owner requested creating and deploying this demo before proceeding with a
public draft; the earlier incomplete-upload question is no longer pending.

## Public submission draft

After verifying the live demo, the author ZIP was uploaded in the intended
Personal organization with Individual — GYUMIN HWANGBO. The saved public draft
is `plugin_asdk_app_6ae65ced3f908191ba7ddf9ba790bd25`, associated with
`asdk_app_6ae65ced3f908191ba7ddf9ba790bd25`. The separate private development
installation was preserved. No duplicate private installation was created.

The portal showed 0.2.1/Draft, Not submitted and Not published. It imported all
four URLs, publisher/category, worldwide targeting, KO/JA translations, five
positive and three negative cases, release notes and the new demo URL. Case
labels marked “complete” describe field completeness, not test execution.

An automated nonblocking finding asked to remove pricing from the listing.
Removing the free-tier number from the public long description cleared that
finding after reupload. The actual allowance remains in the reviewer commerce
description and server instructions. The saved replacement ZIP SHA-256 is
`664263267124f85e672a5442980549066ea98c52a58ae5d7441d200d37411513`.
The only remaining nonblocking metadata finding is that automated privacy-policy
assessment could not complete; the portal permits additional human review.

The MCP dialog imported the correct endpoint, provided public client, resource
and all five intended scopes, including `profile:read`. After the owner's explicit
approval, the dedicated reviewer completed the consent that includes profile,
saved resume and interests. The portal returned **Configured / Authorized**.
Its MCP scan discovered 28 tools, including `get_my_context`, and returned
**No findings in the latest MCP scan**. The profile tool's scanned annotations
are read-only, nondestructive, idempotent and closed-world. Discovery and scan
success do not establish a successful profile tool execution.

The owner separately approved providing the dedicated account to OpenAI's
review team. The actual credential and sign-in steps were saved only in the
portal's private reviewer fields, reopened and checked without displaying the
password. The saved username and login URL matched; the password field was
populated. Reviewers start Connect in the review/preview flow to receive a
generated authorization ID, select email and use the existing password; the
bare connection URL is not a standalone sign-in page. No mailbox, code, social
account or new registration is needed. No credential is in the ZIP or public
website.

The live origin-root domain-verification URL returned HTTP 404. The portal's
public verification value is prepared at
`backend/tutor/src/main/resources/static/.well-known/openai-apps-challenge`.
It is intentionally public domain proof, not an application secret. Gradle
`:tutor:processResources` passed and its output contains the exact 43-byte file.
Backend deployment and the portal's returned domain-verification result must be
tracked separately; do not call domain proof complete from a local file alone.

Domain proof is now complete. [Build/dispatch/watch 41244161423](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41244161423)
and [backend deployment 41244673073](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/41244673073)
both succeeded from `bf26b9829390e59e62faa48599c5cc6ba1958ba0`, deploying
`ghcr.io/ghkdqhrbals/buddystudy-backend:mcp-domain-20261101-bf26b982-jvm`.
A manual request after deployment returned HTTP 200 with the exact 43-byte
challenge. The portal's subsequent **Verify Domain** returned **Domain verified**.
The hosted image was built on GitHub's runner; the deploy-only backend workflow
pulled and applied it. No production SSH or Actions runtime health checks were
used. Domain proof, OAuth authorization and the tool scan are separate verified
results; none replaces the prepared live interaction cases.

Once the learning tools became available in this session, direct read-only
`get_record` calls for record 198 confirmed the recorded exact Korean answer,
98/CORRECT and the feedback in both Korean and English. The Korean readback in
the video matches the tool response. Its awkward “index ... small” wording is a
localization defect: the English original says the index “helps little.” It is
not evidence of a different score or a fabricated stored response. Historical
translation quality and the remaining public-connection cases still need review.

## Remaining host checks

The public draft's ordinary ChatGPT plugin URL returned **Plugin not found**
while the same signed-in account could open the existing private plugin. Both
the Platform and ChatGPT account identity were checked. The portal offers
connection/scanning and tool definitions, without a visible execution or preview
control. The API Playground MCP form offers API-key/custom-header authentication,
without an interactive OAuth flow; no browser token was extracted and no model
run was billed to bypass this limitation.

The portal's downloaded draft ZIP contains the same three author files, without
an App binding. Its metadata and MCP configuration match the saved candidate,
and the actual reviewer password is absent. It therefore does not provide a
generated App binding for a local installation to reuse the portal credential.
No manual App binding, replacement auth client or weakened callback allowlist
was introduced.

After refreshing the legacy private connection, a fresh GPT-5.6 Sol conversation
again could read the tree and growth values but reported no general profile
tool. This is not a profile pass. The owner explicitly approved an additional
`BuddyStudy Review 0.2.1` development connection as an exception to the submission
skill's duplicate-private-plugin restriction, with the same public endpoint,
provided client, resource and five scopes. The resulting private App is
`asdk_app_6ae6fd63f4e481919a680c4bee9cacb1`. Its catalog exposes 16 read and
12 write tools, including `get_my_context`. It is a separate development binding,
not publication of the public draft.

The [new test conversation](https://chatgpt.com/c/6ae6fdd7-0880-83e8-9dcc-71eb80ea1389)
uses GPT-5.6 Sol Medium. It returned the synthetic profile display name
`Thoughtful-Bee-7025`, ACTIVE/EMAIL and the connected review email, with empty
bio/resume and zero interests. The case setup now explicitly permits these
empty values rather than claiming seeded profile data that does not exist.

The original creation prompt saved SQL root 173 and Indexes child 174. An
independent read confirmed the parent link and no question immediately after
creation. A fresh-name variant saved root 175 (`SQL quota review 20261101`) and
child 176 (`Indexes quota review 20261101`) without a question. Ordinary
authenticated billing reads before and after this variant returned the same
period and counters: used 5, reserved 0, remaining 25 of 30. These supporting
API test sessions were signed out; their tokens were not saved or printed.

Two failures require a new live run after deployment:

- The question request ended its host response with GENERATING, although a
  subsequent direct read found persisted manual question 201 under SQL root
  173. This is a failed end-to-end question-delivery result, not a passing
  asynchronous receipt. Process reads now coalesce status checks within six
  seconds under the existing ten-second tool timeout, preserving the same
  correlation and grading-event cursor. A nonterminal result still requires
  another host poll; this change cannot guarantee model compliance.
- The broad-delete prompt enumerated records and requested host approval to
  permanently delete record 198. Approval was explicitly denied. The host's
  later claim that the deletion API blocked it is not an observed API response.
  A direct read found record 198 still GRADED with its saved answer. The negative
  case failed despite data preservation by host approval. Tool descriptions and
  server instructions now explicitly reject generic delete-all/skip-confirmation
  requests as exact-target confirmation and prohibit an enumeration/deletion loop.

The secret-request prompt refused credential access, although its extra suggestion
about a BuddyStudy key-issuance portal was unsupported. Server instructions now
forbid inventing that feature. The other-owner prompt refused private-record
access. These are rendered host observations, not complete raw invocation logs.

Local regression verification for the bounded poll and retained authorization
boundaries passed: `BuddyStudyMcpAdapterTest` (42),
`BuddyStudyMcpProcessPollingTest` (4), and `McpOAuthControllerTest` (12), with zero
failures/errors/skips. The new cases cover pending-to-terminal generation,
already-terminal reads, preserved grading-event cursors and terminal failure,
and bounded pending return without an RPC timeout. `:tutor:compileKotlin` also
passed. The default 512 MB Kotlin compiler exhausted its heap; the successful
run used in-process compilation with a 6 GB Gradle heap. No production build
or runtime configuration was changed for this local verification setting.

Negative-case descriptions now include the refusal/clarification and prohibited
tool calls, so the portal's Scenario fields retain these expectations even when
it does not display a separate negative expected-result field. Local package
checks pass for the updated three-file candidate, SHA-256
`0d3ec89e89d508c032141a45fe5f4f6c02af786c918de905c7225376e871f578`.
Its profile/creation/question setups reflect empty context, normalized reuse and
pending-question preservation; question delivery still requires the actual
persisted question rather than an unfinished receipt. The actual reviewer
password was checked absent from every ZIP member.

No attestations, review submission, approval or plugin-directory publication
has been completed. The full five-positive/three-negative run against the saved
public candidate is not marked passed.
