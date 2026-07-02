import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { validateNoActionsRuntimeHealthChecks, validateWorkflowText } from "../scripts/check-workflow.js";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

test("health monitor workflow is deploy-only and not a runtime health checker", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Configure KV namespace
        run: npm run configure:kv -- "$HEALTH_MONITOR_KV_NAMESPACE_ID"
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

test("health monitor workflow rejects direct curl health checks", () => {
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
      - name: Check backend health
        run: curl -fsS https://api.lowfidev.cloud/api/v1/health/readiness
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must not directly call backend health endpoints/);
});

test("all GitHub Actions workflows reject direct backend health checks", () => {
  const workflow = `
name: Backend Image
on:
  workflow_dispatch:
jobs:
  build:
    steps:
      - name: Health check
        run: wget -qO- https://api.lowfidev.cloud/health
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "backend-image.yml").join("\n"), /backend-image\.yml/);
  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "backend-image.yml").join("\n"), /must not directly call backend health endpoints/);
});

test("all GitHub Actions workflows reject health monitor smoke checks", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    env:
      HEALTH_MONITOR_URL: \${{ secrets.HEALTH_MONITOR_URL }}
    steps:
      - name: Smoke check
        run: npm run smoke
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-smoke.yml").join("\n");

  assert.match(errors, /health-smoke\.yml/);
  assert.match(errors, /must not run health monitor smoke checks/);
  assert.match(errors, /must not configure health monitor check URLs/);
});

test("all GitHub Actions workflows reject direct health monitor smoke script execution", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Smoke check
        run: node deploy/cloudflare-health-monitor/scripts/smoke-check.js https://worker.example token
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-smoke.yml").join("\n");

  assert.match(errors, /health-smoke\.yml/);
  assert.match(errors, /must not run health monitor smoke checks/);
});

test("all GitHub Actions workflows reject direct health monitor manual check calls", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Manual health monitor check
        run: curl -fsS -X POST https://buddystudy-health-monitor.example.workers.dev/check -H "Authorization: Bearer \${MANUAL_CHECK_TOKEN}"
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-smoke.yml").join("\n");

  assert.match(errors, /health-smoke\.yml/);
  assert.match(errors, /must not call health monitor manual check endpoints/);
});

test("deploy repo backend template does not run backend health probes in Actions", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.deepEqual(validateNoActionsRuntimeHealthChecks(template, "deploy-backend.yml"), []);
});

test("deploy repo backend template wires scheduler Slack webhook into backend env", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(template, /SLACK_WEBHOOK_URL:\s*\$\{\{\s*secrets\.SLACK_WEBHOOK_URL\s*\}\}/);
  assert.match(template, /SLACK_WEBHOOK_URL=\$\{SLACK_WEBHOOK_URL\}/);
});

test("deploy repo backend template fails fast when scheduler Slack webhook is missing", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(template, /Missing GitHub Actions secret: SLACK_WEBHOOK_URL/);
});

test("repository workflow files do not run backend health probes in Actions", () => {
  const workflowDir = path.join(repoRoot, ".github", "workflows");
  const deployTemplateDir = path.join(repoRoot, "docs", "deploy-repo-template");
  const files = [
    ...fs.readdirSync(workflowDir)
      .filter((entry) => entry.endsWith(".yml") || entry.endsWith(".yaml"))
      .map((entry) => path.join(workflowDir, entry)),
    ...fs.readdirSync(deployTemplateDir)
      .filter((entry) => entry.endsWith(".yml") || entry.endsWith(".yaml"))
      .map((entry) => path.join(deployTemplateDir, entry)),
  ];

  const errors = files.flatMap((file) => {
    const relativePath = path.relative(repoRoot, file);
    return validateNoActionsRuntimeHealthChecks(fs.readFileSync(file, "utf8"), relativePath);
  });

  assert.deepEqual(errors, []);
});

test("workflow scan rejects container health probes in Actions", () => {
  const workflow = `
name: Deploy Monitoring
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check Grafana
        run: docker exec rsc-grafana wget -qO- http://127.0.0.1:3000/api/health
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-monitoring.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects local runtime health probes even when they are not backend readiness urls", () => {
  const workflow = `
name: Deploy Monitoring
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check local service
        run: curl -fsS http://127.0.0.1:3000/api/health
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-monitoring.yml").join("\n"), /must not run runtime health probes/);
});

test("workflow scan rejects multiline curl backend health checks", () => {
  const workflow = `
name: Backend Deploy
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check backend readiness
        run: |
          curl -fsS \\
            "https://api.lowfidev.cloud/api/v1/health/readiness"
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n");

  assert.match(errors, /deploy-backend\.yml/);
  assert.match(errors, /must not directly call backend health endpoints/);
});

test("workflow scan rejects multiline direct health monitor manual checks", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Manual health monitor check
        run: |
          curl -fsS -X POST \\
            "https://buddystudy-health-monitor.example.workers.dev/check" \\
            -H "Authorization: Bearer \${MANUAL_CHECK_TOKEN}"
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-smoke.yml").join("\n");

  assert.match(errors, /health-smoke\.yml/);
  assert.match(errors, /must not call health monitor manual check endpoints/);
});

test("kubernetes backend probes use dependency readiness while external monitor uses scheduler readiness", () => {
  const backendManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/backend/backend.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const workerConfig = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/wrangler.jsonc"), "utf8");

  assert.match(backendManifest, /path:\s*\/api\/v1\/health\/dependencies/);
  assert.doesNotMatch(backendManifest, /path:\s*\/api\/v1\/health\/readiness/);
  assert.match(combinedManifest, /path:\s*\/api\/v1\/health\/dependencies/);
  assert.doesNotMatch(combinedManifest, /path:\s*\/api\/v1\/health\/readiness/);
  assert.match(workerConfig, /api\.ghkdqhrbals\.org\/api\/v1\/health\/readiness/);
});

test("kubernetes backend config monitors every managed scheduler job", () => {
  const backendConfig = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/config/backend-config.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const requiredJobs = [
    "question-schedule",
    "question-push-outbox-dispatch",
    "user-stats-refresh",
    "admin-analytics-recent",
    "admin-analytics-correction",
  ];

  for (const jobName of requiredJobs) {
    assert.match(backendConfig, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS:.*${jobName}`));
    assert.match(combinedManifest, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS:.*${jobName}`));
  }
});

test("kubernetes production apply path does not include placeholder backend secret", () => {
  const kustomization = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/kustomization.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const placeholderSecret = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/secrets/backend-secret.yaml"), "utf8");

  assert.match(placeholderSecret, /SLACK_WEBHOOK_URL:\s*""/);
  assert.doesNotMatch(kustomization, /secrets\/backend-secret\.yaml/);
  assert.doesNotMatch(combinedManifest, /kind:\s*Secret[\s\S]*name:\s*buddystudy-backend-secret/);
  assert.match(combinedManifest, /name:\s*buddystudy-backend-secret/);
});

test("health monitor workflow rejects deploying before syncing Worker secrets", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Deploy Worker
        run: npm run deploy
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must sync Worker secrets before deploying/);
});

test("health monitor workflow rejects validating bundle before configuring KV namespace", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Validate Worker bundle
        run: npm run check
      - name: Configure KV namespace
        run: npm run configure:kv -- "$HEALTH_MONITOR_KV_NAMESPACE_ID"
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must configure KV namespace before validating/);
});

test("health monitor workflow rejects deployment without KV namespace configuration", () => {
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
      - name: Deploy Worker
        run: npm run deploy
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must configure KV namespace/);
});

test("health monitor workflow rejects deployment without running tests", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Configure KV namespace
        run: npm run configure:kv -- "$HEALTH_MONITOR_KV_NAMESPACE_ID"
      - name: Validate Worker bundle
        run: npm run check
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
      - name: Deploy Worker
        run: npm run deploy
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must run npm test before deploying/);
});

test("health monitor workflow rejects deployment without policy and bundle validation", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Run tests
        run: npm test
      - name: Configure KV namespace
        run: npm run configure:kv -- "$HEALTH_MONITOR_KV_NAMESPACE_ID"
      - name: Sync Worker secrets
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
      - name: Deploy Worker
        run: npm run deploy
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must run npm run check before deploying/);
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

test("health monitor workflow summary documents manual status endpoints", () => {
  const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/health-monitor.yml"), "utf8");

  assert.match(workflow, /workers\.dev/);
  assert.match(workflow, /GET \\`\/\\`/);
  assert.match(workflow, /POST \\`\/check\\`/);
});
