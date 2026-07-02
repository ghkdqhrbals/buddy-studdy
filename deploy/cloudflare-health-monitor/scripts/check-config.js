import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const configPath = path.join(root, "wrangler.jsonc");
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));

const namespace = config.kv_namespaces?.find((item) => item.binding === "HEALTH_MONITOR_STATE");
if (!namespace?.id || namespace.id === "replace-with-kv-namespace-id") {
  console.error(
    "HEALTH_MONITOR_STATE KV namespace id is not configured in wrangler.jsonc. " +
      "Create it with `npx wrangler kv namespace create HEALTH_MONITOR_STATE`, " +
      "then run `npm run configure:kv -- <namespace_id>`.",
  );
  process.exit(1);
}

if (!config.vars?.HEALTHCHECK_URL?.startsWith("https://")) {
  console.error("HEALTHCHECK_URL must be an HTTPS URL.");
  process.exit(1);
}

console.log("Cloudflare health monitor config looks valid.");
