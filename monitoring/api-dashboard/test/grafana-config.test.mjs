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

  assert.deepEqual(rows, ["Server", "Database", "Redis"]);
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
  assert.match(panels.get("PostgreSQL CPU")?.targets[0].query ?? "", /r\.component == "postgres"/);
  assert.match(panels.get("PostgreSQL memory")?.targets[0].query ?? "", /r\.component == "postgres"/);
  assert.match(panels.get("Redis CPU")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.match(panels.get("Redis memory")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.ok(!templateNames.includes("component"));
});
