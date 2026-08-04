import assert from "node:assert/strict";
import { test } from "node:test";
import { validateConfig } from "../scripts/check-config.js";

test("health monitor config accepts required production shape", () => {
  assert.deepEqual(validateConfig(validConfig()), []);
});

test("health monitor config rejects missing runtime essentials", () => {
  const config = validConfig();
  config.kv_namespaces[0].id = "replace-with-kv-namespace-id";
  config.vars.HEALTHCHECK_URL = "http://lowfidev.cloud/api/v1/health/readiness";
  config.vars.SERVICE_NAME = "";
  delete config.vars.ENVIRONMENT_NAME;
  config.vars.FAILURE_THRESHOLD = "0";
  config.vars.ALERT_REPEAT_SECONDS = "bad";
  config.vars.STATUS_STALE_AFTER_SECONDS = "bad";
  config.vars.HEALTHCHECK_TIMEOUT_MS = "";
  config.vars.SLACK_TIMEOUT_MS = "bad";

  const errors = validateConfig(config).join("\n");

  assert.match(errors, /KV namespace/);
  assert.match(errors, /HTTPS URL/);
  assert.match(errors, /SERVICE_NAME/);
  assert.match(errors, /ENVIRONMENT_NAME/);
  assert.match(errors, /FAILURE_THRESHOLD/);
  assert.match(errors, /ALERT_REPEAT_SECONDS/);
  assert.match(errors, /STATUS_STALE_AFTER_SECONDS/);
  assert.match(errors, /HEALTHCHECK_TIMEOUT_MS/);
  assert.match(errors, /SLACK_TIMEOUT_MS/);
});

test("health monitor config rejects cron triggers when scheduled checks are disabled", () => {
  const config = validConfig();
  config.triggers = { crons: ["*/5 * * * *"] };

  assert.match(validateConfig(config).join("\n"), /Cron triggers must be absent/);
});

test("health monitor config requires the exact cron when scheduled checks are enabled", () => {
  const config = validConfig();
  config.vars.SCHEDULED_CHECKS_ENABLED = "true";
  config.triggers = { crons: ["* * * * *"] };

  assert.match(validateConfig(config).join("\n"), /exactly one 5-minute cron/);
});

test("health monitor config rejects lightweight health endpoints", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_URL = "https://api.ghkdqhrbals.org/health";

  assert.match(validateConfig(config).join("\n"), /readiness endpoint/);
});

test("health monitor config requires exact readiness path", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_URL = "https://api.ghkdqhrbals.org/foo/api/v1/health/readiness";

  assert.match(validateConfig(config).join("\n"), /readiness endpoint/);
});

test("health monitor config rejects dev health url for production", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_URL = "https://lowfidev.cloud/api/v1/health/readiness";
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

test("health monitor config rejects stale status thresholds that are too short or too long", () => {
  const tooShort = validConfig();
  tooShort.vars.STATUS_STALE_AFTER_SECONDS = "30";
  assert.match(validateConfig(tooShort).join("\n"), /STATUS_STALE_AFTER_SECONDS must be between 60 and 3600/);

  const tooLong = validConfig();
  tooLong.vars.STATUS_STALE_AFTER_SECONDS = "7200";
  assert.match(validateConfig(tooLong).join("\n"), /STATUS_STALE_AFTER_SECONDS must be between 60 and 3600/);
});

test("health monitor config rejects timeout values that are too slow for Worker cron", () => {
  const config = validConfig();
  config.vars.HEALTHCHECK_TIMEOUT_MS = "30000";
  config.vars.SLACK_TIMEOUT_MS = "30000";

  const errors = validateConfig(config).join("\n");

  assert.match(errors, /HEALTHCHECK_TIMEOUT_MS must be between 1000 and 25000/);
  assert.match(errors, /SLACK_TIMEOUT_MS must be between 1000 and 15000/);
});

test("health monitor config rejects invalid observability urls", () => {
  const config = validConfig();
  config.vars.OBSERVABILITY_URL = "ftp://grafana.example.com/d/backend";

  assert.match(validateConfig(config).join("\n"), /OBSERVABILITY_URL must be an HTTPS URL/);
});

test("health monitor config keeps Slack timeout limit aligned with runtime clamp", () => {
  const config = validConfig();
  config.vars.SLACK_TIMEOUT_MS = "20000";

  assert.match(validateConfig(config).join("\n"), /SLACK_TIMEOUT_MS must be between 1000 and 15000/);
});

test("health monitor config allows a dormant worker without a public entrypoint", () => {
  const config = validConfig();
  config.workers_dev = false;

  assert.deepEqual(validateConfig(config), []);
});

test("health monitor config requires an execution entrypoint when scheduled checks are enabled", () => {
  const config = validConfig();
  config.workers_dev = false;
  config.vars.SCHEDULED_CHECKS_ENABLED = "true";
  config.triggers = { crons: [] };

  assert.match(validateConfig(config).join("\n"), /workers_dev, routes, or a Cron Trigger/);
});

test("health monitor config requires Cloudflare observability logs", () => {
  const config = validConfig();
  config.observability.enabled = false;

  assert.match(validateConfig(config).join("\n"), /observability/);
});

test("health monitor config allows placeholder kv namespace only for pre-deploy readiness", () => {
  const config = validConfig();
  config.kv_namespaces[0].id = "replace-with-kv-namespace-id";

  assert.match(validateConfig(config).join("\n"), /KV namespace id is not configured/);
  assert.deepEqual(validateConfig(config, { allowPlaceholderKvNamespace: true }), []);
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
    workers_dev: false,
    observability: {
      enabled: true,
      head_sampling_rate: 1,
    },
    vars: {
      HEALTHCHECK_URL: "https://api.ghkdqhrbals.org/api/v1/health/readiness",
      SERVICE_NAME: "BuddyStudy backend",
      ENVIRONMENT_NAME: "production",
      FAILURE_THRESHOLD: "2",
      ALERT_REPEAT_SECONDS: "3600",
      STATUS_STALE_AFTER_SECONDS: "180",
      HEALTHCHECK_TIMEOUT_MS: "3000",
      SLACK_TIMEOUT_MS: "3000",
      SCHEDULED_CHECKS_ENABLED: "false",
    },
    kv_namespaces: [{ binding: "HEALTH_MONITOR_STATE", id: "kv-id" }],
  };
}
