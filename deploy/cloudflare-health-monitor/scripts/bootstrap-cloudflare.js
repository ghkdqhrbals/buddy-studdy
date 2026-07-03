import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { configureKvNamespace } from "./set-kv-namespace.js";

const repo = process.env.HEALTH_MONITOR_REPO || "ghkdqhrbals/buddy-studdy";

export function parseWranglerAccountId(output) {
  const patterns = [
    /Account\s+ID\s*[:│|]\s*([a-f0-9]{32})/i,
    /account_id\s*=\s*["']?([a-f0-9]{32})["']?/i,
    /accountId\s*[:=]\s*["']?([a-f0-9]{32})["']?/i,
  ];
  for (const pattern of patterns) {
    const match = output.match(pattern);
    if (match) {
      return match[1];
    }
  }
  const fallback = output.match(/\b([a-f0-9]{32})\b/i);
  return fallback?.[1] ?? null;
}

export function parseKvNamespaceId(output) {
  const patterns = [
    /"id"\s*:\s*"([^"]+)"/,
    /id\s*=\s*"([^"]+)"/,
    /HEALTH_MONITOR_STATE[^\n]+([a-f0-9]{32})/i,
  ];
  for (const pattern of patterns) {
    const match = output.match(pattern);
    if (match) {
      return match[1];
    }
  }
  return null;
}

export function parseExistingKvNamespaceId(output, title = "HEALTH_MONITOR_STATE") {
  try {
    const namespaces = JSON.parse(output);
    const namespace = Array.isArray(namespaces)
      ? namespaces.find((candidate) => candidate?.title === title || candidate?.binding === title)
      : null;
    return typeof namespace?.id === "string" && namespace.id.trim() ? namespace.id : null;
  } catch {
    return null;
  }
}

export function buildSecretCommands({ accountId, namespaceId, repository = repo }) {
  return [
    `gh secret set CLOUDFLARE_ACCOUNT_ID --repo ${repository} --body ${shellQuote(accountId)}`,
    `gh secret set HEALTH_MONITOR_KV_NAMESPACE_ID --repo ${repository} --body ${shellQuote(namespaceId)}`,
    `gh secret set CLOUDFLARE_API_TOKEN --repo ${repository}`,
  ];
}

export function buildSecretPlan({ accountId, namespaceId, hasCloudflareApiToken, repository = repo }) {
  return [
    { name: "CLOUDFLARE_ACCOUNT_ID", value: accountId, requiredValue: true, repository },
    { name: "HEALTH_MONITOR_KV_NAMESPACE_ID", value: namespaceId, requiredValue: true, repository },
    {
      name: "CLOUDFLARE_API_TOKEN",
      value: hasCloudflareApiToken ? process.env.CLOUDFLARE_API_TOKEN : null,
      requiredValue: false,
      repository,
    },
  ];
}

export function buildDispatchCommand({ repository = repo } = {}) {
  return ["workflow", "run", "health-monitor.yml", "--repo", repository];
}

export function buildRunListCommand({ repository = repo } = {}) {
  return [
    "run",
    "list",
    "--workflow",
    "health-monitor.yml",
    "--repo",
    repository,
    "--json",
    "databaseId,status,conclusion,createdAt",
    "--limit",
    "10",
  ];
}

export function parseLatestRunId(output, { createdAfter } = {}) {
  try {
    const runs = JSON.parse(output);
    const minCreatedAt = createdAfter ? Date.parse(createdAfter) : null;
    const run = Array.isArray(runs)
      ? runs.find((candidate) => {
          if (!Number.isInteger(candidate?.databaseId)) {
            return false;
          }
          if (!minCreatedAt) {
            return true;
          }
          const createdAt = Date.parse(candidate?.createdAt);
          return Number.isFinite(createdAt) && createdAt >= minCreatedAt;
        })
      : null;
    return Number.isInteger(run?.databaseId) ? run.databaseId : null;
  } catch {
    return null;
  }
}

export function buildRunWatchCommand(runId, { repository = repo } = {}) {
  return ["run", "watch", String(runId), "--repo", repository, "--exit-status"];
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function commandErrorMessage(error) {
  const stderr = Buffer.isBuffer(error?.stderr) ? error.stderr.toString("utf8") : error?.stderr;
  const stdout = Buffer.isBuffer(error?.stdout) ? error.stdout.toString("utf8") : error?.stdout;
  return String(stderr || stdout || error?.message || "unknown error")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(0, 4)
    .join(" ");
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function findDispatchedRunId({ root, createdAfter, attempts = 10, delayMs = 2000 }) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    const runsOutput = execFileSync("gh", buildRunListCommand(), {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "inherit"],
    });
    const runId = parseLatestRunId(runsOutput, { createdAfter });
    if (runId) {
      return runId;
    }
    if (attempt < attempts) {
      sleep(delayMs);
    }
  }
  return null;
}

function createOrFindKvNamespace(root) {
  try {
    const output = execFileSync("npx", ["wrangler", "kv", "namespace", "create", "HEALTH_MONITOR_STATE"], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return parseKvNamespaceId(output);
  } catch (error) {
    const message = commandErrorMessage(error);
    if (!/already exists/i.test(message)) {
      throw error;
    }
    const output = execFileSync("npx", ["wrangler", "kv", "namespace", "list"], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return parseExistingKvNamespaceId(output);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = path.resolve(import.meta.dirname, "..");
  const configPath = path.join(root, "wrangler.jsonc");
  const shouldSetGitHubSecrets = process.argv.includes("--set-github-secrets");
  const shouldDispatchWorkflow = process.argv.includes("--dispatch-workflow");
  const shouldWatchWorkflow = process.argv.includes("--watch-workflow");
  if (shouldDispatchWorkflow && !shouldSetGitHubSecrets) {
    console.error("`--dispatch-workflow` requires `--set-github-secrets` so the generated KV namespace id is available to the deploy workflow.");
    process.exit(1);
  }
  if (shouldWatchWorkflow && !shouldDispatchWorkflow) {
    console.error("`--watch-workflow` requires `--dispatch-workflow` so there is a deployment run to watch.");
    process.exit(1);
  }

  let whoamiOutput;
  try {
    whoamiOutput = execFileSync("npx", ["wrangler", "whoami"], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    console.error(`Wrangler is not authenticated or unavailable: ${commandErrorMessage(error)}`);
    console.error("Run `npx wrangler login`, then rerun `npm run bootstrap:cloudflare`.");
    process.exit(1);
  }

  const accountId = parseWranglerAccountId(whoamiOutput);
  if (!accountId) {
    console.error("Could not parse Cloudflare account id from `npx wrangler whoami` output.");
    process.exit(1);
  }

  let namespaceId;
  try {
    namespaceId = createOrFindKvNamespace(root);
  } catch (error) {
    console.error(`Could not create or find HEALTH_MONITOR_STATE KV namespace: ${commandErrorMessage(error)}`);
    process.exit(1);
  }

  if (!namespaceId) {
    console.error("Could not parse HEALTH_MONITOR_STATE namespace id from Wrangler output.");
    process.exit(1);
  }

  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const updated = configureKvNamespace(config, namespaceId);
  fs.writeFileSync(configPath, `${JSON.stringify(updated, null, 2)}\n`);

  console.log("Cloudflare health monitor bootstrap values:");
  console.log(`- CLOUDFLARE_ACCOUNT_ID=${accountId}`);
  console.log(`- HEALTH_MONITOR_KV_NAMESPACE_ID=${namespaceId}`);
  console.log("");
  console.log("Run these GitHub secret commands:");
  for (const command of buildSecretCommands({ accountId, namespaceId })) {
    console.log(command);
  }
  if (shouldSetGitHubSecrets) {
    console.log("");
    console.log(`Setting GitHub secrets in ${repo}...`);
    for (const secret of buildSecretPlan({
      accountId,
      namespaceId,
      hasCloudflareApiToken: Boolean(process.env.CLOUDFLARE_API_TOKEN),
    })) {
      if (secret.requiredValue || secret.value) {
        execFileSync("gh", ["secret", "set", secret.name, "--repo", secret.repository, "--body", secret.value], {
          cwd: root,
          stdio: ["ignore", "inherit", "inherit"],
        });
      } else {
        console.log(`Skipped ${secret.name}; set CLOUDFLARE_API_TOKEN env or run: gh secret set CLOUDFLARE_API_TOKEN --repo ${secret.repository}`);
      }
    }
  }
  if (shouldDispatchWorkflow) {
    console.log("");
    console.log("Dispatching Deploy Health Monitor Worker workflow...");
    const dispatchedAfter = new Date(Date.now() - 5000).toISOString();
    execFileSync("gh", buildDispatchCommand(), {
      cwd: root,
      stdio: ["ignore", "inherit", "inherit"],
    });
    if (shouldWatchWorkflow) {
      const runId = findDispatchedRunId({ root, createdAfter: dispatchedAfter });
      if (!runId) {
        console.error("Could not find the dispatched Deploy Health Monitor Worker run.");
        process.exit(1);
      }
      console.log(`Watching Deploy Health Monitor Worker run ${runId}...`);
      execFileSync("gh", buildRunWatchCommand(runId), {
        cwd: root,
        stdio: ["ignore", "inherit", "inherit"],
      });
    }
  }
  console.log("");
  console.log(shouldWatchWorkflow ? "Deployment workflow completed. Then run `npm run readiness -- --json`." : shouldDispatchWorkflow ? "Then watch the GitHub Actions run until deployment completes." : "Then run `npm run readiness -- --json`.");
}
