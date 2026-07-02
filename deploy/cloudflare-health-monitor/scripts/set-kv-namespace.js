import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export function configureKvNamespace(config, namespaceId) {
  const id = namespaceId?.trim();
  if (!id) {
    throw new Error("HEALTH_MONITOR_STATE namespace id is required.");
  }

  const namespaces = [...(config.kv_namespaces || [])];
  const index = namespaces.findIndex((item) => item.binding === "HEALTH_MONITOR_STATE");

  if (index === -1) {
    namespaces.push({ binding: "HEALTH_MONITOR_STATE", id });
  } else {
    namespaces[index] = { ...namespaces[index], id };
  }

  return { ...config, kv_namespaces: namespaces };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const namespaceId = process.argv[2]?.trim();

  if (!namespaceId) {
    console.error("Usage: npm run configure:kv -- <HEALTH_MONITOR_STATE namespace id>");
    process.exit(1);
  }

  const root = path.resolve(import.meta.dirname, "..");
  const configPath = path.join(root, "wrangler.jsonc");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const updated = configureKvNamespace(config, namespaceId);
  fs.writeFileSync(configPath, `${JSON.stringify(updated, null, 2)}\n`);
  console.log(`Configured HEALTH_MONITOR_STATE KV namespace id in ${path.relative(process.cwd(), configPath)}.`);
}
