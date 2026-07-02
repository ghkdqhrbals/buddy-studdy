import assert from "node:assert/strict";
import { test } from "node:test";
import { internals } from "../src/index.js";

const env = {
  ALERT_REPEAT_SECONDS: "3600",
  FAILURE_THRESHOLD: "2",
  HEALTHCHECK_URL: "https://api.lowfidev.cloud/api/v1/health/readiness",
  SERVICE_NAME: "BuddyStudy backend",
  ENVIRONMENT_NAME: "production",
};

test("first failure is degraded and does not alert before threshold", () => {
  const state = internals.nextState(null, { healthy: false, httpStatus: 502, error: "HTTP 502" }, env, "2026-07-03T00:00:00.000Z");

  assert.equal(state.status, "degraded");
  assert.equal(state.consecutiveFailures, 1);
  assert.equal(state.shouldAlert, false);
  assert.equal(state.alertType, null);
});

test("second consecutive failure marks down and alerts slack", () => {
  const previous = {
    status: "degraded",
    consecutiveFailures: 1,
    lastAlertAt: null,
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: null,
  };
  const state = internals.nextState(previous, { healthy: false, httpStatus: null, error: "fetch failed" }, env, "2026-07-03T00:05:00.000Z");

  assert.equal(state.status, "down");
  assert.equal(state.consecutiveFailures, 2);
  assert.equal(state.shouldAlert, true);
  assert.equal(state.alertType, "down");
  assert.equal(state.lastAlertAt, "2026-07-03T00:05:00.000Z");
});

test("down service repeats alert only after repeat interval", () => {
  const previous = {
    status: "down",
    consecutiveFailures: 3,
    lastAlertAt: "2026-07-03T00:05:00.000Z",
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: "2026-07-03T00:05:00.000Z",
  };

  const tooEarly = internals.nextState(previous, { healthy: false, httpStatus: 503, error: "HTTP 503" }, env, "2026-07-03T00:30:00.000Z");
  const repeatDue = internals.nextState(previous, { healthy: false, httpStatus: 503, error: "HTTP 503" }, env, "2026-07-03T01:06:00.000Z");

  assert.equal(tooEarly.shouldAlert, false);
  assert.equal(repeatDue.shouldAlert, true);
  assert.equal(repeatDue.alertType, "still_down");
});

test("recovery after down sends recovery alert", () => {
  const previous = {
    status: "down",
    consecutiveFailures: 4,
    lastAlertAt: "2026-07-03T00:05:00.000Z",
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: "2026-07-03T00:05:00.000Z",
  };
  const state = internals.nextState(previous, { healthy: true, httpStatus: 200, error: null }, env, "2026-07-03T01:10:00.000Z");

  assert.equal(state.status, "up");
  assert.equal(state.consecutiveFailures, 0);
  assert.equal(state.shouldAlert, true);
  assert.equal(state.alertType, "recovered");
});

test("slack payload contains environment, status, url, time, failures, and error", () => {
  const payload = internals.buildSlackPayload(env, {
    status: "down",
    checkedAt: "2026-07-03T00:05:00.000Z",
    consecutiveFailures: 2,
    error: "fetch failed",
    alertType: "down",
  });
  const fields = payload.blocks[1].fields.map((field) => field.text).join("\n");

  assert.equal(payload.text, ":rotating_light: BuddyStudy backend is down");
  assert.match(fields, /production/);
  assert.match(fields, /down/);
  assert.match(fields, /https:\/\/api\.lowfidev\.cloud\/api\/v1\/health\/readiness/);
  assert.match(fields, /2026-07-03T00:05:00.000Z/);
  assert.match(fields, /2/);
  assert.match(fields, /fetch failed/);
});
