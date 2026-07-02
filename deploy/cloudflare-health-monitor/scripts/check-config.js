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
  if (!hasPublicEntrypoint(config)) {
    errors.push("Health monitor Worker must expose workers_dev or routes for manual status checks.");
  }
  if (config.observability?.enabled !== true) {
    errors.push("Health monitor Worker observability must be enabled so Cron checks and Slack alert failures are logged.");
  }
  if (config.observability?.head_sampling_rate !== 1) {
    errors.push("Health monitor Worker observability sampling must be 1 so every Cron check and Slack alert failure is logged.");
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
  } else if (environmentName(config) === "production" && healthcheckHost(config.vars.HEALTHCHECK_URL) !== "api.ghkdqhrbals.org") {
    errors.push("Production HEALTHCHECK_URL must point to `api.ghkdqhrbals.org`.");
  }
  if (!nonBlankString(config.vars?.SERVICE_NAME)) {
    errors.push("SERVICE_NAME must be configured so Slack alerts identify the affected service.");
  }
  if (!nonBlankString(config.vars?.ENVIRONMENT_NAME)) {
    errors.push("ENVIRONMENT_NAME must be configured so Slack alerts identify the affected environment.");
  }
  if (!positiveInt(config.vars?.FAILURE_THRESHOLD)) {
    errors.push("FAILURE_THRESHOLD must be a positive integer.");
  } else if (Number(config.vars.FAILURE_THRESHOLD) > 2) {
    errors.push("FAILURE_THRESHOLD must be 1 or 2 for fast outage alerts.");
  }
  if (!positiveInt(config.vars?.ALERT_REPEAT_SECONDS)) {
    errors.push("ALERT_REPEAT_SECONDS must be a positive integer.");
  }
  if (!positiveInt(config.vars?.HEALTHCHECK_TIMEOUT_MS)) {
    errors.push("HEALTHCHECK_TIMEOUT_MS must be a positive integer.");
  } else if (!boundedInt(config.vars.HEALTHCHECK_TIMEOUT_MS, 1_000, 25_000)) {
    errors.push("HEALTHCHECK_TIMEOUT_MS must be between 1000 and 25000.");
  }
  if (!positiveInt(config.vars?.SLACK_TIMEOUT_MS)) {
    errors.push("SLACK_TIMEOUT_MS must be a positive integer.");
  } else if (!boundedInt(config.vars.SLACK_TIMEOUT_MS, 1_000, 25_000)) {
    errors.push("SLACK_TIMEOUT_MS must be between 1000 and 25000.");
  }

  return errors;
}

function positiveInt(value) {
  return Number.isInteger(Number(value)) && Number(value) > 0;
}

function boundedInt(value, min, max) {
  const number = Number(value);
  return Number.isInteger(number) && number >= min && number <= max;
}

function nonBlankString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function hasPublicEntrypoint(config) {
  if (config.workers_dev === true) {
    return true;
  }
  return Array.isArray(config.routes) && config.routes.length > 0;
}

function healthcheckHost(value) {
  try {
    return new URL(value).hostname;
  } catch (_error) {
    return "";
  }
}

function environmentName(config) {
  return typeof config.vars?.ENVIRONMENT_NAME === "string" ? config.vars.ENVIRONMENT_NAME.trim().toLowerCase() : "";
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
