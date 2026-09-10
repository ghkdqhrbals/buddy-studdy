# Voice MCP read failure investigation — 2026-09-10

## Observed incident

The development Docker backend, MySQL and Redis were running, and the public
health endpoint responded successfully. This did not establish that the complete
voice conversation was working.

For the latest recorded call, session creation succeeded at 17:52:49 KST,
WebRTC negotiation completed at 17:52:52, and the sideband verified the realtime
configuration and all 19 tools at 17:52:55. At **17:53:58 KST**, the MCP adapter
logged `operation=list_studies errorType=java.lang.IllegalStateException`.
The conversation then described being unable to load saved studies and offered
creation as an alternative. A later read apparently recovered: the conversation
referenced saved topics. The call ended through `CLIENT_END` / `USER_ENDED` at
17:56:30, without a persisted transport failure. These observations identify an
internal read failure; they do not explain every reported voice symptom.

The original log recorded only the exception class, with no source location or
cause. Its underlying origin could not be established retrospectively. Isolated
diagnostic experiments did not reproduce the suspected database concurrency or
permission-context failures: 100 transactional MySQL page/count reads passed,
and actual suspending permission checks/handlers passed outside HTTP context
using both JDK and CGLIB proxies. These are negative experiments, not proof that
those components cannot fail. No speculative paging or authorization change was
made. Diagnostic-only test edits were removed after the experiments.

## Recovery and diagnostics

- A tool explicitly registered as read-only retries an unexpected or HTTP 5xx
  handler failure once, after 250 ms, with the same authenticated context and
  arguments. If the second attempt fails, it returns the original structured
  error. Validation, permission, HTTP 4xx and cancellation failures do not retry.
- Mutating tools never use this retry, including tools marked idempotent.
  Response serialization is outside the retry boundary. Cancellation propagates
  without being converted into an MCP error response.
- Failure diagnostics record exception and cause types plus up to eight source
  frames. They omit exception messages, throwable payloads, arguments, user
  identities and conversation content.
- The realtime instructions distinguish failed reads from empty results. After
  an exhausted read they tell the tutor to explain the loading failure briefly
  and listen, retaining the requested topic/level and confirmed focus. The tutor
  must not loop tool calls or propose creating a replacement because a read
  failed. A learner-requested retry reuses the already supplied details.

This is bounded recovery for the observed failure plus improved diagnosis, not a
verified fix for the still-unknown source of the original exception. The prompt
change guides model behavior and does not guarantee it in every conversation.

## Verification and development rollout

- JDK 25 / `:infra:test --tests '*BuddyStudyMcpAdapterTest'`: 32 tests passed,
  including nine new regressions; no failures, errors or skips.
- `:application:test --tests '*VoiceTutorServiceTest' --tests
  '*VoiceTutorWebRtcServiceTest'`: 83 tests passed (64 + 19); no failures, errors
  or skips. The existing topic-discovery policy test now covers failed reads.
- `:tutor:bootJar` and `git diff --check` passed. Build/test output is retained
  locally in `/tmp/study-mate-mcp-retry-tests.log` and
  `/tmp/study-mate-mcp-recovery-build.log`.
- With zero active sessions verified immediately before restart, the existing
  development runtime volume was updated with the built JAR and
  `backend-backend-1` restarted at approximately 18:17 KST. The preceding JAR is
  retained as `/app/buddystudy-backend.pre-mcp-read-recovery-20260910.jar`.
  No Docker image was built and no production deployment was performed.
- Installed JAR SHA-256 matches the build artifact:
  `92617869da4de5b71846d74ef70ac8c2e38108b8173dd5bca4339ad7276f5ca5`.
- After restart, local `/health` and the public development URL
  `https://lowfidev.cloud/health` returned HTTP 200 with `{"ok":true}`.
  The public check used curl. A Python urllib request received HTTP 403;
  changing only its User-Agent to curl returned 200. This client-dependent
  response was not treated as proof of an application or voice failure.
- The user's iPhone was unavailable for a fresh end-to-end call. No successful
  post-change live conversation has been verified. These backend checks and
  health responses do not establish audio quality or complete call recovery.

The change is backend-only; no iOS build or reinstall was required. Unrelated
in-progress profile-loading changes were excluded from this commit.
