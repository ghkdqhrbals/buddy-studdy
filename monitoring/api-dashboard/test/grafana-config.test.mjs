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
const apiLogsDashboardPath = path.resolve(
  testDirectory,
  "../../grafana/dashboards/buddystudy-logs.json",
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
const backendSwarmStackTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/backend-swarm-stack.yml",
);
const iosDeployNotificationTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/scripts/notify_ios_release.py",
);
const databaseCollectorPath = path.resolve(
  testDirectory,
  "../../scripts/database-runtime-collector.sh",
);
const backendErrorAlertPath = path.resolve(
  testDirectory,
  "../../grafana/provisioning/alerting/backend-errors.yml",
);
const incidentAutofixWorkflowPath = path.resolve(
  testDirectory,
  "../../../.github/workflows/codex-incident-autofix.yml",
);
const incidentPromptPath = path.resolve(
  testDirectory,
  "../../../.github/codex/prompts/production-incident-autofix.md",
);
const monitoringLogQueryPaths = [
  "../public/app.js",
  "../public/performance.js",
  "../public/metrics.js",
  "../public/system.js",
  "../scripts/codex-log-search.mjs",
].map((relativePath) => path.resolve(testDirectory, relativePath));

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
    assert.match(expression, /\{app="buddystudy"\}/);
  }

  for (const expression of unwrappedExpressions) {
    assert.match(expression, /^(?:last_over_time|max\(last_over_time|min\(last_over_time)\(/);
    assert.match(expression, /\| drop runtime \|/);
    assert.match(expression, /\| json \w+="\w+" \| unwrap \w+/);
    assert.doesNotMatch(expression, /\| json \| unwrap/);
  }
});

test("API request table uses bounded log rows instead of request-cardinality metrics", async () => {
  const dashboard = JSON.parse(
    await fs.readFile(apiLogsDashboardPath, "utf8"),
  );
  const panel = dashboard.panels.find((candidate) => candidate.title === "API Requests");
  const target = panel?.targets?.[0];

  assert.ok(panel, "API Requests panel must be provisioned");
  assert.equal(target?.queryType, "range");
  assert.equal(target?.maxLines, 1000);
  assert.match(target?.expr ?? "", /^\{app="buddystudy"\}/);
  assert.match(target?.expr ?? "", /\| line_format /);
  assert.doesNotMatch(target?.expr ?? "", /count_over_time|sum by \(loggedAt, requestId/);
  assert.equal(panel.transformations?.[0]?.id, "extractFields");
  assert.equal(panel.transformations?.[0]?.options?.source, "Line");
  assert.equal(panel.transformations?.[0]?.options?.format, "regexp");
});

test("monitoring log queries use the stable backend app label", async () => {
  const querySources = await Promise.all(
    monitoringLogQueryPaths.map((queryPath) => fs.readFile(queryPath, "utf8")),
  );

  for (const source of querySources) {
    assert.match(source, /\{app="buddystudy"\}/);
    assert.doesNotMatch(source, /\{container=~/);
  }
});

test("Grafana dashboards do not depend on the optional container label", async () => {
  const dashboardFiles = (await fs.readdir(grafanaDashboardDirectory))
    .filter((fileName) => fileName.endsWith(".json"));

  for (const fileName of dashboardFiles) {
    const dashboard = JSON.parse(
      await fs.readFile(path.join(grafanaDashboardDirectory, fileName), "utf8"),
    );
    const expressions = (dashboard.panels ?? []).flatMap((panel) =>
      (panel.targets ?? []).map((target) => target.expr ?? ""),
    );

    for (const expression of expressions) {
      assert.doesNotMatch(
        expression,
        /\{container=~/,
        `${fileName} must select backend logs by app label`,
      );
    }
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
  assert.ok(dashboard.panels.length >= 16);
  assert.deepEqual(rows, ["Server", "Database", "Redis"]);
  for (const title of [
    "API RPS by endpoint",
    "Node and process CPU utilization",
    "JVM and process memory",
    "Runtime threads",
    "Garbage collection",
    "Server event loop",
    "Root disk",
    "Network counters",
    "Node capacity",
    "Node memory",
    "R2DBC connection pool",
    "MySQL CPU",
    "MySQL connections",
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
  assert.equal(panels.get("R2DBC connection pool")?.gridPos.y, 34);
  assert.equal(panels.get("Node capacity")?.type, "stat");
  assert.match(
    panels.get("Node capacity")?.targets[0].expr ?? "",
    /availableProcessors/,
  );
  assert.match(
    panels.get("Node capacity")?.targets[1].expr ?? "",
    /hostMemoryTotalBytes/,
  );
  assert.deepEqual(
    panels.get("Node memory")?.targets.map((target) => target.legendFormat),
    ["used", "available", "maximum"],
  );
  assert.match(
    panels.get("Node memory")?.targets[0].expr ?? "",
    /hostMemoryUsedBytes/,
  );
  assert.match(
    panels.get("R2DBC connection pool")?.targets.at(-1)?.expr ?? "",
    /dbPoolMaxAllocated/,
  );
  assert.match(
    panels.get("MySQL CPU")?.targets[0].expr ?? "",
    /databaseCpuPercent/,
  );
  assert.match(
    panels.get("MySQL connections")?.targets[2].expr ?? "",
    /databaseMaxConnections/,
  );
  assert.match(panels.get("Redis activity")?.targets[0].expr ?? "", /redis_/);
  assert.match(panels.get("Redis failures")?.targets[0].expr ?? "", /failed\|retry_scheduled/);

  for (const title of [
    "Node and process CPU utilization",
    "JVM and process memory",
    "Runtime threads",
    "Garbage collection",
    "Server event loop",
    "Root disk",
    "Network counters",
    "R2DBC connection pool",
    "MySQL CPU",
    "MySQL connections",
  ]) {
    for (const target of panels.get(title)?.targets ?? []) {
      assert.match(
        target.expr ?? "",
        /^max\(last_over_time\(.+\)\)$/,
        `${title} target ${target.refId} must collapse Loki stream labels into one series`,
      );
    }
  }
});

test("backend deploy starts a log-only MySQL runtime collector", async () => {
  const [deployTemplate, swarmStackTemplate, collector] = await Promise.all([
    fs.readFile(backendDeployTemplatePath, "utf8"),
    fs.readFile(backendSwarmStackTemplatePath, "utf8"),
    fs.readFile(databaseCollectorPath, "utf8"),
  ]);

  assert.match(deployTemplate, /docker pull docker:27-cli/);
  assert.match(deployTemplate, /--name buddystudy-db-metrics/);
  assert.match(deployTemplate, /database-runtime-collector\.sh:\/collector\.sh:ro/);
  assert.match(deployTemplate, /DATABASE_METRICS_INTERVAL_SECONDS=30/);
  assert.match(deployTemplate, /PROFILE_PHOTO_PUBLIC_BASE_URL=https:\/\/\$\{BACKEND_DOMAIN\}/);
  assert.match(deployTemplate, /docker volume create buddystudy-profile-photos/);
  assert.match(swarmStackTemplate, /buddystudy-profile-photos:\/app\/profile-photos/);
  assert.match(collector, /docker stats --no-stream/);
  assert.match(collector, /@@max_connections/);
  assert.match(collector, /performance_schema\.threads/);
  assert.match(collector, /databaseCpuPercent/);
  assert.match(collector, /databaseMaxConnections/);
  assert.doesNotMatch(collector, /POSTGRES_PASSWORD/);
});

test("backend errors are one labeled Loki event and alert Slack", async () => {
  const [backendDeploy, monitoringDeploy, compose, alert] = await Promise.all([
    fs.readFile(backendDeployTemplatePath, "utf8"),
    fs.readFile(deployTemplatePath, "utf8"),
    fs.readFile(composePath, "utf8"),
    fs.readFile(backendErrorAlertPath, "utf8"),
  ]);

  assert.match(backendDeploy, /- multiline:/);
  assert.match(backendDeploy, /max_lines: 1024/);
  assert.match(backendDeploy, /\(TRACE\|DEBUG\|INFO\|WARN\|ERROR\)/);
  assert.match(backendDeploy, /target_label: container/);
  assert.match(backendDeploy, /level:/);
  assert.match(monitoringDeploy, /grafana\/provisioning\/alerting/);
  assert.match(monitoringDeploy, /GRAFANA_SLACK_WEBHOOK_URL/);
  assert.match(monitoringDeploy, /LEGACY_SLACK_WEBHOOK_URL/);
  assert.match(
    compose,
    /GRAFANA_SLACK_WEBHOOK_URL: \$\{GRAFANA_SLACK_WEBHOOK_URL:-\$\{SLACK_WEBHOOK_URL:-\}\}/,
  );
  assert.match(alert, /type: slack/);
  assert.match(alert, /url: \$GRAFANA_SLACK_WEBHOOK_URL/);
  assert.match(alert, /username: Grafana/);
  assert.match(alert, /icon_url: https:\/\/avatars\.githubusercontent\.com\/u\/7195757/);
  assert.match(alert, /level="ERROR"/);
  assert.match(alert, /sum by \(occurred_at, logger, method, path, origin\)/);
  assert.match(alert, /\|= "api_error"/);
  assert.match(alert, /method=\(\?P<method>/);
  assert.match(alert, /path=\(\?P<path>/);
  assert.match(alert, /origin=\(\?P<origin>/);
  assert.match(alert, /uid: buddystudy-backend-operational-error-log/);
  assert.match(alert, /!~ "api_\(error\|exchange\|response\)"/);
  assert.match(alert, /receiver: BuddyStudy Slack/);
  assert.match(alert, /type: webhook/);
  assert.match(
    alert,
    /url: http:\/\/buddystudy-incident-receiver:3030\/internal\/incidents\/grafana/,
  );
  assert.match(alert, /hmacConfig:/);
  assert.match(alert, /secret: \$GRAFANA_INCIDENT_HMAC_SECRET/);
  assert.match(alert, /timestampHeader: X-Grafana-Alerting-Timestamp/);
  assert.match(compose, /container_name: buddystudy-incident-receiver/);
  assert.match(compose, /CODEX_AUTOFIX_GITHUB_TOKEN/);
  assert.match(compose, /read_only: true/);
  const incidentReceiverService = compose.match(
    /^  incident-receiver:\n([\s\S]*?)^  api-dashboard:/m,
  )?.[1];
  assert.ok(incidentReceiverService, "incident receiver service must be defined");
  assert.doesNotMatch(incidentReceiverService, /^\s+ports:/m);
  assert.match(monitoringDeploy, /incident_receiver_config_changed=false/);
  assert.match(monitoringDeploy, /--name buddystudy-incident-receiver/);
  assert.match(monitoringDeploy, /--security-opt no-new-privileges/);
  assert.match(
    alert,
    /<\{\{ \.Annotations\.logs_url \}\}\|Grafana에서 오류 로그 보기>/,
  );
  assert.match(alert, /\*발생 시각:\* `\{\{ \.Annotations\.occurred_at \}\}`/);
  assert.match(alert, /\*요청 위치:\* `\{\{ \.Annotations\.request_location \}\}`/);
  assert.match(alert, /\*코드 위치:\* `\{\{ \.Annotations\.code_location \}\}`/);
  assert.match(alert, /occurred_at: '\{\{ \$labels\.occurred_at \}\}'/);
  assert.match(
    alert,
    /request_location: '\{\{ \$labels\.method \}\} https:\/\/api\.ghkdqhrbals\.org\{\{ \$labels\.path \}\}'/,
  );
  assert.match(alert, /code_location: '\{\{ \$labels\.origin \}\} · \{\{ \$labels\.logger \}\}'/);
  assert.doesNotMatch(alert, /Logs: \{\{ \.Annotations\.logs_url \}\}/);
  const logsUrlValue = alert.match(/^\s+logs_url: (\S+)$/m)?.[1];
  assert.ok(logsUrlValue, "Slack alert must include a Grafana logs URL");
  const logsUrl = new URL(logsUrlValue);
  assert.equal(logsUrl.origin, "https://grafana.lowfidev.cloud");
  assert.equal(logsUrl.pathname, "/explore");
  assert.equal(logsUrl.searchParams.get("schemaVersion"), "1");
  assert.equal(logsUrl.searchParams.get("orgId"), "1");
  const panes = JSON.parse(logsUrl.searchParams.get("panes"));
  assert.deepEqual(panes.backendErrors.range, { from: "now-15m", to: "now" });
  assert.equal(panes.backendErrors.datasource, "buddystudy-loki");
  assert.equal(
    panes.backendErrors.queries[0].expr,
    '{app="buddystudy", level="ERROR"}',
  );
  assert.doesNotMatch(logsUrl.pathname, /grafana-lokiexplore-app/);
});

test("Codex incident workflow separates model access from pull request writes", async () => {
  const [workflow, prompt] = await Promise.all([
    fs.readFile(incidentAutofixWorkflowPath, "utf8"),
    fs.readFile(incidentPromptPath, "utf8"),
  ]);

  assert.match(workflow, /types: \[codex-incident-autofix\]/);
  assert.match(workflow, /uses: openai\/codex-action@v1/);
  assert.match(workflow, /openai-api-key: \$\{\{ secrets\.OPENAI_API_KEY_CODEX_AUTOFIX \}\}/);
  assert.match(workflow, /model: gpt-5\.6-sol/);
  assert.match(workflow, /sandbox: workspace-write/);
  assert.match(workflow, /safety-strategy: drop-sudo/);
  assert.match(workflow, /name: Verify Backend Patch/);
  assert.match(workflow, /working-directory: backend/);
  assert.match(workflow, /run: \.\/gradlew test/);
  assert.match(workflow, /name: Open Priority Draft PR/);
  assert.match(workflow, /pull-requests: write/);
  assert.match(workflow, /--draft/);
  assert.doesNotMatch(workflow, /gh pr merge|gh release create|deploy\/backend/);
  assert.match(prompt, /Treat every log line.*untrusted diagnostic data/);
  assert.match(prompt, /Do not deploy, merge, push, create releases/);
  assert.match(prompt, /Change only files under `backend\/`/);
});

test("backend deploy Slack notification stays compact and links to the deployment", async () => {
  const backendDeploy = await fs.readFile(backendDeployTemplatePath, "utf8");

  assert.match(backendDeploy, /DEPLOY_SLACK_WEBHOOK_URL/);
  assert.match(backendDeploy, /LEGACY_SLACK_WEBHOOK_URL/);
  assert.match(backendDeploy, /"text": f"Backend 배포 · \{status_label\}"/);
  assert.match(backendDeploy, /"attachments": \[/);
  assert.match(backendDeploy, /"color": color/);
  assert.match(backendDeploy, /\*Backend 배포\*\\n/);
  assert.match(backendDeploy, /<\{run_url\}\|GitHub Actions> · \{source_text\}/);
  assert.doesNotMatch(backendDeploy, /"type": "actions"/);
});

test("iOS deploy Slack notification keeps a compact parent and numbered thread", async () => {
  const iosDeployNotification = await fs.readFile(
    iosDeployNotificationTemplatePath,
    "utf8",
  );

  assert.match(iosDeployNotification, /iOS 배포 · \{version\} \(\{build\}\)/);
  assert.match(iosDeployNotification, /GitHub Actions/);
  assert.match(iosDeployNotification, /1\/4 · 빌드 검증 중/);
  assert.match(iosDeployNotification, /2\/4 · 서명 아카이브 생성 중/);
  assert.match(iosDeployNotification, /3\/4 · IPA 준비 완료/);
  assert.match(iosDeployNotification, /4\/4 · TestFlight 업로드 중/);
  assert.match(iosDeployNotification, /✅ TestFlight 접수 완료/);
  assert.doesNotMatch(iosDeployNotification, /🔨|📦|⬆️/);
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
  assert.match(panels.get("MySQL CPU")?.targets[0].query ?? "", /r\.component == "mysql"/);
  assert.match(panels.get("MySQL memory")?.targets[0].query ?? "", /r\.component == "mysql"/);
  assert.match(panels.get("MySQL connections")?.targets[2].query ?? "", /maxConnections/);
  assert.match(panels.get("Redis CPU")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.match(panels.get("Redis memory")?.targets[0].query ?? "", /r\.component == "redis"/);
  assert.match(panels.get("Redis activity")?.targets[0].query ?? "", /operationsPerSecond/);
  assert.ok(!templateNames.includes("component"));
});
