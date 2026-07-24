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
