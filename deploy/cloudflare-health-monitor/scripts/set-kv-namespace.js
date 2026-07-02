import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const namespaceId = process.argv[2]?.trim();

if (!namespaceId) {
  console.error("Usage: npm run configure:kv -- <HEALTH_MONITOR_STATE namespace id>");
  process.exit(1);
}

const root = path.resolve(import.meta.dirname, "..");
const configPath = path.join(root, "wrangler.jsonc");
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
const namespaces = config.kv_namespaces || [];
const index = namespaces.findIndex((item) => item.binding === "HEALTH_MONITOR_STATE");

if (index === -1) {
  namespaces.push({ binding: "HEALTH_MONITOR_STATE", id: namespaceId });
} else {
  namespaces[index] = { ...namespaces[index], id: namespaceId };
}

config.kv_namespaces = namespaces;
fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`);
console.log(`Configured HEALTH_MONITOR_STATE KV namespace id in ${path.relative(process.cwd(), configPath)}.`);
