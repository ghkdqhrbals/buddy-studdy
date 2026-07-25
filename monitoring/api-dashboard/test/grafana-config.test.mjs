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
const testzoneDashboardPath = path.resolve(
  testDirectory,
  "../../grafana/dashboards/buddystudy-testzone.json",
);
const deployTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/deploy-macbookair-monitoring.yml",
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
  assert.ok(dashboard.panels.length >= 15);
  assert.deepEqual(rows, ["Server", "Database", "Redis"]);
  for (const title of [
    "Request rate",
    "CPU utilization",
    "JVM and process memory",
    "Runtime threads",
    "Garbage collection",
    "Server event loop",
    "Root disk",
    "Network counters",
    "R2DBC connection pool",
    "Redis activity",
    "Redis failures",
  ]) {
    assert.ok(panels.has(title), `${title} panel must be provisioned`);
  }
  assert.ok(!panels.has("Runtime samples"));
  assert.equal(panels.get("Request rate")?.fieldConfig.defaults.unit, "reqps");
  assert.match(panels.get("Request rate")?.targets[0].expr ?? "", /api_exchange/);
  assert.match(panels.get("Request rate")?.targets[0].expr ?? "", /sum\(rate\(/);
  assert.match(panels.get("Request rate")?.targets[0].expr ?? "", /runtime_metrics/);
  assert.match(panels.get("Request rate")?.targets[0].expr ?? "", /\* 0\)/);
  assert.doesNotMatch(panels.get("Request rate")?.targets[0].expr ?? "", /vector\(0\)/);
  assert.equal(panels.get("R2DBC connection pool")?.gridPos.y, 26);
  assert.match(panels.get("Redis activity")?.targets[0].expr ?? "", /redis_/);
  assert.match(panels.get("Redis failures")?.targets[0].expr ?? "", /failed\|retry_scheduled/);
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
