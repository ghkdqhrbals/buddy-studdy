import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { buildDeploymentReadinessReport } from "./check-workflow.js";

const root = path.resolve(import.meta.dirname, "..", "..", "..");
const workflowPath = path.join(root, ".github", "workflows", "health-monitor.yml");
const repo = process.env.HEALTH_MONITOR_REPO || "ghkdqhrbals/study-mate";
const jsonOutput = process.argv.includes("--json");

function readRemoteWorkflowNames() {
  try {
    const output = execFileSync("gh", ["workflow", "list", "--repo", repo], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return output
      .split(/\r?\n/)
      .map((line) => line.trim().split(/\t+/)[0])
      .filter(Boolean);
  } catch (error) {
    return [];
  }
}

function hasGitHubSlackSecret() {
  try {
    const output = execFileSync("gh", ["secret", "list", "--repo", repo], {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return /^HEALTH_MONITOR_SLACK_WEBHOOK_URL\b/m.test(output);
  } catch (error) {
    return null;
  }
}

function printSection(title, items) {
  if (items.length === 0) {
    return;
  }
  console.log(title);
  for (const item of items) {
    console.log(`- ${item}`);
  }
}

const report = buildDeploymentReadinessReport({
  localWorkflowExists: fs.existsSync(workflowPath),
  remoteWorkflowNames: readRemoteWorkflowNames(),
  hasGitHubSlackSecret: hasGitHubSlackSecret(),
  hasCloudflareApiToken: Boolean(process.env.CLOUDFLARE_API_TOKEN),
});

if (jsonOutput) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log(report.ready ? "Health monitor deployment readiness: ready" : "Health monitor deployment readiness: blocked");
  printSection("Blockers:", report.blockers);
  printSection("Next actions:", report.nextActions);
}

process.exit(report.ready ? 0 : 1);
