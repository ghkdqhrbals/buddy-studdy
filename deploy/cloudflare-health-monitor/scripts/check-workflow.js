import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(import.meta.dirname, "..", "..", "..");
const workflowPath = path.join(root, ".github", "workflows", "health-monitor.yml");
const workflowsDir = path.join(root, ".github", "workflows");
const deployTemplateDir = path.join(root, "docs", "deploy-repo-template");
const backendHealthProbePattern = /(?:https?:\/\/[^\s"'\\]+)?(?:\/api\/v1\/health(?:\/readiness)?|(?<!\/api)\/health(?:\/readiness)?)\b/;
const localRuntimeHealthProbePattern = /docker\s+(?:exec|run)\b[^\n]*(?:curl|wget|http)\b[^\n]*\/api\/health\b/;
const dockerHealthInspectPattern = /docker\s+inspect\b[^\n]*(?:\.State\.Health|Health\.Status)\b/;
const dockerHealthMetadataPattern = /docker\s+(?:container\s+)?(?:run|create)\b[^\n]*--health-[a-z-]+\b/;
const dockerComposeWaitPattern = /docker\s+compose\b[^\n]*(?:\bup\b[^\n]*\s--wait\b|\bwait\b)/;
const dockerHealthFilterPattern = /docker\s+(?:compose\s+)?ps\b[^\n]*(?:--filter\s+health=|health=|\.Health|Health\.Status)\b/;
const dockerStatusHealthPattern = /docker\s+(?:compose\s+)?ps\b[^\n]*\.Status\b[^\n]*(?:healthy|unhealthy)\b/;
const runtimeHealthProbePattern = /(?:curl|wget|http)\b[^\n]*(?:\/api\/health|(?<!\/api)\/health)\b/;
const healthMonitorManualCheckPattern = /(?:buddystudy-health-monitor|workers\.dev)[^\n]*\/check\b/;
const healthMonitorManualCheckScriptPattern = /npm\s+run\s+manual:check\b|manual-check\.js/;
const healthMonitorWorkflowName = "Deploy Health Monitor Worker";
const githubCliAuthAction = "verify GitHub CLI auth with `gh auth status`; if invalid, run `gh auth login -h github.com`, then rerun readiness";
const wranglerAuthAction =
  "run `npx wrangler login`, then `npm run bootstrap:cloudflare` to create KV and print GitHub secret commands";
export const requiredHealthMonitorGitHubSecrets = [
  "CLOUDFLARE_API_TOKEN",
  "CLOUDFLARE_ACCOUNT_ID",
  "HEALTH_MONITOR_KV_NAMESPACE_ID",
  "HEALTH_MONITOR_SLACK_WEBHOOK_URL",
  "HEALTH_MONITOR_MANUAL_CHECK_TOKEN",
];
const cloudflareSetupSecrets = new Set([
  "CLOUDFLARE_API_TOKEN",
  "CLOUDFLARE_ACCOUNT_ID",
  "HEALTH_MONITOR_KV_NAMESPACE_ID",
]);
const cloudflareSetupAction =
  "run `npm run bootstrap:cloudflare` after Wrangler login, then set the printed GitHub secrets";

function normalizeShellContinuations(text) {
  return text.replace(/\\\r?\n\s*/g, " ");
}

export function validateNoActionsRuntimeHealthChecks(text, fileName = "workflow") {
  const errors = [];
  const scanText = normalizeShellContinuations(text);

  if (backendHealthProbePattern.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not directly call backend health endpoints.`);
  }
  if (runtimeHealthProbePattern.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not run runtime health probes.`);
  }
  if (
    localRuntimeHealthProbePattern.test(scanText) ||
    dockerHealthInspectPattern.test(scanText) ||
    dockerHealthMetadataPattern.test(scanText) ||
    dockerComposeWaitPattern.test(scanText) ||
    dockerHealthFilterPattern.test(scanText) ||
    dockerStatusHealthPattern.test(scanText)
  ) {
    errors.push(`${fileName}: GitHub Actions workflows must not run container health probes.`);
  }
  if (/npm\s+run\s+smoke/.test(scanText) || /smoke-check\.js/.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not run health monitor smoke checks.`);
  }
  if (healthMonitorManualCheckScriptPattern.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not run health monitor manual checks.`);
  }
  if (healthMonitorManualCheckPattern.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not call health monitor manual check endpoints.`);
  }
  if (/HEALTH_MONITOR_URL/.test(scanText)) {
    errors.push(`${fileName}: GitHub Actions workflows must not configure health monitor check URLs.`);
  }

  return errors;
}

export function validateWorkflowText(text) {
  const errors = [];

  if (!/^\s*workflow_dispatch\s*:/m.test(text)) {
    errors.push("Health monitor workflow must be manually dispatchable for deployment.");
  }
  if (/^\s*schedule\s*:/m.test(text)) {
    errors.push("Health monitor workflow must not use GitHub Actions schedule for runtime health checks.");
  }
  if (/npm\s+run\s+smoke/.test(text) || /smoke-check\.js/.test(text)) {
    errors.push("Health monitor workflow must not run smoke health checks in GitHub Actions.");
  }
  if (healthMonitorManualCheckScriptPattern.test(text)) {
    errors.push("Health monitor workflow must not run manual health checks in GitHub Actions.");
  }
  if (/HEALTH_MONITOR_URL/.test(text)) {
    errors.push("Health monitor workflow must not depend on HEALTH_MONITOR_URL.");
  }
  errors.push(...validateNoActionsRuntimeHealthChecks(text));
  if (!/wrangler\s+secret\s+put\s+SLACK_WEBHOOK_URL/.test(text)) {
    errors.push("Health monitor workflow must include Worker Slack secret sync.");
  }
  if (!/wrangler\s+secret\s+put\s+MANUAL_CHECK_TOKEN/.test(text)) {
    errors.push("Health monitor workflow must include Worker manual check token sync.");
  }
  const optionalSecretSyncPattern =
    /if:\s*(?:\$\{\{\s*)?[^}\n]*(?:env\.HEALTH_MONITOR_SLACK_WEBHOOK_URL|env\.(?:HEALTH_MONITOR_)?MANUAL_CHECK_TOKEN)\s*!=\s*''/m;
  if (optionalSecretSyncPattern.test(text)) {
    errors.push("Slack alert secrets must be required, not optional, for health monitor deployment.");
  }
  const configureKvIndex = text.indexOf("npm run configure:kv");
  const testIndex = text.indexOf("npm test");
  const validateBundleIndex = text.indexOf("npm run check");
  const syncSlackSecretIndex = text.indexOf("wrangler secret put SLACK_WEBHOOK_URL");
  const deployIndex = text.indexOf("npm run deploy");
  if (deployIndex !== -1 && testIndex === -1) {
    errors.push("Health monitor workflow must run npm test before deploying.");
  }
  if (testIndex !== -1 && deployIndex !== -1 && testIndex > deployIndex) {
    errors.push("Health monitor workflow must run npm test before deploying.");
  }
  if (deployIndex !== -1 && validateBundleIndex === -1) {
    errors.push("Health monitor workflow must run npm run check before deploying.");
  }
  if (validateBundleIndex !== -1 && deployIndex !== -1 && validateBundleIndex > deployIndex) {
    errors.push("Health monitor workflow must run npm run check before deploying.");
  }
  if (configureKvIndex === -1) {
    errors.push("Health monitor workflow must configure KV namespace before deployment.");
  }
  if (configureKvIndex !== -1 && validateBundleIndex !== -1 && configureKvIndex > validateBundleIndex) {
    errors.push("Health monitor workflow must configure KV namespace before validating the Worker bundle.");
  }
  if (syncSlackSecretIndex !== -1 && deployIndex !== -1 && syncSlackSecretIndex > deployIndex) {
    errors.push("Health monitor workflow must sync Worker secrets before deploying.");
  }

  return errors;
}

export function buildDeploymentReadinessReport({
  localWorkflowExists,
  localWorkflowErrors = [],
  localWorkerConfigErrors = [],
  remoteWorkflowNames = [],
  remoteWorkflowError = null,
  hasGitHubSlackSecret,
  requiredGitHubSecrets,
  hasCloudflareApiToken,
  hasWranglerAuth = null,
}) {
  const blockers = [];
  const nextActions = [];
  const remoteWorkflowStateUnknown = remoteWorkflowNames == null;
  const remoteWorkflowExists = !remoteWorkflowStateUnknown && remoteWorkflowNames.includes(healthMonitorWorkflowName);
  const secretStates = requiredGitHubSecrets ?? {
    HEALTH_MONITOR_SLACK_WEBHOOK_URL: hasGitHubSlackSecret,
  };
  const secretNames = requiredGitHubSecrets ? requiredHealthMonitorGitHubSecrets : Object.keys(secretStates);
  const hasAllRequiredSecrets = secretNames.every((name) => secretStates[name] === true);

  if (!localWorkflowExists) {
    blockers.push("Local .github/workflows/health-monitor.yml is missing.");
    nextActions.push("restore .github/workflows/health-monitor.yml before configuring Slack alerts");
  }
  for (const error of localWorkflowErrors) {
    blockers.push(`Local .github/workflows/health-monitor.yml is invalid: ${error}`);
  }
  if (localWorkflowErrors.length > 0) {
    nextActions.push("fix local `.github/workflows/health-monitor.yml` validation errors before deployment");
  }
  for (const error of localWorkerConfigErrors) {
    blockers.push(`Local deploy/cloudflare-health-monitor/wrangler.jsonc is invalid: ${error}`);
  }
  if (localWorkerConfigErrors.length > 0) {
    nextActions.push("fix local `deploy/cloudflare-health-monitor/wrangler.jsonc` validation errors before deployment");
  }
  if (localWorkflowExists && remoteWorkflowStateUnknown) {
    blockers.push(`Could not verify Deploy Health Monitor Worker on the remote default branch${formatReason(remoteWorkflowError)}.`);
    nextActions.push(githubCliAuthAction);
  } else if (localWorkflowExists && !remoteWorkflowExists) {
    blockers.push(
      "Deploy Health Monitor Worker is not present on the remote default branch, so Worker secrets cannot be synced from GitHub Actions.",
    );
    nextActions.push("merge or push `.github/workflows/health-monitor.yml` to the remote default branch");
    if (hasAllRequiredSecrets) {
      nextActions.push("dispatch Deploy Health Monitor Worker after the workflow is available on the remote default branch");
    }
  }
  for (const name of secretNames) {
    const state = secretStates[name];
    if (state == null) {
      blockers.push(`Could not verify ${name} in GitHub Actions secrets.`);
      nextActions.push(githubCliAuthAction);
    } else if (!state) {
      blockers.push(`${name} is missing from GitHub Actions secrets.`);
      nextActions.push(`set ${name} in the buddy-studdy repository secrets`);
      if (cloudflareSetupSecrets.has(name)) {
        nextActions.push(cloudflareSetupAction);
      }
    }
  }
  if (hasWranglerAuth === false && secretNames.some((name) => cloudflareSetupSecrets.has(name) && secretStates[name] === false)) {
    nextActions.push(wranglerAuthAction);
  }
  if (localWorkflowExists && localWorkflowErrors.length === 0 && localWorkerConfigErrors.length === 0 && remoteWorkflowExists && hasAllRequiredSecrets) {
    nextActions.push("dispatch Deploy Health Monitor Worker to sync Cloudflare Worker secrets");
  } else if (hasCloudflareApiToken) {
    nextActions.push("alternatively run `wrangler secret put SLACK_WEBHOOK_URL` with CLOUDFLARE_API_TOKEN");
  }

  return {
    ready: blockers.length === 0,
    blockers,
    nextActions: [...new Set(nextActions)],
  };
}

function formatReason(reason) {
  const text = typeof reason === "string" ? reason.trim() : "";
  return text ? `: ${text}` : "";
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = validateWorkflowText(fs.readFileSync(workflowPath, "utf8"));
  for (const entry of fs.readdirSync(workflowsDir)) {
    if (!entry.endsWith(".yml") && !entry.endsWith(".yaml")) {
      continue;
    }
    const text = fs.readFileSync(path.join(workflowsDir, entry), "utf8");
    errors.push(...validateNoActionsRuntimeHealthChecks(text, entry));
  }
  for (const entry of fs.readdirSync(deployTemplateDir)) {
    if (!entry.endsWith(".yml") && !entry.endsWith(".yaml")) {
      continue;
    }
    const text = fs.readFileSync(path.join(deployTemplateDir, entry), "utf8");
    errors.push(...validateNoActionsRuntimeHealthChecks(text, path.join("docs/deploy-repo-template", entry)));
  }
  if (errors.length > 0) {
    console.error(errors.join("\n"));
    process.exit(1);
  }
  console.log("Health monitor workflow is deploy-only.");
}
