import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { buildExecutionScript } from "../src/runner.mjs";
import { DEFAULT_SCRIPT } from "../src/store.mjs";

const runnerSource = await readFile(new URL("../src/runner.mjs", import.meta.url), "utf8");

test("default user script contains only API test logic", () => {
  assert.doesNotMatch(DEFAULT_SCRIPT, /TARGET_RPS|MAX_VUS|DURATION|export const options/);
  assert.match(DEFAULT_SCRIPT, /export default function/);
});

test("runtime wrapper owns Run Plan execution settings", () => {
  const wrapper = buildExecutionScript();

  assert.match(wrapper, /import userScenario from "\.\/script\.js"/);
  assert.match(wrapper, /__ENV\.TARGET_RPS/);
  assert.match(wrapper, /__ENV\.MAX_VUS/);
  assert.match(wrapper, /export const options/);
  assert.match(wrapper, /export default userScenario/);
});

test("runner uses the immutable run target instead of project configuration", () => {
  assert.match(runnerSource, /targetBaseUrl:\s*run\.targetUrl/);
  assert.match(runnerSource, /BASE_URL:\s*run\.targetUrl/);
  assert.doesNotMatch(runnerSource, /BASE_URL:\s*project\.baseUrl/);
});
