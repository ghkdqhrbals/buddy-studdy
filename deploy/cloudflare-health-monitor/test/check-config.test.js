import assert from "node:assert/strict";
import { test } from "node:test";
import { validateConfig } from "../scripts/check-config.js";

test("health monitor config accepts required production shape", () => {
  assert.deepEqual(validateConfig(validConfig()), []);
});

test("health monitor config rejects missing runtime essentials", () => {
  const config = validConfig();
  config.triggers.crons = ["*/5 * * * *"];
  config.kv_namespaces[0].id = "replace-with-kv-namespace-id";
  config.vars.HEALTHCHECK_URL = "http://api.lowfidev.cloud/api/v1/health/readiness";
  config.vars.SERVICE_NAME = "";
  delete config.vars.ENVIRONMENT_NAME;
  config.vars.FAILURE_THRESHOLD = "0";
  config.vars.ALERT_REPEAT_SECONDS = "bad";
  config.vars.HEALTHCHECK_TIMEOUT_MS = "";
  config.vars.SLACK_TIMEOUT_MS = "bad";

  const errors = validateConfig(config).join("\n");

  assert.match(errors, /1-minute cron/);
  assert.match(errors, /KV namespace/);
  assert.match(errors, /HTTPS URL/);
  assert.match(errors, /SERVICE_NAME/);
  assert.match(errors, /ENVIRONMENT_NAME/);
  assert.match(errors, /FAILURE_THRESHOLD/);
  assert.match(errors, /ALERT_REPEAT_SECONDS/);
  assert.match(errors, /HEALTHCHECK_TIMEOUT_MS/);
  assert.match(errors, /SLACK_TIMEOUT_MS/);
});

test("health monitor config rejects lightweight health endpoints", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_URL = "https://api.ghkdqhrbals.org/health";

  assert.match(validateConfig(config).join("\n"), /readiness endpoint/);
});

test("health monitor config rejects dev health url for production", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_URL = "https://api.lowfidev.cloud/api/v1/health/readiness";
  config.vars.ENVIRONMENT_NAME = " Production ";

  assert.match(validateConfig(config).join("\n"), /Production HEALTHCHECK_URL/);
});

test("health monitor config rejects slow outage alert thresholds", () => {
  const config = validConfig();
  config.vars.FAILURE_THRESHOLD = "3";

  assert.match(validateConfig(config).join("\n"), /FAILURE_THRESHOLD must be 1 or 2/);
});

test("health monitor config rejects repeat intervals that are too noisy or too delayed", () => {
  const tooNoisy = validConfig();
  tooNoisy.vars.ALERT_REPEAT_SECONDS = "60";
  assert.match(validateConfig(tooNoisy).join("\n"), /ALERT_REPEAT_SECONDS must be between 300 and 86400/);

  const tooDelayed = validConfig();
  tooDelayed.vars.ALERT_REPEAT_SECONDS = "172800";
  assert.match(validateConfig(tooDelayed).join("\n"), /ALERT_REPEAT_SECONDS must be between 300 and 86400/);
});

test("health monitor config rejects timeout values that are too slow for Worker cron", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_TIMEOUT_MS = "30000";
  config.vars.SLACK_TIMEOUT_MS = "30000";

  const errors = validateConfig(config).join("\n");

  assert.match(errors, /HEALTHCHECK_TIMEOUT_MS must be between 1000 and 25000/);
  assert.match(errors, /SLACK_TIMEOUT_MS must be between 1000 and 25000/);
});

test("health monitor config requires a public worker entrypoint", () => {
  const config = validConfig();
  config.workers_dev = false;

  assert.match(validateConfig(config).join("\n"), /workers_dev or routes/);
});

test("health monitor config requires Cloudflare observability logs", () => {
  const config = validConfig();
  config.observability.enabled = false;

  assert.match(validateConfig(config).join("\n"), /observability/);
});

test("health monitor config requires full observability sampling", () => {
  const config = validConfig();
  config.observability.head_sampling_rate = 0.1;

  assert.match(validateConfig(config).join("\n"), /observability sampling/);
});

function validConfig() {
  return {
    name: "buddystudy-health-monitor",
    main: "src/index.js",
    workers_dev: true,
    observability: {
      enabled: true,
      head_sampling_rate: 1,
    },
    triggers: { crons: ["* * * * *"] },
    vars: {
      HEALTHCHECK_URL: "https://api.ghkdqhrbals.org/api/v1/health/readiness",
      SERVICE_NAME: "BuddyStudy backend",
      ENVIRONMENT_NAME: "production",
      FAILURE_THRESHOLD: "2",
      ALERT_REPEAT_SECONDS: "3600",
      HEALTHCHECK_TIMEOUT_MS: "8000",
      SLACK_TIMEOUT_MS: "5000",
    },
    kv_namespaces: [{ binding: "HEALTH_MONITOR_STATE", id: "kv-id" }],
  };
}
