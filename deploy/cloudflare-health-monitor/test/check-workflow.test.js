import assert from "node:assert/strict";
import { test } from "node:test";
import { validateWorkflowText } from "../scripts/check-workflow.js";

test("health monitor workflow is deploy-only and not a runtime health checker", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Smoke check deployed Worker
        run: npm run smoke
`;

  assert.deepEqual(validateWorkflowText(workflow), []);
});

test("health monitor workflow rejects scheduled GitHub Actions checks", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
  schedule:
    - cron: "* * * * *"
jobs: {}
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must not use GitHub Actions schedule/);
});

test("health monitor workflow rejects deployments without a post-deploy smoke check", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Deploy Worker
        run: npm run deploy
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /post-deploy smoke check/);
});
