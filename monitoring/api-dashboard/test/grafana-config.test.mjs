import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const composePath = path.resolve(testDirectory, "../../docker-compose.yml");
const gatewayPath = path.resolve(testDirectory, "../../grafana-gateway/nginx.conf");
const serverRuntimeDashboardPath = path.resolve(
  testDirectory,
  "../../grafana/dashboards/buddystudy-server-runtime.json",
);
const grafanaDashboardDirectory = path.resolve(
  testDirectory,
  "../../grafana/dashboards",
);
const testzoneDashboardPath = path.resolve(
  testDirectory,
  "../../grafana/dashboards/buddystudy-testzone.json",
);
const deployTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/deploy-macbookair-monitoring.yml",
);
const backendDeployTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/deploy-backend.yml",
);
const databaseCollectorPath = path.resolve(
  testDirectory,
  "../../scripts/database-runtime-collector.sh",
);

test("Grafana Live accepts only the public Grafana origin", async () => {
  const [compose, deployTemplate] = await Promise.all([
    fs.readFile(composePath, "utf8"),
    fs.readFile(deployTemplatePath, "utf8"),
  ]);

  for (const source of [compose, deployTemplate]) {
    assert.match(
      source,
      /GF_LIVE_ALLOWED_ORIGINS(?::|=) ?https:\/\/grafana\.lowfidev\.cloud/,
    );
  }
});

test("Grafana gateway restores the public origin consumed by Routingflare", async () => {
  const gateway = await fs.readFile(gatewayPath, "utf8");

  assert.match(gateway, /proxy_set_header Host grafana\.lowfidev\.cloud;/);
  assert.match(gateway, /proxy_set_header X-Forwarded-Host grafana\.lowfidev\.cloud;/);
  assert.match(gateway, /proxy_set_header X-Forwarded-Proto https;/);
  assert.match(gateway, /proxy_set_header X-Forwarded-Port 443;/);
  assert.doesNotMatch(gateway, /proxy_set_header Host \$host;/);
  assert.doesNotMatch(gateway, /proxy_set_header X-Forwarded-Host \$host;/);
  assert.doesNotMatch(gateway, /proxy_set_header X-Forwarded-Proto \$scheme;/);
});

test("monitoring deploy keeps unchanged Grafana and Loki containers running", async () => {
  const deployTemplate = await fs.readFile(deployTemplatePath, "utf8");

  assert.match(deployTemplate, /loki_config_changed=false/);
  assert.match(deployTemplate, /grafana_config_changed=false/);
  assert.match(deployTemplate, /container_running\(\)/);
  assert.match(
    deployTemplate,
    /if ! container_running buddystudy-loki \|\| \[ "\$\{loki_config_changed\}" = "true" \]/,
  );
  assert.match(
    deployTemplate,
    /if ! container_running buddystudy-grafana \|\| \[ "\$\{grafana_config_changed\}" = "true" \]/,
  );
  assert.match(deployTemplate, /dashboard_temp="\$\{dashboard_target\}\.tmp"/);
  assert.match(deployTemplate, /mv -f "\$\{dashboard_temp\}" "\$\{dashboard_target\}"/);
  assert.doesNotMatch(
    deployTemplate,
    /docker rm -f \\\n\s+buddystudy-api-dashboard \\\n\s+buddystudy-monitoring-promtail \\\n\s+buddystudy-grafana-gateway \\\n\s+buddystudy-loki \\\n\s+buddystudy-grafana/,
  );
});

test("server runtime dashboard emits bounded Loki metric series", async () => {
  const dashboard = JSON.parse(
    await fs.readFile(serverRuntimeDashboardPath, "utf8"),
  );
  const expressions = dashboard.panels.flatMap((panel) =>
    (panel.targets ?? []).map((target) => target.expr),
  );
  const runtimeExpressions = expressions.filter((expression) =>
    expression.includes('runtime_metrics "'),
  );
  const unwrappedExpressions = runtimeExpressions.filter((expression) =>
    expression.includes("| unwrap "),
  );

  assert.ok(runtimeExpressions.length > 0);
  assert.ok(unwrappedExpressions.length > 0);

  for (const expression of runtimeExpressions) {
    assert.match(expression, /\{container=~"buddystudy-backend\.\*"\}/);
  }

  for (const expression of unwrappedExpressions) {
    assert.match(expression, /^last_over_time\(/);
    assert.match(expression, /\| drop runtime \|/);
    assert.match(expression, /\| json \w+="\w+" \| unwrap \w+/);
    assert.doesNotMatch(expression, /\| json \| unwrap/);
  }
});

test("Grafana dashboards use supported fixed color field configuration", async () => {
  const dashboardFiles = (await fs.readdir(grafanaDashboardDirectory))
    .filter((fileName) => fileName.endsWith(".json"));

  for (const fileName of dashboardFiles) {
    const dashboard = JSON.parse(
      await fs.readFile(path.join(grafanaDashboardDirectory, fileName), "utf8"),
    );
    const panels = dashboard.panels ?? [];

    for (const panel of panels) {
      const color = panel.fieldConfig?.defaults?.color;
      if (!color) continue;

      assert.notEqual(
        color.mode,
        "fixedColor",
        `${fileName} panel "${panel.title}" uses an unsupported color mode`,
      );
      if (color.fixedColor) {
        assert.equal(
          color.mode,
          "fixed",
          `${fileName} panel "${panel.title}" must pair fixedColor with mode=fixed`,
        );
      }
    }
  }
});

test("server runtime dashboard separates server, database, and Redis signals", async () => {
  const dashboard = JSON.parse(
    await fs.readFile(serverRuntimeDashboardPath, "utf8"),
  );
  const rows = dashboard.panels
    .filter((panel) => panel.type === "row")
    .map((panel) => panel.title);
  const panels = new Map(dashboard.panels.map((panel) => [panel.title, panel]));

  assert.equal(dashboard.title, "BuddyStudy Server Dashboard");
  assert.match(dashboard.description, /JVM.*Reactor Netty.*R2DBC.*Redis/);
  assert.ok(dashboard.panels.length >= 14);
  assert.deepEqual(rows, ["Server", "Database", "Redis"]);
  for (const title of [
    "API RPS by endpoint",
    "CPU utilization",
    "JVM and process memory",
    "Runtime threads",
    "Garbage collection",
    "Server event loop",
    "Root disk",
    "Network counters",
    "R2DBC connection pool",
    "PostgreSQL CPU",
    "PostgreSQL connections",
    "Redis activity",
    "Redis failures",
  ]) {
    assert.ok(panels.has(title), `${title} panel must be provisioned`);
  }
  assert.ok(!panels.has("Runtime samples"));
  assert.ok(!panels.has("Request rate"));
  assert.ok(!panels.has("Process CPU"));
  assert.equal(panels.get("API RPS by endpoint")?.fieldConfig.defaults.unit, "reqps");
  assert.match(panels.get("API RPS by endpoint")?.targets[0].expr ?? "", /api_exchange/);
  assert.match(panels.get("API RPS by endpoint")?.targets[0].expr ?? "", /topk\(20/);
  assert.match(panels.get("API RPS by endpoint")?.targets[0].expr ?? "", /sum by \(method, path\)/);
  assert.equal(panels.get("API RPS by endpoint")?.targets[0].legendFormat, "{{method}} {{path}}");
  assert.equal(panels.get("R2DBC connection pool")?.gridPos.y, 26);
  assert.match(
    panels.get("R2DBC connection pool")?.targets.at(-1)?.expr ?? "",
    /dbPoolMaxAllocated/,
  );
  assert.match(
    panels.get("PostgreSQL CPU")?.targets[0].expr ?? "",
    /databaseCpuPercent/,
  );
  assert.match(
    panels.get("PostgreSQL connections")?.targets[2].expr ?? "",
    /databaseMaxConnections/,
  );
  assert.match(panels.get("Redis activity")?.targets[0].expr ?? "", /redis_/);
  assert.match(panels.get("Redis failures")?.targets[0].expr ?? "", /failed\|retry_scheduled/);
});

test("backend deploy starts a log-only PostgreSQL runtime collector", async () => {
  const [deployTemplate, collector] = await Promise.all([
    fs.readFile(backendDeployTemplatePath, "utf8"),
    fs.readFile(databaseCollectorPath, "utf8"),
  ]);

  assert.match(deployTemplate, /docker pull docker:27-cli/);
  assert.match(deployTemplate, /--name buddystudy-db-metrics/);
  assert.match(deployTemplate, /database-runtime-collector\.sh:\/collector\.sh:ro/);
  assert.match(deployTemplate, /DATABASE_METRICS_INTERVAL_SECONDS=30/);
  assert.match(deployTemplate, /PROFILE_PHOTO_PUBLIC_BASE_URL=https:\/\/\$\{BACKEND_DOMAIN\}/);
  assert.match(deployTemplate, /docker volume create buddystudy-profile-photos/);
  assert.match(deployTemplate, /buddystudy-profile-photos:\/app\/profile-photos/);
  assert.match(collector, /docker stats --no-stream/);
  assert.match(collector, /current_setting\('max_connections'\)/);
  assert.match(collector, /databaseCpuPercent/);
  assert.match(collector, /databaseMaxConnections/);
  assert.doesNotMatch(collector, /POSTGRES_PASSWORD/);
});

test("TestZone dashboard separates server, database, and Redis runtime signals", async () => {
  const dashboard = JSON.parse(
    await fs.readFile(testzoneDashboardPath, "utf8"),
  );
  const rows = dashboard.panels
    .filter((panel) => panel.type === "row")
    .map((panel) => panel.title);
  const panels = new Map(dashboard.panels.map((panel) => [panel.title, panel]));
  const templateNames = dashboard.templating.list.map((template) => template.name);

  assert.deepEqual(rows, ["Server", "Database", "Redis"]);
  assert.match(panels.get("HTTP success and errors per second")?.targets[0].query ?? "", /successCount/);
  assert.match(panels.get("HTTP success and errors per second")?.targets[1].query ?? "", /errorCount/);
  assert.match(panels.get("Response time avg \/ p90 \/ p95")?.targets[0].query ?? "", /averageMs/);
  assert.match(panels.get("RPS \/ average latency \/ error count")?.targets[0].query ?? "", /requestRate/);
  assert.match(panels.get("RPS \/ average latency \/ error count")?.targets[2].query ?? "", /errorCount/);
  assert.match(panels.get("PostgreSQL CPU")?.targets[0].query ?? "", /r\.component == "postgres"/);
  assert.match(panels.get("PostgreSQL memory")?.targets[0].query ?? "", /r\.component == "postgres"/);
  assert.match(panels.get("PostgreSQL connections")?.targets[2].query ?? "", /maxConnections/);
  assert.match(panels.get("Redis CPU")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.match(panels.get("Redis memory")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.match(panels.get("Redis activity")?.targets[0].query ?? "", /operationsPerSecond/);
  assert.ok(!templateNames.includes("component"));
});
