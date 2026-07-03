import assert from "node:assert/strict";
import os from "node:os";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  buildDeploymentReadinessReport,
  requiredHealthMonitorGitHubSecrets,
  validateNoActionsRuntimeHealthChecks,
  validateWorkflowText,
} from "../scripts/check-workflow.js";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const kotlinSourceRoot = path.join(repoRoot, "backend", "infra", "src", "main", "kotlin", "com", "buddystudy", "backend");

function kotlinFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return kotlinFiles(entryPath);
    }
    return entry.isFile() && entry.name.endsWith(".kt") ? [entryPath] : [];
  });
}

function managedJobNames() {
  return kotlinFiles(kotlinSourceRoot)
    .flatMap((file) => {
      const source = fs.readFileSync(file, "utf8");
      if (!/:\s*ManagedJob\b/.test(source)) {
        return [];
      }
      return [...source.matchAll(/override\s+val\s+name\s*:\s*String\s*(?:=\s*|get\(\)\s*=\s*)"([^"]+)"/g)].map((match) => match[1]);
    })
    .sort();
}

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

test("health monitor workflow rejects optional manual check token secret sync", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    env:
      HEALTH_MONITOR_SLACK_WEBHOOK_URL: \${{ secrets.HEALTH_MONITOR_SLACK_WEBHOOK_URL }}
      HEALTH_MONITOR_MANUAL_CHECK_TOKEN: \${{ secrets.HEALTH_MONITOR_MANUAL_CHECK_TOKEN }}
    steps:
      - name: Sync Worker secrets
        if: env.HEALTH_MONITOR_MANUAL_CHECK_TOKEN != ''
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$HEALTH_MONITOR_MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must be required, not optional/);
});

test("health monitor workflow rejects GitHub expression optional secret sync", () => {
  const workflow = `
name: Deploy Health Monitor Worker
on:
  workflow_dispatch:
jobs:
  deploy:
    env:
      HEALTH_MONITOR_SLACK_WEBHOOK_URL: \${{ secrets.HEALTH_MONITOR_SLACK_WEBHOOK_URL }}
      HEALTH_MONITOR_MANUAL_CHECK_TOKEN: \${{ secrets.HEALTH_MONITOR_MANUAL_CHECK_TOKEN }}
    steps:
      - name: Sync Worker secrets
        if: \${{ env.HEALTH_MONITOR_SLACK_WEBHOOK_URL != '' && env.HEALTH_MONITOR_MANUAL_CHECK_TOKEN != '' }}
        run: |
          printf '%s' "$HEALTH_MONITOR_SLACK_WEBHOOK_URL" | npx wrangler secret put SLACK_WEBHOOK_URL
          printf '%s' "$HEALTH_MONITOR_MANUAL_CHECK_TOKEN" | npx wrangler secret put MANUAL_CHECK_TOKEN
`;

  assert.match(validateWorkflowText(workflow).join("\n"), /must be required, not optional/);
});

test("all GitHub Actions workflows reject health monitor manual check scripts", () => {
  const workflow = `
name: Health Manual Check
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Manual check
        run: npm run manual:check -- https://worker.example token
      - name: Direct manual check script
        run: node deploy/cloudflare-health-monitor/scripts/manual-check.js https://worker.example token
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-manual-check.yml").join("\n");

  assert.match(errors, /health-manual-check\.yml/);
  assert.match(errors, /must not run health monitor manual checks/);
});

test("deploy repo backend template does not run backend health probes in Actions", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.deepEqual(validateNoActionsRuntimeHealthChecks(template, "deploy-backend.yml"), []);
});

test("deploy repo docs prohibit Actions runtime and container health checks", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/README.md"), "utf8");

  assert.match(readme, /must not call backend `\/health` or readiness endpoints/i);
  assert.match(readme, /must not inspect Docker `Health\.Status`/i);
  assert.match(readme, /must not use indirect container health gates/i);
  assert.match(readme, /`docker compose up --wait`/i);
  assert.match(readme, /`docker compose wait`/i);
  assert.match(readme, /must not call the Health Monitor Worker `\/check` endpoint/i);
});

test("deploy repo docs explain coordinator readiness wiring", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/README.md"), "utf8");

  assert.match(readme, /internal Redis Stream Coordinator/i);
  assert.match(readme, /`REACTION_STREAM_COORDINATOR_BASE_URL`/);
  assert.match(readme, /`MONITORING_COORDINATOR_READINESS_ENABLED=true`/);
  assert.match(readme, /`http:\/\/rsc-coordinator:8080`/);
});

test("deploy repo monitoring template remains PLG only", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-monitoring.yml"), "utf8");

  assert.match(template, /docker pull grafana\/loki:/);
  assert.match(template, /docker pull grafana\/promtail:/);
  assert.match(template, /docker pull grafana\/grafana:/);
  assert.match(template, /docker rm -f[\s\S]*rsc-prometheus[\s\S]*redis-exporter-6379[\s\S]*redis-exporter-6381/);
  assert.doesNotMatch(template, /docker run[\s\S]*prom\/prometheus/);
  assert.doesNotMatch(template, /docker run[\s\S]*redis_exporter/);
  assert.doesNotMatch(template, /prometheus\.yml/);
});

test("deploy repo monitoring template persists Loki and Grafana state", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-monitoring.yml"), "utf8");

  assert.match(template, /docker volume create rsc-loki-data/);
  assert.match(template, /docker volume create rsc-grafana-data/);
  assert.match(template, /-v rsc-loki-data:\/loki/);
  assert.match(template, /-v rsc-grafana-data:\/var\/lib\/grafana/);
  assert.match(template, /retention_period:\s*168h/);
  assert.match(template, /retention_enabled:\s*true/);
});

test("k3s operations docs describe the actual exposed node ports", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/k3s/README.md"), "utf8");
  const postgresManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/postgres/postgres.yaml"), "utf8");
  const redisManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/redis/redis.yaml"), "utf8");

  const postgresNodePort = postgresManifest.match(/nodePort:\s*(\d+)/)?.[1];
  const redisNodePort = redisManifest.match(/name:\s*buddystudy-redis-external[\s\S]*?nodePort:\s*(\d+)/)?.[1];

  assert.equal(postgresNodePort, "30432");
  assert.equal(redisNodePort, "30379");
  assert.match(readme, new RegExp(`^.*${postgresNodePort}.*PostgreSQL.*$`, "m"));
  assert.match(readme, new RegExp(`^.*${redisNodePort}.*Redis.*$`, "m"));
  assert.doesNotMatch(readme, /PostgreSQL:[^\n]*<host-ip>:5432/);
  assert.doesNotMatch(readme, /Redis[^:\n]*:[^\n]*<host-ip>:6379/);
});

test("deploy repo backend template wires scheduler Slack webhook into backend env", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(template, /SLACK_WEBHOOK_URL:\s*\$\{\{\s*secrets\.SLACK_WEBHOOK_URL\s*\}\}/);
  assert.match(template, /SLACK_WEBHOOK_URL=\$\{SLACK_WEBHOOK_URL\}/);
});

test("kubernetes backend config throttles scheduler failure Slack alerts", () => {
  const applicationConfig = fs.readFileSync(path.join(repoRoot, "backend/tutor/src/main/resources/application.yml"), "utf8");
  const backendConfig = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/config/backend-config.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const deployTemplate = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(applicationConfig, /scheduler-failure-alert-repeat-seconds:\s*\$\{MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS:300\}/);
  for (const text of [backendConfig, combinedManifest]) {
    assert.match(text, /MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS:\s*"300"/);
  }
  assert.match(deployTemplate, /MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS:\s*\$\{\{\s*vars\.MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS\s*\|\|\s*'300'\s*\}\}/);
  assert.match(deployTemplate, /MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS=\$\{MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS\}/);
});

test("deploy repo backend template wires scheduler admin alert links into backend env", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(template, /MONITORING_ADMIN_BASE_URL:\s*\$\{\{\s*vars\.MONITORING_ADMIN_BASE_URL\s*\|\|\s*'https:\/\/api\.ghkdqhrbals\.org\/admin'\s*\}\}/);
  assert.match(template, /MONITORING_ADMIN_BASE_URL=\$\{MONITORING_ADMIN_BASE_URL\}/);
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

test("image build workflows do not run health-check scanners or probes", () => {
  const workflowDir = path.join(repoRoot, ".github", "workflows");
  for (const workflowName of ["backend-image.yml", "admin-frontend-image.yml"]) {
    const workflow = fs.readFileSync(path.join(workflowDir, workflowName), "utf8");
    assert.doesNotMatch(workflow, /check-workflow\.js/, `${workflowName} must not run health-check policy scanners`);
    assert.deepEqual(validateNoActionsRuntimeHealthChecks(workflow, workflowName), []);
  }
});

test("backend image does not define Docker health metadata", () => {
  const dockerfile = fs.readFileSync(path.join(repoRoot, "backend/Dockerfile"), "utf8");

  assert.doesNotMatch(dockerfile, /^\s*HEALTHCHECK\b/im);
  assert.doesNotMatch(dockerfile, /curl\s+-fsS\s+http:\/\/127\.0\.0\.1:8080\/health/);
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

test("workflow scan rejects docker health status inspection in Actions", () => {
  const workflow = `
name: Deploy Backend
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check container health
        run: docker inspect -f '{{.State.Health.Status}}' buddystudy-backend
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects docker health metadata in Actions", () => {
  const workflow = `
name: Deploy Backend
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Start database
        run: docker run --health-cmd "pg_isready" postgres:16-alpine
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects docker ps health filters in Actions", () => {
  const workflow = `
name: Deploy Backend
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check unhealthy containers
        run: docker ps --filter health=unhealthy --format '{{.Names}}'
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects docker ps status health checks in Actions", () => {
  const workflow = `
name: Deploy Backend
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check unhealthy status
        run: docker ps --format '{{.Names}} {{.Status}}' | grep unhealthy
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects docker compose wait health checks in Actions", () => {
  const workflow = `
name: Deploy Backend
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Wait for backend health
        run: docker compose up -d --wait backend
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n"), /must not run container health probes/);
});

test("workflow scan rejects coordinator runtime health probes in Actions", () => {
  const workflow = `
name: Deploy Coordinator
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check coordinator
        run: curl -fsS http://coordinator.ghkdqhrbals.org/coord/v1/monitoring/health
`;

  assert.match(validateNoActionsRuntimeHealthChecks(workflow, "deploy-coordinator.yml").join("\n"), /must not run runtime health probes/);
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

test("workflow scan rejects node fetch backend health checks", () => {
  const workflow = `
name: Backend Deploy
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Check backend readiness
        run: node -e "fetch('https://api.lowfidev.cloud/api/v1/health/readiness')"
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "deploy-backend.yml").join("\n");

  assert.match(errors, /deploy-backend\.yml/);
  assert.match(errors, /must not directly call backend health endpoints/);
});

test("workflow scan rejects bare backend health urls in wait commands", () => {
  const workflow = `
name: Backend Deploy
on:
  workflow_dispatch:
jobs:
  deploy:
    steps:
      - name: Wait for backend readiness
        run: npx wait-on https://api.lowfidev.cloud/api/v1/health/readiness
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

test("workflow scan rejects node fetch health monitor manual checks", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Manual health monitor check
        run: node -e "fetch('https://buddystudy-health-monitor.example.workers.dev/check', { method: 'POST' })"
`;

  const errors = validateNoActionsRuntimeHealthChecks(workflow, "health-smoke.yml").join("\n");

  assert.match(errors, /health-smoke\.yml/);
  assert.match(errors, /must not call health monitor manual check endpoints/);
});

test("workflow scan rejects bare health monitor manual check urls in wait commands", () => {
  const workflow = `
name: Health Smoke
on:
  workflow_dispatch:
jobs:
  check:
    steps:
      - name: Wait for manual check
        run: npx wait-on https://buddystudy-health-monitor.example.workers.dev/check
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
  const applicationConfig = fs.readFileSync(path.join(repoRoot, "backend/tutor/src/main/resources/application.yml"), "utf8");
  const backendConfig = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/config/backend-config.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const deployTemplate = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");
  const requiredJobs = managedJobNames();

  assert.ok(requiredJobs.length > 0, "expected at least one ManagedJob implementation");
  for (const jobName of requiredJobs) {
    assert.match(applicationConfig, new RegExp(`scheduler-monitored-jobs:.*${jobName}`));
    assert.match(backendConfig, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS:.*${jobName}`));
    assert.match(combinedManifest, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS:.*${jobName}`));
    assert.match(deployTemplate, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS:.*${jobName}`));
    assert.match(deployTemplate, new RegExp(`MONITORING_SCHEDULER_MONITORED_JOBS=\\$\\{MONITORING_SCHEDULER_MONITORED_JOBS\\}`));
  }
});

test("deploy repo backend template wires scheduler readiness policy into backend env", () => {
  const template = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(template, /MONITORING_ENVIRONMENT_NAME:\s*\$\{\{\s*vars\.MONITORING_ENVIRONMENT_NAME\s*\|\|\s*'production'\s*\}\}/);
  assert.match(template, /MONITORING_SERVICE_NAME:\s*\$\{\{\s*vars\.MONITORING_SERVICE_NAME\s*\|\|\s*'BuddyStudy backend'\s*\}\}/);
  assert.match(template, /MONITORING_SLACK_TIMEOUT_MS:\s*\$\{\{\s*vars\.MONITORING_SLACK_TIMEOUT_MS\s*\|\|\s*'5000'\s*\}\}/);
  assert.match(template, /MONITORING_SCHEDULER_READINESS_ENABLED:\s*\$\{\{\s*vars\.MONITORING_SCHEDULER_READINESS_ENABLED\s*\|\|\s*'true'\s*\}\}/);
  assert.match(template, /MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES:\s*\$\{\{\s*vars\.MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES\s*\|\|\s*'15'\s*\}\}/);
  assert.match(template, /MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES:\s*\$\{\{\s*vars\.MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES\s*\|\|\s*'15'\s*\}\}/);
  assert.match(template, /MONITORING_ENVIRONMENT_NAME=\$\{MONITORING_ENVIRONMENT_NAME\}/);
  assert.match(template, /MONITORING_SERVICE_NAME=\$\{MONITORING_SERVICE_NAME\}/);
  assert.match(template, /MONITORING_SLACK_TIMEOUT_MS=\$\{MONITORING_SLACK_TIMEOUT_MS\}/);
  assert.match(template, /MONITORING_SCHEDULER_READINESS_ENABLED=\$\{MONITORING_SCHEDULER_READINESS_ENABLED\}/);
  assert.match(template, /MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES=\$\{MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES\}/);
  assert.match(template, /MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES=\$\{MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES\}/);
});

test("kubernetes backend config enables coordinator readiness for external monitor", () => {
  const applicationConfig = fs.readFileSync(path.join(repoRoot, "backend/tutor/src/main/resources/application.yml"), "utf8");
  const backendConfig = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/config/backend-config.yaml"), "utf8");
  const combinedManifest = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/deploy.yaml"), "utf8");
  const deployTemplate = fs.readFileSync(path.join(repoRoot, "docs/deploy-repo-template/deploy-backend.yml"), "utf8");

  assert.match(applicationConfig, /coordinator-readiness-enabled:\s*\$\{MONITORING_COORDINATOR_READINESS_ENABLED:false\}/);
  for (const text of [backendConfig, combinedManifest]) {
    assert.match(text, /MONITORING_COORDINATOR_READINESS_ENABLED:\s*"true"/);
    assert.match(text, /MONITORING_COORDINATOR_BASE_URL:\s*"http:\/\/buddystudy-redis-stream-coordinator:8080"/);
    assert.match(text, /MONITORING_COORDINATOR_TIMEOUT_MS:\s*"3000"/);
  }
  assert.match(deployTemplate, /REACTION_STREAM_COORDINATOR_BASE_URL:\s*\$\{\{\s*vars\.REACTION_STREAM_COORDINATOR_BASE_URL\s*\|\|\s*'http:\/\/rsc-coordinator:8080'\s*\}\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_READINESS_ENABLED:\s*\$\{\{\s*vars\.MONITORING_COORDINATOR_READINESS_ENABLED\s*\|\|\s*'true'\s*\}\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_BASE_URL:\s*\$\{\{\s*vars\.MONITORING_COORDINATOR_BASE_URL\s*\|\|\s*'http:\/\/rsc-coordinator:8080'\s*\}\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_TIMEOUT_MS:\s*\$\{\{\s*vars\.MONITORING_COORDINATOR_TIMEOUT_MS\s*\|\|\s*'3000'\s*\}\}/);
  assert.match(deployTemplate, /REACTION_STREAM_COORDINATOR_BASE_URL=\$\{REACTION_STREAM_COORDINATOR_BASE_URL\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_READINESS_ENABLED=\$\{MONITORING_COORDINATOR_READINESS_ENABLED\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_BASE_URL=\$\{MONITORING_COORDINATOR_BASE_URL\}/);
  assert.match(deployTemplate, /MONITORING_COORDINATOR_TIMEOUT_MS=\$\{MONITORING_COORDINATOR_TIMEOUT_MS\}/);
});

test("backend scheduler readiness defaults and seed migrations cover every managed scheduler job", () => {
  const appProperties = fs.readFileSync(
    path.join(repoRoot, "backend/application/src/main/kotlin/com/buddystudy/backend/config/AppProperties.kt"),
    "utf8",
  );
  const applicationConfig = fs.readFileSync(path.join(repoRoot, "backend/tutor/src/main/resources/application.yml"), "utf8");
  const migrations = fs
    .readdirSync(path.join(repoRoot, "backend/tutor/src/main/resources/db/migration"))
    .filter((entry) => entry.endsWith(".sql"))
    .map((entry) => fs.readFileSync(path.join(repoRoot, "backend/tutor/src/main/resources/db/migration", entry), "utf8"))
    .join("\n");

  for (const jobName of managedJobNames()) {
    assert.match(appProperties, new RegExp(`"${jobName}"`), `AppProperties default must monitor ${jobName}`);
    assert.match(applicationConfig, new RegExp(`scheduler-monitored-jobs:.*${jobName}`), `application.yml default must monitor ${jobName}`);
    assert.match(migrations, new RegExp(`'${jobName}'`), `Flyway scheduler seed must include ${jobName}`);
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

test("kubernetes docs separate scheduler Slack secrets from admin alert config", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/kubernetes/README.md"), "utf8");
  const secretSection = readme.slice(readme.indexOf("Required keys include:"), readme.indexOf("`config/backend-config.yaml`"));

  assert.match(secretSection, /`SLACK_WEBHOOK_URL` for production scheduler failure alerts/);
  assert.doesNotMatch(secretSection, /MONITORING_ADMIN_BASE_URL/);
  assert.match(readme, /`config\/backend-config\.yaml` contains non-secret monitoring values/);
  assert.match(readme, /`MONITORING_ADMIN_BASE_URL`, which must be an HTTPS admin UI URL/);
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

test("health monitor workflow validates every required GitHub secret by repository secret name", () => {
  const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/health-monitor.yml"), "utf8");

  for (const name of requiredHealthMonitorGitHubSecrets) {
    assert.match(workflow, new RegExp(`${name}:\\s*\\$\\{\\{\\s*secrets\\.${name}\\s*\\}\\}`));
    assert.match(workflow, new RegExp(`for name in[\\s\\S]*\\b${name}\\b`));
  }
  assert.match(workflow, /wrangler\s+secret\s+put\s+MANUAL_CHECK_TOKEN/);
});

test("health monitor workflow summary documents status without manual health checks", () => {
  const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/health-monitor.yml"), "utf8");

  assert.match(workflow, /workers\.dev/);
  assert.doesNotMatch(workflow, /GET \\`\/\\`/);
  assert.doesNotMatch(workflow, /POST \\`\/check\\`/);
});

test("health monitor workflow npm ci has a committed lockfile", () => {
  const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/health-monitor.yml"), "utf8");
  const packageJson = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/package.json"), "utf8");
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");
  const packageLockPath = path.join(repoRoot, "deploy/cloudflare-health-monitor/package-lock.json");

  assert.match(workflow, /npm ci/);
  assert.match(workflow, /cache-dependency-path:\s*deploy\/cloudflare-health-monitor\/package-lock\.json/);
  assert.match(readme, /npm ci/);
  assert.ok(fs.existsSync(packageLockPath), "package-lock.json must be committed because the workflow uses npm ci");
  assert.equal(JSON.parse(fs.readFileSync(packageLockPath, "utf8")).name, JSON.parse(packageJson).name);
});

test("health monitor docs keep manual checks out of GitHub Actions", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /Do not run this from GitHub Actions/);
  assert.match(readme, /runtime health checks and Slack alerts are owned by Cloudflare Cron/);
});

test("health monitor docs distinguish GitHub secret storage from Worker secret sync", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /Slack setup is complete only after the Worker secret sync has run/);
  assert.match(readme, /Adding\s+`HEALTH_MONITOR_SLACK_WEBHOOK_URL`\s+to GitHub Actions secrets stores the webhook\s+for deployment/);
  assert.match(readme, /the running Worker will not send Slack alerts until\s+`SLACK_WEBHOOK_URL`\s+exists in Cloudflare Worker secrets/);
  assert.match(readme, /workflow not found/);
  assert.match(readme, /merge\/push the workflow first/);
});

test("health monitor deployment readiness reports remote workflow absence as Slack sync blocker", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: ["Build Backend Image", "Release iOS App"],
    hasGitHubSlackSecret: true,
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "Deploy Health Monitor Worker is not present on the remote default branch, so Worker secrets cannot be synced from GitHub Actions.",
  ]);
  assert.match(report.nextActions.join("\n"), /merge or push `.github\/workflows\/health-monitor\.yml`/);
  assert.match(report.nextActions.join("\n"), /dispatch Deploy Health Monitor Worker/);
});

test("health monitor deployment readiness blocks invalid local workflow", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    localWorkflowErrors: ["Health monitor workflow must run npm test before deploying."],
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    requiredGitHubSecrets: Object.fromEntries(requiredHealthMonitorGitHubSecrets.map((name) => [name, true])),
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "Local .github/workflows/health-monitor.yml is invalid: Health monitor workflow must run npm test before deploying.",
  ]);
  assert.match(report.nextActions.join("\n"), /fix local `.github\/workflows\/health-monitor\.yml` validation errors/);
  assert.doesNotMatch(report.nextActions.join("\n"), /dispatch Deploy Health Monitor Worker/);
});

test("health monitor deployment readiness blocks invalid worker config", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    localWorkerConfigErrors: ["HEALTHCHECK_URL must point to the backend readiness endpoint `/api/v1/health/readiness`."],
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    requiredGitHubSecrets: Object.fromEntries(requiredHealthMonitorGitHubSecrets.map((name) => [name, true])),
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "Local deploy/cloudflare-health-monitor/wrangler.jsonc is invalid: HEALTHCHECK_URL must point to the backend readiness endpoint `/api/v1/health/readiness`.",
  ]);
  assert.match(report.nextActions.join("\n"), /fix local `deploy\/cloudflare-health-monitor\/wrangler\.jsonc` validation errors/);
  assert.doesNotMatch(report.nextActions.join("\n"), /dispatch Deploy Health Monitor Worker/);
});

test("health monitor deployment readiness reports unknown remote workflow state separately", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: null,
    remoteWorkflowError: "token expired",
    requiredGitHubSecrets: Object.fromEntries(requiredHealthMonitorGitHubSecrets.map((name) => [name, true])),
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "Could not verify Deploy Health Monitor Worker on the remote default branch: token expired.",
  ]);
  assert.match(report.nextActions.join("\n"), /then rerun readiness/);
  assert.match(report.nextActions.join("\n"), /gh auth status/);
  assert.match(report.nextActions.join("\n"), /gh auth login -h github\.com/);
  assert.doesNotMatch(report.blockers.join("\n"), /not present on the remote default branch/);
});

test("health monitor deployment readiness distinguishes unknown GitHub secret state from missing secret", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    hasGitHubSlackSecret: null,
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, ["Could not verify HEALTH_MONITOR_SLACK_WEBHOOK_URL in GitHub Actions secrets."]);
  assert.match(report.nextActions.join("\n"), /then rerun readiness/);
  assert.match(report.nextActions.join("\n"), /gh auth status/);
  assert.match(report.nextActions.join("\n"), /gh auth login -h github\.com/);
  assert.doesNotMatch(report.blockers.join("\n"), /is missing from GitHub Actions secrets/);
});

test("health monitor deployment readiness checks every workflow secret", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    requiredGitHubSecrets: {
      CLOUDFLARE_API_TOKEN: true,
      CLOUDFLARE_ACCOUNT_ID: false,
      HEALTH_MONITOR_KV_NAMESPACE_ID: true,
      HEALTH_MONITOR_SLACK_WEBHOOK_URL: true,
      HEALTH_MONITOR_MANUAL_CHECK_TOKEN: false,
    },
    hasCloudflareApiToken: false,
  });

  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "CLOUDFLARE_ACCOUNT_ID is missing from GitHub Actions secrets.",
    "HEALTH_MONITOR_MANUAL_CHECK_TOKEN is missing from GitHub Actions secrets.",
  ]);
  assert.match(report.nextActions.join("\n"), /set CLOUDFLARE_ACCOUNT_ID in the buddy-studdy repository secrets/);
  assert.match(report.nextActions.join("\n"), /set HEALTH_MONITOR_MANUAL_CHECK_TOKEN in the buddy-studdy repository secrets/);
  assert.match(report.nextActions.join("\n"), /run `npm run bootstrap:cloudflare` after Wrangler login/);
  assert.doesNotMatch(report.nextActions.join("\n"), /dispatch Deploy Health Monitor Worker/);
});

test("health monitor deployment readiness points to wrangler login when Cloudflare setup is missing and unauthenticated", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    requiredGitHubSecrets: {
      CLOUDFLARE_API_TOKEN: false,
      CLOUDFLARE_ACCOUNT_ID: false,
      HEALTH_MONITOR_KV_NAMESPACE_ID: false,
      HEALTH_MONITOR_SLACK_WEBHOOK_URL: true,
      HEALTH_MONITOR_MANUAL_CHECK_TOKEN: true,
    },
    hasCloudflareApiToken: false,
    hasWranglerAuth: false,
  });

  assert.equal(report.ready, false);
  assert.match(report.nextActions.join("\n"), /run `npx wrangler login`/);
  assert.match(report.nextActions.join("\n"), /npm run bootstrap:cloudflare/);
});

test("health monitor deployment readiness deduplicates repeated next actions", () => {
  const report = buildDeploymentReadinessReport({
    localWorkflowExists: true,
    remoteWorkflowNames: ["Deploy Health Monitor Worker"],
    requiredGitHubSecrets: {
      CLOUDFLARE_API_TOKEN: null,
      CLOUDFLARE_ACCOUNT_ID: null,
      HEALTH_MONITOR_KV_NAMESPACE_ID: null,
      HEALTH_MONITOR_SLACK_WEBHOOK_URL: null,
      HEALTH_MONITOR_MANUAL_CHECK_TOKEN: null,
    },
    hasCloudflareApiToken: false,
  });

  const repeatedAction = "verify GitHub CLI auth with `gh auth status`; if invalid, run `gh auth login -h github.com`, then rerun readiness";
  assert.equal(
    report.nextActions.filter((action) => action === repeatedAction).length,
    1,
  );
});

test("health monitor package exposes a deployment readiness command", () => {
  const packageJson = JSON.parse(
    fs.readFileSync(path.join(repoRoot, "deploy", "cloudflare-health-monitor", "package.json"), "utf8"),
  );
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.equal(packageJson.scripts.readiness, "node scripts/readiness.js");
  assert.match(readme, /npm run readiness/);
  assert.match(readme, /prints blockers before relying on Slack outage alerts/);
});

test("health monitor docs include GitHub secret setup commands for readiness blockers", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  for (const name of requiredHealthMonitorGitHubSecrets) {
    assert.match(readme, new RegExp(`gh secret set ${name} --repo ghkdqhrbals/buddy-studdy`));
  }
});

test("health monitor docs explain how to obtain remaining Cloudflare readiness secrets", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /wrangler login/);
  assert.match(readme, /wrangler whoami/);
  assert.match(readme, /Cloudflare dashboard/i);
  assert.match(readme, /Workers Scripts/i);
  assert.match(readme, /Workers KV Storage/i);
  assert.match(readme, /wrangler kv namespace create HEALTH_MONITOR_STATE/);
  assert.match(readme, /HEALTH_MONITOR_KV_NAMESPACE_ID/);
});

test("health monitor readiness command supports json output", () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "health-monitor-readiness-"));
  const ghStub = path.join(tempDir, "gh");
  fs.writeFileSync(
    ghStub,
    `#!/bin/sh
if [ "$1" = "workflow" ]; then
  printf 'Build Backend Image\\tactive\\t1\\n'
  exit 0
fi
if [ "$1" = "secret" ]; then
  printf 'CLOUDFLARE_API_TOKEN\\t2026-07-03\\n'
  printf 'CLOUDFLARE_ACCOUNT_ID\\t2026-07-03\\n'
  printf 'HEALTH_MONITOR_KV_NAMESPACE_ID\\t2026-07-03\\n'
  printf 'HEALTH_MONITOR_SLACK_WEBHOOK_URL\\t2026-07-03\\n'
  printf 'HEALTH_MONITOR_MANUAL_CHECK_TOKEN\\t2026-07-03\\n'
  exit 0
fi
exit 1
`,
    { mode: 0o755 },
  );

  let output = "";
  try {
    execFileSync("node", ["scripts/readiness.js", "--json"], {
      cwd: path.join(repoRoot, "deploy", "cloudflare-health-monitor"),
      env: { ...process.env, PATH: `${tempDir}${path.delimiter}${process.env.PATH}` },
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    output = error.stdout;
  }

  const report = JSON.parse(output);
  assert.equal(report.ready, false);
  assert.deepEqual(report.blockers, [
    "Deploy Health Monitor Worker is not present on the remote default branch, so Worker secrets cannot be synced from GitHub Actions.",
  ]);
  assert.match(report.nextActions.join("\n"), /dispatch Deploy Health Monitor Worker/);
});

test("health monitor readiness command reports wrangler login when whoami prints unauthenticated with exit zero", () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "health-monitor-readiness-"));
  const ghStub = path.join(tempDir, "gh");
  const npxStub = path.join(tempDir, "npx");
  fs.writeFileSync(
    ghStub,
    `#!/bin/sh
if [ "$1" = "workflow" ]; then
  printf 'Deploy Health Monitor Worker\\tactive\\t1\\n'
  exit 0
fi
if [ "$1" = "secret" ]; then
  printf 'HEALTH_MONITOR_SLACK_WEBHOOK_URL\\t2026-07-03\\n'
  printf 'HEALTH_MONITOR_MANUAL_CHECK_TOKEN\\t2026-07-03\\n'
  exit 0
fi
exit 1
`,
    { mode: 0o755 },
  );
  fs.writeFileSync(
    npxStub,
    `#!/bin/sh
if [ "$1" = "wrangler" ] && [ "$2" = "whoami" ]; then
  printf 'You are not authenticated. Please run \`wrangler login\`.\\n'
  exit 0
fi
exit 1
`,
    { mode: 0o755 },
  );

  let output = "";
  try {
    execFileSync("node", ["scripts/readiness.js", "--json"], {
      cwd: path.join(repoRoot, "deploy", "cloudflare-health-monitor"),
      env: { ...process.env, PATH: `${tempDir}${path.delimiter}${process.env.PATH}` },
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    output = error.stdout;
  }

  const report = JSON.parse(output);
  assert.equal(report.ready, false);
  assert.match(report.nextActions.join("\n"), /run `npx wrangler login`/);
});

test("health monitor docs include post deploy verification without Actions checks", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /## Post-deploy Verification/);
  assert.match(readme, /GET https:\/\/<worker-host>\/`/);
  assert.match(readme, /npm run manual:check/);
  assert.match(readme, /ALLOW_DOWN=true/);
  assert.match(readme, /checkedAt` has advanced/);
});

test("health monitor docs describe manual check HTTP status contract", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /`POST \/check` uses the same state transition and Slack alert path as the cron/);
  assert.match(readme, /returns `200` only when the checked state is `up` or `degraded`/);
  assert.match(readme, /returns\s+`503`\s+for `down`, `stale`, `config_error`, or `monitor_error`/);
});

test("health monitor docs describe readiness response contract and stale status config", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "deploy/cloudflare-health-monitor/README.md"), "utf8");

  assert.match(readme, /non-JSON/i);
  assert.match(readme, /ok:true/);
  assert.match(readme, /STATUS_STALE_AFTER_SECONDS/);
  assert.match(readme, /Worker Cron itself stops running/i);
  assert.match(readme, /reports the stored state as `stale`/i);
  assert.match(readme, /Cloudflare Worker\s+observability/i);
});

test("backend API docs do not describe admin scheduler as deployment smoke health check", () => {
  const apiDoc = fs.readFileSync(path.join(repoRoot, "backend/API.md"), "utf8");

  assert.doesNotMatch(apiDoc, /deployment smoke tests/i);
  assert.match(apiDoc, /must not be called from GitHub Actions health checks/i);
});

test("backend README documents external uptime and scheduler alert boundaries", () => {
  const readme = fs.readFileSync(path.join(repoRoot, "backend/README.md"), "utf8");

  assert.match(readme, /Runtime uptime monitoring must not run from GitHub Actions/i);
  assert.match(readme, /GitHub Actions is only for build, deploy dispatch, and deploy-result watching/i);
  assert.match(readme, /Cloudflare Worker in\s+`deploy\/cloudflare-health-monitor`/i);
  assert.match(readme, /checks `\/api\/v1\/health\/readiness`\s+and sends Slack alerts/i);
  assert.match(readme, /The readiness endpoint checks required backend dependencies and\s+core scheduler freshness/i);
  assert.match(readme, /Kubernetes readiness probes should use `\/api\/v1\/health\/dependencies`/i);
  assert.match(readme, /`SLACK_WEBHOOK_URL` and a valid HTTPS\s+`MONITORING_ADMIN_BASE_URL` are required/i);
});
