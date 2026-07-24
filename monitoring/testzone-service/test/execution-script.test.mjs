import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { DEFAULT_SCRIPT } from "../src/store.mjs";

const runnerSource = await readFile(new URL("../src/runner.mjs", import.meta.url), "utf8");

test("default user script owns its k6 execution options", () => {
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

test("runner uses the immutable run target instead of project configuration", () => {
  assert.match(runnerSource, /targetBaseUrl:\s*run\.targetUrl/);
  assert.match(runnerSource, /BASE_URL:\s*run\.targetUrl/);
  assert.doesNotMatch(runnerSource, /BASE_URL:\s*project\.baseUrl/);
});
