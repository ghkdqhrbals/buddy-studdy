import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const appSource = fs.readFileSync(path.join(root, "src", "App.tsx"), "utf8");
const apiSource = fs.readFileSync(path.join(root, "src", "api.ts"), "utf8");
const configSource = fs.readFileSync(path.join(root, "src", "adminConfig.ts"), "utf8");
const operationsSource = fs.readFileSync(path.join(root, "src", "OperationsPanel.tsx"), "utf8");

const checks = [
  {
    ok: /jobName:\s*section === "operations" \? params\.get\("jobName"\)/.test(appSource),
    message: "App route state must preserve the scheduler jobName query parameter.",
  },
  {
    ok: /runId:\s*section === "operations" \? parseRunId\(params\.get\("runId"\)\)/.test(appSource),
    message: "App route state must preserve the scheduler runId query parameter.",
  },
  {
    ok: /fetchJobRuns\([^)]*jobName/.test(appSource),
    message: "App must pass the scheduler jobName filter into fetchJobRuns.",
  },
  {
    ok: /fetchJobRuns\([^)]*jobNameFilter,\s*highlightRunId/.test(appSource),
    message: "App must pass the scheduler runId filter into fetchJobRuns.",
  },
  {
    ok: /function fetchJobRuns\([^)]*jobName/.test(apiSource) || /async function fetchJobRuns\([^)]*jobName/.test(apiSource),
    message: "fetchJobRuns must accept a scheduler jobName filter.",
  },
  {
    ok: /params\.set\("jobName",\s*jobName\.trim\(\)\)/.test(apiSource),
    message: "fetchJobRuns must send the scheduler jobName filter to the backend API.",
  },
  {
    ok: /params\.set\("runId",\s*String\(runId\)\)/.test(apiSource),
    message: "fetchJobRuns must send the scheduler runId filter to the backend API.",
  },
  {
    ok: /sectionHref\("operations",\s*\(nextPage - 1\) \* jobPage\.limit,\s*undefined,\s*jobNameFilter,\s*highlightRunId\)/.test(appSource),
    message: "Scheduler runs page links must preserve jobName across pagination.",
  },
  {
    ok: /highlightRunId=\{highlightRunId\}/.test(appSource) && /highlighted-run/.test(fs.readFileSync(path.join(root, "src", "OperationsPanel.tsx"), "utf8")),
    message: "Scheduler runs page must highlight the runId opened from Slack.",
  },
  {
    ok: /returnTo=/.test(appSource) && /safeReturnPath/.test(appSource),
    message: "Login redirects must preserve a safe return path for scheduler alert links.",
  },
  {
    ok: /BATCH_JOBS_MONITOR_URL\s*=\s*"https:\/\/monitoring\.lowfidev\.cloud\/jobs\.html"/.test(configSource),
    message: "Operations must use the production Batch Jobs monitor URL.",
  },
  {
    ok: /\{\s*key:\s*"operations",\s*label:\s*"Batch Jobs"/.test(configSource),
    message: "The admin primary navigation must expose Batch Jobs by name.",
  },
  {
    ok: /href=\{BATCH_JOBS_MONITOR_URL\}/.test(operationsSource)
      && /target="_blank"/.test(operationsSource)
      && /rel="noopener noreferrer"/.test(operationsSource)
      && /in a new tab/.test(operationsSource),
    message: "Operations must expose an accessible, safe external Batch Jobs link.",
  },
  {
    ok: /jobs\/statuses\?\$\{params\}/.test(apiSource)
      && /limit:\s*String\(limit\),\s*offset:\s*String\(offset\)/.test(apiSource),
    message: "Admin scheduler statuses must use bounded server pagination.",
  },
];

const failures = checks.filter((check) => !check.ok).map((check) => check.message);

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log("Scheduler alert links preserve job filters.");
