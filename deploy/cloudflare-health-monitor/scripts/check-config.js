import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(import.meta.dirname, "..");
const configPath = path.join(root, "wrangler.jsonc");

export function validateConfig(config) {
  const errors = [];

  if (config.name !== "buddystudy-health-monitor") {
    errors.push("Worker name must be buddystudy-health-monitor.");
  }
  if (config.main !== "src/index.js") {
    errors.push("Worker main must be src/index.js.");
  }
  const crons = config.triggers?.crons;
  if (!Array.isArray(crons) || crons.length === 0) {
    errors.push("At least one Cloudflare Cron Trigger must be configured.");
  } else if (!crons.includes("* * * * *")) {
    errors.push("Health monitor must include the 1-minute cron `* * * * *` for fast outage alerts.");
  }

  const namespace = config.kv_namespaces?.find((item) => item.binding === "HEALTH_MONITOR_STATE");
  if (!namespace?.id || namespace.id === "replace-with-kv-namespace-id") {
    errors.push(
      "HEALTH_MONITOR_STATE KV namespace id is not configured in wrangler.jsonc. " +
        "Create it with `npx wrangler kv namespace create HEALTH_MONITOR_STATE`, " +
        "then run `npm run configure:kv -- <namespace_id>`.",
    );
  }

  if (!config.vars?.HEALTHCHECK_URL?.startsWith("https://")) {
    errors.push("HEALTHCHECK_URL must be an HTTPS URL.");
  } else if (!config.vars.HEALTHCHECK_URL.endsWith("/api/v1/health/readiness")) {
    errors.push("HEALTHCHECK_URL must point to the backend readiness endpoint `/api/v1/health/readiness`.");
  }
  if (!positiveInt(config.vars?.FAILURE_THRESHOLD)) {
    errors.push("FAILURE_THRESHOLD must be a positive integer.");
  }
  if (!positiveInt(config.vars?.ALERT_REPEAT_SECONDS)) {
    errors.push("ALERT_REPEAT_SECONDS must be a positive integer.");
  }
  if (!positiveInt(config.vars?.HEALTHCHECK_TIMEOUT_MS)) {
    errors.push("HEALTHCHECK_TIMEOUT_MS must be a positive integer.");
  }

  return errors;
}

function positiveInt(value) {
  return Number.isInteger(Number(value)) && Number(value) > 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const errors = validateConfig(config);
  if (errors.length > 0) {
    console.error(errors.join("\n"));
    process.exit(1);
  }
  console.log("Cloudflare health monitor config looks valid.");
}
