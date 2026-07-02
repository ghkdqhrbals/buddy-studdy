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
  if (!/npm\s+run\s+smoke/.test(text)) {
    errors.push("Health monitor workflow must include a post-deploy smoke check.");
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
