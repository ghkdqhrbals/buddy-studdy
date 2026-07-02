import assert from "node:assert/strict";
import { test } from "node:test";
import { validateConfig } from "../scripts/check-config.js";

test("health monitor config accepts required production shape", () => {
  assert.deepEqual(validateConfig(validConfig()), []);
});

test("health monitor config rejects missing runtime essentials", () => {
  const config = validConfig();
  config.triggers.crons = [];
  config.kv_namespaces[0].id = "replace-with-kv-namespace-id";
  config.vars.HEALTHCHECK_URL = "http://api.lowfidev.cloud/api/v1/health/readiness";
  config.vars.FAILURE_THRESHOLD = "0";
  config.vars.ALERT_REPEAT_SECONDS = "bad";
  config.vars.HEALTHCHECK_TIMEOUT_MS = "";

  const errors = validateConfig(config).join("\n");

  assert.match(errors, /Cron Trigger/);
  assert.match(errors, /KV namespace/);
  assert.match(errors, /HTTPS URL/);
  assert.match(errors, /FAILURE_THRESHOLD/);
  assert.match(errors, /ALERT_REPEAT_SECONDS/);
  assert.match(errors, /HEALTHCHECK_TIMEOUT_MS/);
});

function validConfig() {
  return {
    name: "buddystudy-health-monitor",
    main: "src/index.js",
    triggers: { crons: ["*/5 * * * *"] },
    vars: {
      HEALTHCHECK_URL: "https://api.lowfidev.cloud/api/v1/health/readiness",
      FAILURE_THRESHOLD: "2",
      ALERT_REPEAT_SECONDS: "3600",
      HEALTHCHECK_TIMEOUT_MS: "8000",
    },
    kv_namespaces: [{ binding: "HEALTH_MONITOR_STATE", id: "kv-id" }],
  };
}
