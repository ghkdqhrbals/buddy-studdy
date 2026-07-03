import process from "node:process";
import { execFileSync } from "node:child_process";

export const requiredWorkerSecrets = ["SLACK_WEBHOOK_URL", "MANUAL_CHECK_TOKEN"];

export function parseSecretNames(output) {
  try {
    const parsed = JSON.parse(output);
    const items = Array.isArray(parsed) ? parsed : parsed?.secrets;
    if (!Array.isArray(items)) return [];
    return items
      .map((item) => {
        if (typeof item === "string") return item;
        return item?.name || item?.key || item?.binding || null;
      })
      .filter((name) => typeof name === "string" && name.trim().length > 0)
      .map((name) => name.trim());
  } catch {
    return [];
  }
}

export function parseDeploymentCount(output) {
  try {
    const parsed = JSON.parse(output);
    if (Array.isArray(parsed)) return parsed.length;
    if (Array.isArray(parsed?.deployments)) return parsed.deployments.length;
    if (Array.isArray(parsed?.items)) return parsed.items.length;
    return 0;
  } catch {
    return 0;
  }
}

export function buildWorkerStatusReport({
  deploymentsOk,
  deploymentsError = null,
  deploymentCount = 0,
  secretNames = [],
  secretsError = null,
} = {}) {
  const blockers = [];
  const nextActions = [];
  const deployed = deploymentsOk === true && deploymentCount > 0;

  if (!deploymentsOk) {
    blockers.push(`Cloudflare Worker is not deployed${formatReason(deploymentsError)}.`);
    nextActions.push("set CLOUDFLARE_API_TOKEN in GitHub Actions secrets, then dispatch Deploy Health Monitor Worker");
  } else if (!deployed) {
    blockers.push("Cloudflare Worker has no deployments.");
    nextActions.push("dispatch Deploy Health Monitor Worker");
  }

  if (deployed) {
    const presentSecrets = new Set(secretNames);
    const missingSecrets = requiredWorkerSecrets.filter((name) => !presentSecrets.has(name));
    if (secretsError) {
      blockers.push(`Could not verify Cloudflare Worker secrets${formatReason(secretsError)}.`);
      nextActions.push("dispatch Deploy Health Monitor Worker to sync Worker secrets from GitHub Actions secrets");
    } else if (missingSecrets.length > 0) {
      blockers.push(`Cloudflare Worker secrets are missing: ${missingSecrets.join(", ")}.`);
      nextActions.push("dispatch Deploy Health Monitor Worker to sync Worker secrets from GitHub Actions secrets");
    }
  }

  return {
    ready: blockers.length === 0,
    deployed,
    deploymentCount,
    requiredWorkerSecrets,
    presentWorkerSecrets: secretNames,
    blockers,
    nextActions: [...new Set(nextActions)],
  };
}

function readDeployments() {
  try {
    const output = execFileSync("npx", ["wrangler", "deployments", "list", "--json"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { ok: true, count: parseDeploymentCount(output), error: null };
  } catch (error) {
    return { ok: false, count: 0, error: commandErrorMessage(error) };
  }
}

function readSecrets() {
  try {
    const output = execFileSync("npx", ["wrangler", "secret", "list", "--format", "json"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { names: parseSecretNames(output), error: null };
  } catch (error) {
    return { names: [], error: commandErrorMessage(error) };
  }
}

function commandErrorMessage(error) {
  const stderr = Buffer.isBuffer(error?.stderr) ? error.stderr.toString("utf8") : error?.stderr;
  const stdout = Buffer.isBuffer(error?.stdout) ? error.stdout.toString("utf8") : error?.stdout;
  return stripAnsi(String(stderr || stdout || error?.message || "unknown error"))
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(0, 4)
    .join(" ");
}

function stripAnsi(value) {
  return value.replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, "");
}

function formatReason(reason) {
  const text = typeof reason === "string" ? reason.trim() : "";
  return text ? `: ${text}` : "";
}

function printSection(title, items) {
  if (items.length === 0) return;
  console.log(title);
  for (const item of items) {
    console.log(`- ${item}`);
  }
}

if (process.argv[1]?.endsWith("/worker-status.js")) {
  const deployments = readDeployments();
  const secrets = deployments.ok ? readSecrets() : { names: [], error: null };
  const report = buildWorkerStatusReport({
    deploymentsOk: deployments.ok,
    deploymentsError: deployments.error,
    deploymentCount: deployments.count,
    secretNames: secrets.names,
    secretsError: secrets.error,
  });

  if (process.argv.includes("--json")) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    console.log(report.ready ? "Health monitor Worker status: ready" : "Health monitor Worker status: blocked");
    printSection("Blockers:", report.blockers);
    printSection("Next actions:", report.nextActions);
  }
  process.exit(report.ready ? 0 : 1);
}
