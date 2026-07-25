import test from "node:test";
import assert from "node:assert/strict";
import {
  durationLabel,
  formatKstFromNs,
  lokiMetricTimestampToMs,
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
    'c.b.RequestLoggingFilter : api_exchange {"requestId":"63c5eecb-66f1-49d1-b98e-8d20bae64b4b","clientIp":"2a06:98c0:3600::103","method":"GET","path":"/api/v1/health/readiness","query":"","requestHeaders":{"accept":"application/json"},"requestBody":"","status":200,"durationMs":"3.12","responseHeaders":{"Content-Type":"application/json"},"responseBody":{"ok":true}}',
  ].join(" ");

  const parsed = parseApiExchange(["1783255799514000000", line]);

  assert.equal(parsed.method, "GET");
  assert.equal(parsed.path, "/api/v1/health/readiness");
  assert.equal(parsed.status, 200);
  assert.equal(parsed.durationMs, 3.12);
  assert.deepEqual(parsed.request.body, "");
  assert.deepEqual(parsed.response.body, { ok: true });
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

test("duration and percentile helpers are stable", () => {
  assert.equal(durationLabel(4.381), "4.38ms");
  assert.equal(durationLabel(1300), "1.30s");
  assert.equal(percentile([1, 2, 3, 4, 5], 95), 5);
});
