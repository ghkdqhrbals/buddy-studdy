import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildWorkerStatusReport,
  parseDeploymentCount,
  parseSecretNames,
  requiredWorkerSecrets,
} from "../scripts/worker-status.js";

test("parseSecretNames reads wrangler secret list array output", () => {
  assert.deepEqual(
    parseSecretNames(JSON.stringify([{ name: "SLACK_WEBHOOK_URL" }, { name: "MANUAL_CHECK_TOKEN" }])),
    ["SLACK_WEBHOOK_URL", "MANUAL_CHECK_TOKEN"],
  );
});

test("parseSecretNames reads alternate secret output shapes defensively", () => {
  assert.deepEqual(
    parseSecretNames(JSON.stringify({ secrets: [{ key: "SLACK_WEBHOOK_URL" }, { binding: "MANUAL_CHECK_TOKEN" }] })),
    ["SLACK_WEBHOOK_URL", "MANUAL_CHECK_TOKEN"],
  );
});

test("parseSecretNames ignores invalid output", () => {
  assert.deepEqual(parseSecretNames("not-json"), []);
});

test("parseDeploymentCount reads wrangler deployment list shapes", () => {
  assert.equal(parseDeploymentCount(JSON.stringify([{ id: "a" }, { id: "b" }])), 2);
  assert.equal(parseDeploymentCount(JSON.stringify({ deployments: [{ id: "a" }] })), 1);
  assert.equal(parseDeploymentCount(JSON.stringify({ items: [{ id: "a" }, { id: "b" }, { id: "c" }] })), 3);
});

test("worker status reports ready when deployment and required secrets exist", () => {
  const report = buildWorkerStatusReport({
    deploymentsOk: true,
    deploymentCount: 1,
    secretNames: requiredWorkerSecrets,
  });

  assert.equal(report.ready, true);
  assert.equal(report.deployed, true);
  assert.deepEqual(report.blockers, []);
});

test("worker status blocks when Worker is not deployed", () => {
  const report = buildWorkerStatusReport({
    deploymentsOk: false,
    deploymentsError: "Worker does not exist",
  });

  assert.equal(report.ready, false);
  assert.equal(report.deployed, false);
  assert.match(report.blockers.join("\n"), /not deployed/);
  assert.match(report.nextActions.join("\n"), /CLOUDFLARE_API_TOKEN/);
  assert.doesNotMatch(report.blockers.join("\n"), /Worker secrets are missing/);
});

test("worker status blocks when Worker secrets are missing", () => {
  const report = buildWorkerStatusReport({
    deploymentsOk: true,
    deploymentCount: 1,
    secretNames: ["SLACK_WEBHOOK_URL"],
  });

  assert.equal(report.ready, false);
  assert.match(report.blockers.join("\n"), /MANUAL_CHECK_TOKEN/);
  assert.match(report.nextActions.join("\n"), /sync Worker secrets/);
});
