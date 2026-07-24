import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { appendUniqueLiveWarning, projectForRun } from "../src/runner.mjs";
import { DEFAULT_SCRIPT } from "../src/store.mjs";

const runnerSource = await readFile(new URL("../src/runner.mjs", import.meta.url), "utf8");

test("default user script owns its k6 execution options", () => {
  assert.match(DEFAULT_SCRIPT, /export const testConfig/);
  assert.match(DEFAULT_SCRIPT, /targetUrl: "https:\/\/api\.ghkdqhrbals\.org"/);
  assert.match(DEFAULT_SCRIPT, /export const options/);
  assert.match(DEFAULT_SCRIPT, /executor: "constant-arrival-rate"/);
  assert.match(DEFAULT_SCRIPT, /rate: 300/);
  assert.match(DEFAULT_SCRIPT, /maxVUs: 500/);
  assert.match(DEFAULT_SCRIPT, /export default function/);
});

test("runner executes the saved script without injecting load options", () => {
  assert.doesNotMatch(runnerSource, /buildExecutionScript|TARGET_RPS|MAX_VUS|DURATION/);
  assert.match(runnerSource, /`json=\$\{metricsPath\}`,\s*scriptPath/);
});

test("runner leaves target URL and headers entirely to the saved script", () => {
  assert.doesNotMatch(runnerSource, /allowedTargetHosts|TESTZONE_ALLOWED_TARGET_HOSTS/);
  assert.doesNotMatch(runnerSource, /BASE_URL|HEADERS_JSON|environment\.json/);
});

test("runner resolves the run project before recording live and summary metrics", () => {
  const store = {
    state: {
      projects: [{ id: "project-1", name: "API tests" }],
    },
  };
  const run = { id: "run-1", projectId: "project-1" };

  assert.deepEqual(projectForRun(store, run), store.state.projects[0]);
  assert.throws(
    () => projectForRun(store, { id: "run-2", projectId: "missing" }),
    /Project missing was not found for run run-2/,
  );
  assert.match(runnerSource, /writeLiveSnapshot\(run,\s*project,\s*script,\s*snapshot\)/);
  assert.match(runnerSource, /writeRunSummary\(updated,\s*project,\s*script,\s*summary\)/);
});

test("runner records a repeated live metric failure only once until recovery", () => {
  const logs = [];
  let previous = null;

  previous = appendUniqueLiveWarning(logs, new Error("InfluxDB unavailable"), previous);
  previous = appendUniqueLiveWarning(logs, new Error("InfluxDB unavailable"), previous);

  assert.equal(previous, "InfluxDB unavailable");
  assert.deepEqual(logs, ["Live metrics warning: InfluxDB unavailable"]);
});
