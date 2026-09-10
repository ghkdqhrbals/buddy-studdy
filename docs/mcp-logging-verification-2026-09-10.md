# MCP administrative request/response logging — 2026-09-10

## Scope

MCP operations now appear alongside REST calls in the existing authenticated
Monitoring API Logs and API Performance workspace. This change covers backend
observability and the monitoring UI; it does not modify the iOS app.

- External HTTP MCP records the final SDK JSON-RPC result, including discovery,
  tool/resource reads, unknown tools/methods, and input/output schema failures.
- Local voice execution records its final result, including early permission
  and validation failures, explicit reviewed-answer submission/skip, and
  `voice/progress` polling. Shared inner handlers suppress duplicate entries.
- Each `mcp_exchange` has a unique request ID, trusted user ID, transport,
  operation/target, start/end timestamps, duration in milliseconds, status,
  redacted request/response bodies, and available parent HTTP/session/call IDs.
- Timing includes internal retries. Cancellation propagates and records `499`.
  JSON-RPC internal/server errors and voice tool outages are classified as 5xx;
  permission and learner-state failures remain distinct from server failures.
- Bodies are redacted before a 2,000-character preview cap. Nested JSON text,
  credentials, Basic/Bearer values, URL userinfo, encoded signing parameters,
  signed URLs, audio/binary fields, and recording grants are covered. Invalid
  JSON-like strings fail closed. Unknown method metadata cannot bypass the cap.
- Existing REST `api_exchange` metrics/alerts keep their meaning. Raw HTTP MCP
  body buffering remains disabled; pre-handler HTTP rejections retain REST
  metadata logs.

## Verification

The focused backend suite runs from an isolated checkout to avoid sharing
Gradle build outputs with concurrent voice work:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew \
  -Dorg.gradle.jvmargs=-Xmx4g \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :infra:test --tests '*McpExchangeLoggerTest' \
  --tests '*BuddyStudyMcpAdapterTest' --tests '*McpVoiceTutorToolAdapterTest' \
  --tests '*RequestLoggingFilterTest' \
  :tutor:test --tests '*McpServerConfigTest' \
  -x :tutor:processTestAot -x :tutor:compileAotTestJava \
  -x :tutor:processAotTestResources :tutor:bootJar
```

- Backend focused suite: **176 passed, 0 failed** (16 HTTP logging filter,
  32 shared MCP adapter, 9 exchange logger, 105 voice adapter, 14 HTTP MCP
  configuration/SDK tests). Production AOT processing and `:tutor:bootJar`
  completed successfully on JDK 25.
- The admin parser accepted **464 actual backend test exchange log lines**
  across 30 operation groups and both HTTP/voice transports. Request IDs,
  numeric durations, status classification, metadata rendering, and performance
  group totals matched the generated backend events.
- Monitoring `npm test`: **131 passed, 0 failed**.
- Monitoring production React bundle rebuilt successfully.
- Browser verification used deterministic fixtures with the compiled admin UI:
  MCP filtering, tool search, HTTP/Voice metadata, request/response detail,
  precise duration, parent-request related logs, and MCP performance statistics
  passed. Desktop/mobile checks found no console errors or blocking overlays.
  These fixtures are test records, not live learner calls.
- `git diff --check`: passed.

## Runtime and deployment boundary

This patch does not change deployment workflows, expose the disabled-by-default
external MCP endpoint, connect to production via SSH, or add runtime workflow
health checks.

The local development container is `backend-backend-1`. Its logs currently stay
in Docker's local log driver; the checked-in monitoring configuration does not
forward this Mac's backend logs to the remote Loki. Updating the remote admin UI
alone therefore cannot show local development calls.

Production availability requires separate backend and Monitoring module
rollouts through the existing GitHub Actions/personal-deploy workflows. No
production rollout or remote Loki ingestion is claimed by this verification.
At the user's explicit local-only restart request, `backend-backend-1` was
restarted at **2026-09-10 19:50:27 KST** with the verified `61b03f42` backend.
All 1,022 backend source files in the build snapshot matched that commit, and
the installed JAR contains the MCP logger and final-response transport wrapper.
The JAR SHA-256 is
`ea7ec99dd71083641dff1ff65f6f68c3f8f377d99ee58db0dc52667b9c9bdfc4`.

- Active voice sessions were **0** immediately before restart.
- `http://127.0.0.1:8080/health` returned **200** with `{"ok":true}` afterward.
- The previous JAR was preserved as
  `/app/pre-mcp-observability-20260910.jar` in the existing app volume.
- The installed BuddyStudy **1.1.0 (16)** app was successfully activated on the
  connected Min iPhone at **19:51:04 KST** using `devicectl`; no app reinstall
  was necessary.
- Concurrent uncommitted native-input-admission server changes were left out
  of this verified JAR and remain owned by the other task for its later rollout.
