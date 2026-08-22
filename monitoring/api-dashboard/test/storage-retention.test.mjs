import test from "node:test";
import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const execFileAsync = promisify(execFile);
const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const monitoringDirectory = path.resolve(testDirectory, "../..");
const lokiConfigPath = path.join(monitoringDirectory, "loki/config/loki.yml");
const promtailConfigPath = path.join(
  monitoringDirectory,
  "promtail/config/promtail.yml",
);
const nginxConfigPath = path.join(
  monitoringDirectory,
  "api-dashboard/nginx.conf",
);
const composePath = path.join(monitoringDirectory, "docker-compose.yml");
const logRotationScriptPath = path.join(
  monitoringDirectory,
  "log-retention/rotate-gateway-logs.sh",
);
const deploymentTemplateDirectory = path.resolve(
  monitoringDirectory,
  "../docs/deploy-repo-template",
);
const monitoringWorkflowTemplatePath = path.join(
  deploymentTemplateDirectory,
  "deploy-macbookair-monitoring.yml",
);
const capacityWorkflowTemplatePath = path.join(
  deploymentTemplateDirectory,
  "maintain-macbookair-docker-capacity.yml",
);

function composeServiceBlocks(compose) {
  const blocks = new Map();
  const lines = compose.split("\n");
  let currentService;
  let inServices = false;

  for (const line of lines) {
    if (line === "services:") {
      inServices = true;
      currentService = undefined;
      continue;
    }

    if (/^[^ ]/.test(line)) {
      inServices = false;
      currentService = undefined;
      continue;
    }

    if (!inServices) {
      continue;
    }

    const serviceMatch = line.match(/^  ([a-z0-9-]+):$/);
    if (serviceMatch) {
      currentService = serviceMatch[1];
      blocks.set(currentService, []);
      continue;
    }

    if (currentService) {
      blocks.get(currentService).push(line);
    }
  }

  return new Map(
    [...blocks].map(([serviceName, blockLines]) => [
      serviceName,
      blockLines.join("\n"),
    ]),
  );
}

async function waitForFile(filePath) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      await fs.access(filePath);
      return;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  }

  assert.fail(`timed out waiting for ${filePath}`);
}

test("Loki compacts logs into the explicit seven-day retention window", async () => {
  const config = await fs.readFile(lokiConfigPath, "utf8");

  assert.match(config, /^  retention_period: 168h$/m);
  assert.doesNotMatch(config, /retention_stream:/);
  assert.match(config, /^  max_query_lookback: 168h$/m);
  assert.match(config, /^  working_directory: \/loki\/compactor$/m);
  assert.match(config, /^  compaction_interval: 10m$/m);
  assert.match(config, /^  apply_retention_interval: 10m$/m);
  assert.match(config, /^  retention_enabled: true$/m);
  assert.match(config, /^  retention_delete_delay: 2h$/m);
  assert.match(config, /^  retention_delete_worker_count: 20$/m);
  assert.match(config, /^  delete_request_store: filesystem$/m);
});

test("Promtail persists offsets and ships both gateway log files", async () => {
  const config = await fs.readFile(promtailConfigPath, "utf8");

  assert.match(config, /^  filename: \/var\/lib\/promtail\/positions\.yaml$/m);
  assert.match(config, /^  sync_period: 10s$/m);
  assert.match(config, /^  ignore_invalid_yaml: false$/m);
  assert.match(config, /^          job: monitoring-access$/m);
  assert.match(
    config,
    /^          __path__: \/var\/log\/monitoring\/monitoring-access\.log$/m,
  );
  assert.match(config, /^          job: monitoring-gateway-error$/m);
  assert.match(
    config,
    /^          __path__: \/var\/log\/monitoring\/monitoring-error\.log$/m,
  );
  assert.doesNotMatch(config, /monitoring-(?:access|error)\.log\*/);
});

test("Nginx writes explicit bounded-spool access and error logs", async () => {
  const config = await fs.readFile(nginxConfigPath, "utf8");

  assert.match(
    config,
    /^error_log \/var\/log\/nginx\/monitoring-error\.log warn;$/m,
  );
  assert.match(
    config,
    /^  access_log \/var\/log\/nginx\/monitoring-access\.log monitoring_access if=\$monitoring_audit_enabled;$/m,
  );
  assert.match(
    config,
    /listen 8082;[\s\S]*?server_name redis\.lowfidev\.cloud _;[\s\S]*?access_log \/var\/log\/nginx\/monitoring-access\.log monitoring_access if=\$monitoring_audit_enabled;/,
  );
});

test("every monitoring container rotates Docker local-driver output", async () => {
  const compose = await fs.readFile(composePath, "utf8");
  const serviceBlocks = composeServiceBlocks(compose);

  assert.match(
    compose,
    /^x-bounded-local-logging: &bounded-local-logging\n  driver: local\n  options:\n    max-size: "10m"\n    max-file: "3"\n    compress: "true"$/m,
  );
  assert.deepEqual([...serviceBlocks.keys()], [
    "incident-receiver",
    "api-dashboard",
    "monitoring-log-rotator",
    "monitoring-promtail",
    "loki",
    "grafana",
    "grafana-gateway",
    "testzone-influxdb",
    "testzone-service",
  ]);

  for (const [serviceName, serviceBlock] of serviceBlocks) {
    assert.match(
      serviceBlock,
      /^    logging: \*bounded-local-logging$/m,
      `${serviceName} must bound Docker local-driver output`,
    );
  }

  const rotator = serviceBlocks.get("monitoring-log-rotator");
  assert.match(rotator, /^    network_mode: none$/m);
  assert.match(rotator, /^    read_only: true$/m);
  assert.match(rotator, /^    pid: "service:api-dashboard"$/m);
  assert.match(rotator, /ACCESS_LOG_ROTATE_BYTES: "8388608"/);
  assert.match(rotator, /ERROR_LOG_ROTATE_BYTES: "2097152"/);
  assert.match(rotator, /ROTATED_LOG_COUNT: "3"/);
  assert.match(rotator, /LOG_ROTATE_STARTUP_GRACE_SECONDS: "60"/);
});

test("gateway log rotation archives only files at their configured thresholds", async (t) => {
  const temporaryDirectory = await fs.mkdtemp(
    path.join(os.tmpdir(), "buddystudy-monitoring-log-cap-"),
  );
  t.after(() => fs.rm(temporaryDirectory, { recursive: true, force: true }));

  const accessLogPath = path.join(temporaryDirectory, "monitoring-access.log");
  const errorLogPath = path.join(temporaryDirectory, "monitoring-error.log");
  await Promise.all([
    fs.writeFile(accessLogPath, "a".repeat(101)),
    fs.writeFile(errorLogPath, "e".repeat(5)),
    fs.writeFile(`${accessLogPath}.4`, "stale"),
  ]);

  await execFileAsync("/bin/sh", [logRotationScriptPath], {
    env: {
      ...process.env,
      LOG_DIRECTORY: temporaryDirectory,
      ACCESS_LOG_ROTATE_BYTES: "100",
      ERROR_LOG_ROTATE_BYTES: "10",
      ROTATED_LOG_COUNT: "3",
      LOG_ROTATE_CHECK_INTERVAL_SECONDS: "1",
      LOG_ROTATE_STARTUP_GRACE_SECONDS: "0",
      NGINX_MASTER_PID: "1",
      SKIP_NGINX_REOPEN: "true",
      RUN_ONCE: "true",
    },
  });

  const [accessStat, archivedAccessStat, errorStat] = await Promise.all([
    fs.stat(accessLogPath),
    fs.stat(`${accessLogPath}.1`),
    fs.stat(errorLogPath),
  ]);
  assert.equal(accessStat.size, 0);
  assert.equal(archivedAccessStat.size, 101);
  assert.equal(errorStat.size, 5);
  await assert.rejects(fs.stat(`${accessLogPath}.4`), { code: "ENOENT" });
});

test("gateway log rotation signals the shared Nginx master to reopen files", async (t) => {
  const temporaryDirectory = await fs.mkdtemp(
    path.join(os.tmpdir(), "buddystudy-monitoring-log-signal-"),
  );
  t.after(() => fs.rm(temporaryDirectory, { recursive: true, force: true }));

  const readyPath = path.join(temporaryDirectory, "ready");
  const signalPath = path.join(temporaryDirectory, "signalled");
  const signalTarget = spawn(
    process.execPath,
    [
      "-e",
      `
        const fs = require("node:fs");
        fs.writeFileSync(process.env.READY_PATH, "ready");
        process.on("SIGUSR1", () => {
          fs.writeFileSync(process.env.SIGNAL_PATH, "signalled");
          process.exit(0);
        });
        setInterval(() => {}, 1000);
      `,
    ],
    {
      env: {
        ...process.env,
        READY_PATH: readyPath,
        SIGNAL_PATH: signalPath,
      },
      stdio: "ignore",
    },
  );
  t.after(() => {
    if (signalTarget.exitCode === null && signalTarget.signalCode === null) {
      signalTarget.kill("SIGKILL");
    }
  });
  await waitForFile(readyPath);

  await fs.writeFile(
    path.join(temporaryDirectory, "monitoring-access.log"),
    "a".repeat(100),
  );
  await execFileAsync("/bin/sh", [logRotationScriptPath], {
    env: {
      ...process.env,
      LOG_DIRECTORY: temporaryDirectory,
      ACCESS_LOG_ROTATE_BYTES: "100",
      ERROR_LOG_ROTATE_BYTES: "10",
      ROTATED_LOG_COUNT: "3",
      LOG_ROTATE_CHECK_INTERVAL_SECONDS: "1",
      LOG_ROTATE_STARTUP_GRACE_SECONDS: "0",
      NGINX_MASTER_PID: String(signalTarget.pid),
      RUN_ONCE: "true",
    },
  });

  await waitForFile(signalPath);
});

test("gateway log rotation rejects unsafe byte limits", async () => {
  await assert.rejects(
    execFileAsync("/bin/sh", [logRotationScriptPath], {
      env: {
        ...process.env,
        ACCESS_LOG_ROTATE_BYTES: "0",
        RUN_ONCE: "true",
      },
    }),
    (error) => {
      assert.equal(error.code, 2);
      assert.match(
        error.stderr,
        /ACCESS_LOG_ROTATE_BYTES must be greater than zero/,
      );
      return true;
    },
  );
});

test("host-wide Docker storage cleanup has one bounded owner", async () => {
  const [monitoringWorkflow, capacityWorkflow] = await Promise.all([
    fs.readFile(monitoringWorkflowTemplatePath, "utf8"),
    fs.readFile(capacityWorkflowTemplatePath, "utf8"),
  ]);

  assert.doesNotMatch(monitoringWorkflow, /docker image prune/);
  assert.doesNotMatch(monitoringWorkflow, /docker builder prune/);
  assert.match(capacityWorkflow, /workflow_dispatch:/);
  assert.match(capacityWorkflow, /cron: "17 18 \* \* 0"/);
  assert.equal(
    capacityWorkflow.match(
      /docker image prune -a --filter "until=168h" --force/g,
    )?.length,
    1,
  );
  assert.equal(
    capacityWorkflow.match(
      /docker builder prune -a --filter until=168h --force/g,
    )?.length,
    1,
  );

  for (const forbiddenCommand of [
    "docker system prune",
    "docker container prune",
    "docker volume prune",
    "docker network prune",
    "docker buildx prune",
    "docker rmi",
    "docker rm",
  ]) {
    assert.doesNotMatch(capacityWorkflow, new RegExp(forbiddenCommand));
  }

  for (const forbiddenProbe of [
    "curl ",
    "wget ",
    "docker inspect",
    ".State.Health",
    "docker compose wait",
    "docker compose up --wait",
  ]) {
    assert.doesNotMatch(capacityWorkflow, new RegExp(forbiddenProbe));
  }

  assert.match(capacityWorkflow, /Status: maintenance submitted/);
  assert.match(capacityWorkflow, /Docker image prune reclaimed output/);
  assert.match(capacityWorkflow, /Docker build-cache prune reclaimed output/);
  assert.match(
    capacityWorkflow,
    /removed images can be pulled again from their registries/,
  );
  assert.match(
    capacityWorkflow,
    /removed build cache can be recreated by rebuilding/,
  );
  assert.match(capacityWorkflow, /active\/in-use build cache/);
});
