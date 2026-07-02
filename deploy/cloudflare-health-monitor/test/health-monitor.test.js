import assert from "node:assert/strict";
import { test } from "node:test";
import worker, { internals } from "../src/index.js";

const env = {
  ALERT_REPEAT_SECONDS: "3600",
  FAILURE_THRESHOLD: "2",
  HEALTHCHECK_URL: "https://api.lowfidev.cloud/api/v1/health/readiness",
  SERVICE_NAME: "BuddyStudy backend",
  ENVIRONMENT_NAME: "production",
};

test("first failure is degraded and does not alert before threshold", () => {
  const state = internals.nextState(
    null,
    { healthy: false, httpStatus: 502, error: "HTTP 502", detail: "upstream failed" },
    env,
    "2026-07-03T00:00:00.000Z",
  );

  assert.equal(state.status, "degraded");
  assert.equal(state.consecutiveFailures, 1);
  assert.equal(state.shouldAlert, false);
  assert.equal(state.alertType, null);
  assert.equal(state.detail, "upstream failed");
});

test("second consecutive failure marks down and alerts slack", () => {
  const previous = {
    status: "degraded",
    consecutiveFailures: 1,
    lastAlertAt: null,
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: null,
  };
  const state = internals.nextState(previous, { healthy: false, httpStatus: null, error: "fetch failed", detail: null }, env, "2026-07-03T00:05:00.000Z");

  assert.equal(state.status, "down");
  assert.equal(state.consecutiveFailures, 2);
  assert.equal(state.shouldAlert, true);
  assert.equal(state.alertType, "down");
  assert.equal(state.lastAlertAt, null);
});

test("down service repeats alert only after repeat interval", () => {
  const previous = {
    status: "down",
    consecutiveFailures: 3,
    lastAlertAt: "2026-07-03T00:05:00.000Z",
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: "2026-07-03T00:05:00.000Z",
  };

  const tooEarly = internals.nextState(previous, { healthy: false, httpStatus: 503, error: "HTTP 503", detail: null }, env, "2026-07-03T00:30:00.000Z");
  const repeatDue = internals.nextState(previous, { healthy: false, httpStatus: 503, error: "HTTP 503", detail: null }, env, "2026-07-03T01:06:00.000Z");

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
  const state = internals.nextState(previous, { healthy: true, httpStatus: 200, error: null, detail: null }, env, "2026-07-03T01:10:00.000Z");

  assert.equal(state.status, "up");
  assert.equal(state.consecutiveFailures, 0);
  assert.equal(state.shouldAlert, true);
  assert.equal(state.alertType, "recovered");
});

test("slack payload contains environment, status, url, time, failures, error, and readiness detail", () => {
  const payload = internals.buildSlackPayload(env, {
    status: "down",
    checkedAt: "2026-07-03T00:05:00.000Z",
    lastUpAt: "2026-07-02T23:55:00.000Z",
    lastDownAt: "2026-07-03T00:05:00.000Z",
    consecutiveFailures: 2,
    error: "fetch failed",
    detail: "scheduler: Stale scheduler jobs: question-schedule",
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
  assert.match(fields, /Stale scheduler jobs/);
  assert.match(fields, /Down since/);
  assert.match(fields, /Last up/);
  assert.match(fields, /Duration/);
});

test("recovery slack payload includes outage duration", () => {
  const payload = internals.buildSlackPayload(env, {
    status: "up",
    checkedAt: "2026-07-03T01:10:00.000Z",
    lastUpAt: "2026-07-03T01:10:00.000Z",
    lastDownAt: "2026-07-03T00:05:00.000Z",
    consecutiveFailures: 0,
    error: null,
    detail: null,
    alertType: "recovered",
  });
  const fields = payload.blocks[1].fields.map((field) => field.text).join("\n");

  assert.equal(payload.text, ":white_check_mark: BuddyStudy backend recovered");
  assert.match(fields, /Duration/);
  assert.match(fields, /1h 5m/);
});

test("summarizes failed readiness checks from JSON body", () => {
  const summary = internals.summarizeHealthJson({
    ok: false,
    checks: {
      database: { ok: true },
      redis: { ok: false, message: "Redis ping failed" },
      scheduler: {
        ok: false,
        message: "Stale scheduler jobs: question-schedule",
        details: {
          thresholdSeconds: 900,
          staleJobs: [
            { jobName: "question-schedule", lastStartedAt: "2026-07-03T04:00:00Z", staleForSeconds: 1800 },
          ],
        },
      },
    },
  });

  assert.equal(
    summary,
    "redis: Redis ping failed; scheduler: Stale scheduler jobs: question-schedule [threshold=900s, staleJobs=question-schedule staleFor=1800s]",
  );
});

test("checkHealth captures non ok readiness body detail", async () => {
  const environment = manualEnv({
    healthResponse: new Response(
      JSON.stringify({
        ok: false,
        checks: {
          database: { ok: true },
          scheduler: { ok: false, message: "Missing monitored scheduler jobs: question-schedule" },
        },
      }),
      { status: 503, headers: { "Content-Type": "application/json" } },
    ),
  });

  const result = await withManualEnv(environment, () => internals.checkHealth(environment.HEALTHCHECK_URL));

  assert.equal(result.healthy, false);
  assert.equal(result.httpStatus, 503);
  assert.equal(result.error, "HTTP 503");
  assert.equal(result.detail, "scheduler: Missing monitored scheduler jobs: question-schedule");
});

test("checkHealth times out slow health responses", async () => {
  const environment = manualEnv({
    HEALTHCHECK_TIMEOUT_MS: "1000",
    healthResponse: (_input, init) =>
      new Promise((_resolve, reject) => {
        init.signal.addEventListener("abort", () => {
          const error = new Error("healthcheck timeout");
          error.name = "AbortError";
          reject(error);
        });
      }),
  });

  const result = await withManualEnv(environment, () => internals.checkHealth(environment.HEALTHCHECK_URL, environment));

  assert.equal(result.healthy, false);
  assert.equal(result.httpStatus, null);
  assert.equal(result.error, "Healthcheck timed out after 1000ms");
});

test("healthcheck timeout is bounded to Cloudflare-safe limits", () => {
  assert.equal(internals.healthcheckTimeoutMs({ HEALTHCHECK_TIMEOUT_MS: "1" }), 1000);
  assert.equal(internals.healthcheckTimeoutMs({ HEALTHCHECK_TIMEOUT_MS: "999999" }), 25000);
  assert.equal(internals.healthcheckTimeoutMs({ HEALTHCHECK_TIMEOUT_MS: "bad" }), 8000);
});

test("manual check requires configured bearer token", async () => {
  const unauthorized = await worker.fetch(new Request("https://monitor.example.com/check", { method: "POST" }), manualEnv());
  const authorizedEnv = manualEnv({
    healthResponse: new Response("ok", { status: 200 }),
  });
  const authorized = await withManualEnv(authorizedEnv, () =>
    worker.fetch(
      new Request("https://monitor.example.com/check", {
        method: "POST",
        headers: { Authorization: "Bearer manual-secret" },
      }),
      authorizedEnv,
    ),
  );

  assert.equal(unauthorized.status, 401);
  assert.equal(authorized.status, 200);
  assert.equal((await authorized.json()).state.status, "up");
});

test("manual check writes state and sends slack alert when threshold is reached", async () => {
  const slackPayloads = [];
  const environment = manualEnv({
    existingState: {
      status: "degraded",
      consecutiveFailures: 1,
      lastAlertAt: null,
      lastUpAt: "2026-07-02T23:55:00.000Z",
      lastDownAt: null,
    },
    healthResponse: new Response("bad gateway", { status: 502 }),
    onSlack: async (request) => {
      slackPayloads.push(await request.json());
      return new Response("ok", { status: 200 });
    },
  });

  const response = await withManualEnv(environment, () =>
    worker.fetch(
      new Request("https://monitor.example.com/check", {
        method: "POST",
        headers: { Authorization: "Bearer manual-secret" },
      }),
      environment,
    ),
  );
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.equal(body.ok, false);
  assert.equal(body.state.status, "down");
  assert.equal(environment.stateWrites.length, 1);
  const storedState = JSON.parse(environment.stateWrites[0].value);
  assert.equal(storedState.status, "down");
  assert.equal(storedState.lastAlertAt, storedState.checkedAt);
  assert.equal(storedState.shouldAlert, false);
  assert.equal(storedState.alertSent, true);
  assert.equal(storedState.slackAlertError, null);
  assert.equal(slackPayloads.length, 1);
  assert.equal(slackPayloads[0].text, ":rotating_light: BuddyStudy backend is down");
});

test("manual check keeps alert retryable when Slack delivery fails", async () => {
  const environment = manualEnv({
    existingState: {
      status: "degraded",
      consecutiveFailures: 1,
      lastAlertAt: null,
      lastUpAt: "2026-07-02T23:55:00.000Z",
      lastDownAt: null,
    },
    healthResponse: new Response("bad gateway", { status: 502 }),
    onSlack: async () => new Response("slack unavailable", { status: 503 }),
  });

  const response = await withManualEnv(environment, () =>
    worker.fetch(
      new Request("https://monitor.example.com/check", {
        method: "POST",
        headers: { Authorization: "Bearer manual-secret" },
      }),
      environment,
    ),
  );
  const body = await response.json();
  const storedState = JSON.parse(environment.stateWrites[0].value);

  assert.equal(response.status, 200);
  assert.equal(body.state.status, "down");
  assert.equal(storedState.status, "down");
  assert.equal(storedState.shouldAlert, true);
  assert.equal(storedState.alertSent, false);
  assert.equal(storedState.slackAlertError, "Slack webhook failed with HTTP 503");
  assert.equal(storedState.lastAlertAt, null);
});

test("manual check reports unexpected monitor failures as json", async () => {
  const environment = manualEnv({ stateGetError: new Error("kv unavailable") });

  const response = await worker.fetch(
    new Request("https://monitor.example.com/check", {
      method: "POST",
      headers: { Authorization: "Bearer manual-secret" },
    }),
    environment,
  );
  const body = await response.json();

  assert.equal(response.status, 500);
  assert.equal(body.ok, false);
  assert.equal(body.error, "Health monitor execution failed.");
  assert.match(body.message, /kv unavailable/);
});

test("scheduled check catches unexpected monitor failures", async () => {
  const environment = manualEnv({ statePutError: new Error("kv write failed") });
  const waitUntilPromises = [];
  const ctx = { waitUntil: (promise) => waitUntilPromises.push(promise) };

  await worker.scheduled({ scheduledTime: Date.parse("2026-07-03T00:00:00.000Z") }, environment, ctx);

  assert.equal(waitUntilPromises.length, 1);
  await assert.doesNotReject(waitUntilPromises[0]);
});

test("scheduled check persists configuration error state when kv is available", async () => {
  const environment = manualEnv();
  environment.SLACK_WEBHOOK_URL = "";
  const waitUntilPromises = [];
  const ctx = { waitUntil: (promise) => waitUntilPromises.push(promise) };

  await worker.scheduled({ scheduledTime: Date.parse("2026-07-03T00:00:00.000Z") }, environment, ctx);
  await assert.doesNotReject(waitUntilPromises[0]);

  assert.equal(environment.stateWrites.length, 1);
  const storedState = JSON.parse(environment.stateWrites[0].value);
  assert.equal(storedState.status, "config_error");
  assert.match(storedState.error, /SLACK_WEBHOOK_URL/);
});

test("manual check token helper rejects absent and mismatched tokens", () => {
  assert.equal(internals.isAuthorizedManualCheck(new Request("https://monitor.example.com/check"), env), false);
  assert.equal(
    internals.isAuthorizedManualCheck(
      new Request("https://monitor.example.com/check", { headers: { Authorization: "Bearer wrong" } }),
      { ...env, MANUAL_CHECK_TOKEN: "manual-secret" },
    ),
    false,
  );
  assert.equal(
    internals.isAuthorizedManualCheck(
      new Request("https://monitor.example.com/check", { headers: { Authorization: "Bearer manual-secret" } }),
      { ...env, MANUAL_CHECK_TOKEN: "manual-secret" },
    ),
    true,
  );
});

test("root reports configuration errors before reading state", async () => {
  const response = await worker.fetch(new Request("https://monitor.example.com/"), {
    ...env,
    SLACK_WEBHOOK_URL: "https://slack.example.com/webhook",
  });
  const body = await response.json();

  assert.equal(response.status, 500);
  assert.equal(body.ok, false);
  assert.deepEqual(body.missingConfig, ["HEALTH_MONITOR_STATE"]);
});

test("root reports state read errors as json", async () => {
  const response = await worker.fetch(
    new Request("https://monitor.example.com/"),
    manualEnv({ stateGetError: new Error("kv read failed") }),
  );
  const body = await response.json();

  assert.equal(response.status, 500);
  assert.equal(body.ok, false);
  assert.equal(body.error, "Health monitor state read failed.");
  assert.match(body.message, /kv read failed/);
});

test("manual check reports configuration errors after authorization", async () => {
  const response = await worker.fetch(
    new Request("https://monitor.example.com/check", {
      method: "POST",
      headers: { Authorization: "Bearer manual-secret" },
    }),
    {
      ...env,
      MANUAL_CHECK_TOKEN: "manual-secret",
      HEALTH_MONITOR_STATE: manualEnv().HEALTH_MONITOR_STATE,
    },
  );
  const body = await response.json();

  assert.equal(response.status, 500);
  assert.equal(body.ok, false);
  assert.deepEqual(body.missingConfig, ["SLACK_WEBHOOK_URL"]);
});

test("validateEnv accepts required monitor bindings", () => {
  const environment = manualEnv();

  assert.deepEqual(internals.validateEnv(environment), { ok: true, missing: [] });
});

function manualEnv({
  existingState = null,
  healthResponse = new Response("ok", { status: 200 }),
  onSlack = null,
  stateGetError = null,
  statePutError = null,
  ...overrides
} = {}) {
  const stateWrites = [];
  return {
    ...env,
    ...overrides,
    MANUAL_CHECK_TOKEN: "manual-secret",
    SLACK_WEBHOOK_URL: "https://slack.example.com/webhook",
    HEALTH_MONITOR_STATE: {
      async get() {
        if (stateGetError) throw stateGetError;
        return existingState ? JSON.stringify(existingState) : null;
      },
      async put(key, value) {
        if (statePutError) throw statePutError;
        stateWrites.push({ key, value });
      },
    },
    stateWrites,
    healthResponse,
    onSlack,
  };
}

const originalFetch = globalThis.fetch;
globalThis.fetch = async function mockedFetch(input, init) {
  const url = typeof input === "string" ? input : input.url;
  const activeEnv = currentManualEnv;
  if (activeEnv && url === activeEnv.HEALTHCHECK_URL) {
    if (typeof activeEnv.healthResponse === "function") {
      return activeEnv.healthResponse(input, init);
    }
    return activeEnv.healthResponse.clone();
  }
  if (activeEnv && url === activeEnv.SLACK_WEBHOOK_URL && activeEnv.onSlack) {
    return activeEnv.onSlack(input instanceof Request ? input : new Request(input, init));
  }
  return originalFetch(input, init);
};

let currentManualEnv = null;

function withManualEnv(environment, run) {
  currentManualEnv = environment;
  return Promise.resolve(run()).finally(() => {
    currentManualEnv = null;
  });
}
