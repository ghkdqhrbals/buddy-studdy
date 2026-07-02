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
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
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

test("health monitor workflow rejects GitHub Actions smoke health checks", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
      - name: Smoke check deployed Worker
        run: npm run smoke
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must not run smoke health checks/);
});

test("health monitor workflow rejects deployed health check urls", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    env:
      HEALTH_MONITOR_URL: \${{ secrets.HEALTH_MONITOR_URL }}
    steps:
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must not depend on HEALTH_MONITOR_URL/);
});

test("health monitor workflow rejects deployments without Worker Slack secret sync", () => {
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

  assert.match(validateWorkflowText(workflow).join("\n"), /Worker Slack secret sync/);
});

test("health monitor workflow rejects optional Slack alert deployment", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Sync Worker secrets
        if: env.HEALTH_MONITOR_SLACK_WEBHOOK_URL != '' && env.MANUAL_CHECK_TOKEN != ''
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /Slack alert secrets must be required/);
});
