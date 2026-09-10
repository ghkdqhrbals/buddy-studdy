import test from "node:test";
import assert from "node:assert/strict";
import {
  buildApiExchangeQuery,
  buildRelatedLogQuery,
  durationLabel,
  exchangeMetadata,
  formatKstFromNs,
  lokiMetricTimestampToMs,
  groupApiExchanges,
  parseApiError,
  parseApiExchange,
  parseRelatedLog,
  percentile,
} from "../public/logs.js";

test("formatKstFromNs renders KST without ISO T or Z", () => {
  assert.equal(formatKstFromNs("1783255799514000000"), "2026-07-05 21:49:59.514");
});

test("lokiMetricTimestampToMs converts metric query seconds to epoch milliseconds", () => {
  assert.equal(lokiMetricTimestampToMs(1783268850), 1783268850000);
  assert.equal(lokiMetricTimestampToMs("1783268850.5"), 1783268850500);
});

test("parseApiExchange extracts request row fields", () => {
  const line = [
    "2026-07-05T15:12:28.927Z ERROR [7dc19fed-31b7-43cd-be6d-b37862cf01c0] 1 --- [buddystudy-backend]",
    'c.b.RequestLoggingFilter : api_exchange {"requestId":"7dc19fed-31b7-43cd-be6d-b37862cf01c0","clientIp":"182.228.212.11","request":{"method":"POST","path":"/api/v1/devices/register","query":"","headers":{},"body":{"platform":"ios"}},"response":{"status":500,"durationMs":"4.38","headers":{},"body":{"error":{"code":"INTERNAL_SERVER_ERROR","reason":"boom"}}}}',
  ].join(" ");

  const parsed = parseApiExchange(["1783255799514000000", line]);

  assert.equal(parsed.time, "2026-07-05 21:49:59.514");
  assert.equal(parsed.method, "POST");
  assert.equal(parsed.path, "/api/v1/devices/register");
  assert.equal(parsed.status, 500);
  assert.equal(parsed.durationMs, 4.38);
  assert.equal(parsed.errorCode, "INTERNAL_SERVER_ERROR");
});

test("parseApiExchange extracts flat backend request logging fields", () => {
  const line = [
    "2026-07-06T13:16:18.261Z INFO [63c5eecb-66f1-49d1-b98e-8d20bae64b4b] 1 --- [buddystudy-backend]",
    'c.b.RequestLoggingFilter : api_exchange {"requestId":"63c5eecb-66f1-49d1-b98e-8d20bae64b4b","clientIp":"2a06:98c0:3600::103","userId":"42","method":"GET","path":"/api/v1/health/readiness","query":"","requestHeaders":{"accept":"application/json"},"requestBody":"","status":200,"durationMs":"3.12","responseHeaders":{"Content-Type":"application/json"},"responseBody":{"ok":true}}',
  ].join(" ");

  const parsed = parseApiExchange(["1783255799514000000", line]);

  assert.equal(parsed.method, "GET");
  assert.equal(parsed.clientIp, "2a06:98c0:3600::103");
  assert.equal(parsed.userId, "42");
  assert.equal(parsed.path, "/api/v1/health/readiness");
  assert.equal(parsed.status, 200);
  assert.equal(parsed.durationMs, 3.12);
  assert.deepEqual(parsed.request.body, "");
  assert.deepEqual(parsed.response.body, { ok: true });
});

test("parseApiExchange uses anonymous marker for legacy logs without user id", () => {
  const line =
    'c.b.RequestLoggingFilter : api_exchange {"requestId":"legacy","clientIp":"198.51.100.7","method":"GET","path":"/api/v1/public/questions","status":200,"durationMs":"1.00"}';

  const parsed = parseApiExchange(["1783255799514000000", line]);

  assert.equal(parsed.userId, "-");
});

test("parseApiError keeps stack trace when present", () => {
  const line = [
    "2026-07-05T15:12:28.927Z ERROR [7dc19fed-31b7-43cd-be6d-b37862cf01c0] 1 --- [buddystudy-backend]",
    "c.b.ErrorHandler : api_error requestId=7dc19fed-31b7-43cd-be6d-b37862cf01c0 clientIp=182.228.212.11 method=POST path=/api/v1/devices/register status=500 code=INTERNAL_SERVER_ERROR message=Internal backend error.",
    "",
    "jakarta.servlet.ServletException: Handler dispatch failed",
    "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:978)",
  ].join("\n");

  const parsed = parseApiError(["1783255799514000000", line]);

  assert.equal(parsed.requestId, "7dc19fed-31b7-43cd-be6d-b37862cf01c0");
  assert.equal(parsed.status, 500);
  assert.match(parsed.stack, /jakarta\.servlet\.ServletException/);
  assert.match(parsed.stack, /DispatcherServlet/);
});

test("parseApiError keeps one-line exception cause and origin fields", () => {
  const line = [
    "2026-07-26T04:20:00.000Z ERROR [req-linkage] 1 --- [buddystudy-backend]",
    "c.b.ErrorHandler : api_error requestId=req-linkage clientIp=203.0.113.2 method=POST path=/api/v1/studies/2/questions status=500 code=INTERNAL_SERVER_ERROR message=Internal backend error. exceptionType=NoClassDefFoundError exceptionMessage=Could not initialize class org.jooq.impl.DefaultDSLContext rootCauseType=ExceptionInInitializerError rootCauseMessage=jOOQ SQLDataType initialization failed origin=org.jooq.impl.DSL.using(DSL.java:918)",
  ].join(" ");

  const parsed = parseApiError(["1785039600000000000", line]);

  assert.equal(parsed.exceptionType, "NoClassDefFoundError");
  assert.equal(parsed.exceptionMessage, "Could not initialize class org.jooq.impl.DefaultDSLContext");
  assert.equal(parsed.rootCauseType, "ExceptionInInitializerError");
  assert.equal(parsed.rootCauseMessage, "jOOQ SQLDataType initialization failed");
  assert.equal(parsed.origin, "org.jooq.impl.DSL.using(DSL.java:918)");
});

test("related logs expose a concise summary instead of the full log line", () => {
  const requestId = "7dc19fed-31b7-43cd-be6d-b37862cf01c0";
  const line = [
    `2026-07-05T15:12:28.927Z ERROR [${requestId}] 1 --- [buddystudy-backend]`,
    `c.b.ErrorHandler : api_error requestId=${requestId} clientIp=182.228.212.11 method=POST path=/api/v1/auth/email/code status=503 code=EMAIL_DELIVERY_FAILED message=Email sender is not configured.`,
  ].join(" ");

  const parsed = parseRelatedLog(["1783255799514000000", line]);

  assert.equal(parsed.summary, "API error · POST · /api/v1/auth/email/code · EMAIL_DELIVERY_FAILED · status 503");
  assert.equal(parsed.summary.includes(requestId), false);
  assert.equal(parsed.summary.includes("Email sender is not configured"), false);
  assert.equal(parsed.rawLine, line);
});

test("stack continuation lines are not mislabeled as INFO", () => {
  const parsed = parseRelatedLog([
    "1783255799514000000",
    "\tat com.buddystudy.backend.Worker.process(Worker.kt:42)",
  ]);

  assert.equal(parsed.level, "UNKNOWN");
});

test("duration and percentile helpers are stable", () => {
  assert.equal(durationLabel(4.381), "4.38ms");
  assert.equal(durationLabel(1300), "1.30s");
  assert.equal(percentile([1, 2, 3, 4, 5], 95), 5);
});

function mcpExchange(overrides = {}) {
  const payload = {
    requestId: "mcp-1",
    userId: "42",
    method: "MCP",
    path: "/mcp/tools/call/list_studies",
    protocol: "mcp",
    transport: "voice",
    operation: "tools/call",
    toolName: "list_studies",
    sessionId: "session-1",
    callId: "call-1",
    startedAt: "2026-09-10T10:00:00.000Z",
    completedAt: "2026-09-10T10:00:00.251Z",
    requestBody: { name: "list_studies", arguments: { query: "spring" } },
    responseBody: { studies: [{ topic: "spring" }] },
    status: 200,
    durationMs: "251.25",
    ...overrides,
  };
  return ["1789034400251000000", `2026-09-10T10:00:00.251Z INFO McpExchangeLogger : mcp_exchange ${JSON.stringify(payload)}`];
}

test("MCP voice calls retain bodies, exact duration, identities and KST start/end metadata", () => {
  const parsed = parseApiExchange(mcpExchange());
  assert.equal(parsed.method, "MCP");
  assert.equal(parsed.protocol, "mcp");
  assert.equal(parsed.transport, "voice");
  assert.equal(parsed.durationMs, 251.25);
  assert.deepEqual(parsed.request.body, { name: "list_studies", arguments: { query: "spring" } });
  assert.deepEqual(parsed.response.body, { studies: [{ topic: "spring" }] });
  const metadata = Object.fromEntries(exchangeMetadata(parsed));
  assert.equal(metadata.Started, "2026-09-10 19:00:00.000 KST");
  assert.equal(metadata.Completed, "2026-09-10 19:00:00.251 KST");
  assert.equal(metadata["Duration (ms)"], "251.25");
  assert.equal(metadata["Session ID"], "session-1");
  assert.equal(metadata["Call ID"], "call-1");
  assert.equal(metadata.Transport, "Voice");
});

test("MCP logical failures expose error message and HTTP parent correlation", () => {
  const parsed = parseApiExchange(mcpExchange({
    transport: "http", parentRequestId: "http-parent", status: 403,
    responseBody: { error: { code: "PERMISSION_DENIED", status: 403, message: "Permission is denied." } },
  }));
  assert.equal(parsed.isError, true);
  assert.equal(parsed.errorCode, "PERMISSION_DENIED");
  assert.equal(parsed.errorReason, "Permission is denied.");
  assert.equal(parsed.parentRequestId, "http-parent");
  assert.equal(Object.fromEntries(exchangeMetadata(parsed))["Parent request ID"], "http-parent");
});

test("MCP marker inference and resources remain visible with optional metadata absent", () => {
  const parsed = parseApiExchange(mcpExchange({
    protocol: undefined, transport: "http", toolName: undefined, sessionId: undefined, callId: undefined,
    operation: "resources/read", resourceName: "profile", path: "/mcp/resources/read/profile",
    responseBody: { content: "api_exchange must stay inside the original JSON body" },
  }));
  assert.equal(parsed.protocol, "mcp");
  assert.equal(parsed.resourceName, "profile");
  assert.equal(Object.fromEntries(exchangeMetadata(parsed)).Resource, "profile");
  assert.equal(Object.fromEntries(exchangeMetadata(parsed))["Call ID"], undefined);
  assert.match(parseRelatedLog(mcpExchange()).summary, /^MCP exchange · MCP · \/mcp\/tools\/call\/list_studies/);
});

test("exchange search includes both log markers and safely quotes MCP filters", () => {
  assert.equal(buildApiExchangeQuery(), '{app="buddystudy"} |~ "(api|mcp)_exchange "');
  assert.equal(buildApiExchangeQuery({ method: "MCP", path: "list_studies", statusPrefix: "4" }),
    '{app="buddystudy"} |~ "(api|mcp)_exchange " |= "\\\"method\\\":\\\"MCP\\\"" |~ "\\\"status\\\":4[0-9][0-9]" |= "list_studies"');
  const hostile = 'quoted" |~ ".*\n';
  assert.ok(buildApiExchangeQuery({ requestId: hostile }).endsWith(`|= ${JSON.stringify(hostile)}`));
});

test("related logs match only escaped logical and parent request IDs", () => {
  assert.equal(buildRelatedLogQuery({ requestId: "mcp-1", parentRequestId: "http-parent" }),
    '{app="buddystudy"} |~ "mcp-1|http-parent"');
  assert.equal(buildRelatedLogQuery({ requestId: "a.b", parentRequestId: "c|d" }),
    '{app="buddystudy"} |~ "a\\\\.b|c\\\\|d"');
  assert.equal(buildRelatedLogQuery({ requestId: "same", parentRequestId: "same" }),
    '{app="buddystudy"} |= "same"');
});

test("MCP performance groups logical errors and cancellations while preserving REST server-error semantics", () => {
  const values = [
    parseApiExchange(mcpExchange({ durationMs: "20" })),
    parseApiExchange(mcpExchange({ status: 403, durationMs: "40" })),
    parseApiExchange(mcpExchange({ status: 499, errorCode: "CANCELLED", durationMs: "60" })),
    parseApiExchange(mcpExchange({ method: "POST", protocol: "rest", path: "/api/v1/mcp", status: 400, durationMs: "5" })),
    parseApiExchange(mcpExchange({ method: "POST", protocol: "rest", path: "/api/v1/mcp", status: 500, durationMs: "10" })),
  ];
  const groups = groupApiExchanges(values);
  assert.equal(groups.length, 2);
  assert.deepEqual([groups[0].method, groups[0].count, groups[0].errors, groups[0].p50, groups[0].p99], ["MCP", 3, 2, 40, 60]);
  assert.deepEqual([groups[1].method, groups[1].count, groups[1].errors], ["POST", 2, 1]);
  assert.equal(groupApiExchanges(values, { method: "MCP", pathQuery: "LIST_STUDIES" }).length, 1);
  assert.equal(groupApiExchanges(values, { method: "MCP", pathQuery: "missing" }).length, 0);
});
