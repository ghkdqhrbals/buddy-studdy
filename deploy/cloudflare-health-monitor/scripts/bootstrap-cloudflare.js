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

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = path.resolve(import.meta.dirname, "..");
  const configPath = path.join(root, "wrangler.jsonc");
  const shouldSetGitHubSecrets = process.argv.includes("--set-github-secrets");

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

  let kvOutput;
  try {
    kvOutput = execFileSync("npx", ["wrangler", "kv", "namespace", "create", "HEALTH_MONITOR_STATE"], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    console.error(`Could not create HEALTH_MONITOR_STATE KV namespace: ${commandErrorMessage(error)}`);
    process.exit(1);
  }

  const namespaceId = parseKvNamespaceId(kvOutput);
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
  console.log("");
  console.log("Then run `npm run readiness -- --json`.");
}
