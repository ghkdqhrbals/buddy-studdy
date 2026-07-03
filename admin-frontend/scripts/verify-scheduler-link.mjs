import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const appSource = fs.readFileSync(path.join(root, "src", "App.tsx"), "utf8");
const apiSource = fs.readFileSync(path.join(root, "src", "api.ts"), "utf8");

const checks = [
  {
    ok: /jobName:\s*section === "operations" \? params\.get\("jobName"\)/.test(appSource),
    message: "App route state must preserve the scheduler jobName query parameter.",
  },
  {
    ok: /fetchJobRuns\([^)]*jobName/.test(appSource),
    message: "App must pass the scheduler jobName filter into fetchJobRuns.",
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
    ok: /sectionHref\("operations",\s*\(nextPage - 1\) \* jobPage\.limit,\s*undefined,\s*jobNameFilter\)/.test(appSource),
    message: "Scheduler runs page links must preserve jobName across pagination.",
  },
  {
    ok: /returnTo=/.test(appSource) && /safeReturnPath/.test(appSource),
    message: "Login redirects must preserve a safe return path for scheduler alert links.",
  },
];

const failures = checks.filter((check) => !check.ok).map((check) => check.message);

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log("Scheduler alert links preserve job filters.");
