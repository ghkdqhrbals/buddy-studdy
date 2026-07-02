import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(import.meta.dirname, "..", "..", "..");
const workflowPath = path.join(root, ".github", "workflows", "health-monitor.yml");

export function validateWorkflowText(text) {
  const errors = [];

  if (!/^\s*workflow_dispatch\s*:/m.test(text)) {
    errors.push("Health monitor workflow must be manually dispatchable for deployment.");
  }
  if (/^\s*schedule\s*:/m.test(text)) {
    errors.push("Health monitor workflow must not use GitHub Actions schedule for runtime health checks.");
  }
  if (/npm\s+run\s+smoke/.test(text)) {
    errors.push("Health monitor workflow must not run smoke health checks in GitHub Actions.");
  }
  if (/HEALTH_MONITOR_URL/.test(text)) {
    errors.push("Health monitor workflow must not depend on HEALTH_MONITOR_URL.");
  }
  if (!/wrangler\s+secret\s+put\s+SLACK_WEBHOOK_URL/.test(text)) {
    errors.push("Health monitor workflow must include Worker Slack secret sync.");
  }
  if (!/wrangler\s+secret\s+put\s+MANUAL_CHECK_TOKEN/.test(text)) {
    errors.push("Health monitor workflow must include Worker manual check token sync.");
  }
  if (/if:\s*env\.HEALTH_MONITOR_SLACK_WEBHOOK_URL\s*!=\s*''/m.test(text) || /if:\s*env\.MANUAL_CHECK_TOKEN\s*!=\s*''/m.test(text)) {
    errors.push("Slack alert secrets must be required, not optional, for health monitor deployment.");
  }
  const configureKvIndex = text.indexOf("npm run configure:kv");
  const validateBundleIndex = text.indexOf("npm run check");
  const syncSlackSecretIndex = text.indexOf("wrangler secret put SLACK_WEBHOOK_URL");
  const deployIndex = text.indexOf("npm run deploy");
  if (configureKvIndex !== -1 && validateBundleIndex !== -1 && configureKvIndex > validateBundleIndex) {
    errors.push("Health monitor workflow must configure KV namespace before validating the Worker bundle.");
  }
  if (syncSlackSecretIndex !== -1 && deployIndex !== -1 && syncSlackSecretIndex > deployIndex) {
    errors.push("Health monitor workflow must sync Worker secrets before deploying.");
  }

  return errors;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = validateWorkflowText(fs.readFileSync(workflowPath, "utf8"));
  if (errors.length > 0) {
    console.error(errors.join("\n"));
    process.exit(1);
  }
  console.log("Health monitor workflow is deploy-only.");
}
