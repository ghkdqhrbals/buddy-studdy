import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicRoot = new URL("../public/", import.meta.url);
const sourceRoot = new URL("../src/", import.meta.url);

async function text(file) {
  return readFile(new URL(file, publicRoot), "utf8");
}

async function source(file) {
  return readFile(new URL(file, sourceRoot), "utf8");
}

test("all monitoring pages load the shared React application", async () => {
  for (const page of [
    "index.html",
    "performance.html",
    "testzone.html",
    "audit.html",
    "settings.html",
    "users.html",
    "jobs.html",
    "streams.html",
  ]) {
    const html = await text(page);
    assert.match(html, /id="monitoring-react-root"/);
    assert.match(html, /src="\/react\/manage\.js\?/);
    assert.match(html, /href="\/react\/manage\.css\?/);
    assert.match(html, /src="\/nav-bootstrap\.js\?/);
    assert.doesNotMatch(html, /src="\/shell\.js\?/);
  }
  const app = await source("MonitoringApp.jsx");
  assert.match(app, /ApiLogsPage/);
  assert.match(app, /ApiPerformancePage/);
  assert.match(app, /TestZonePage/);
  const navigation = await source("app/navigation.js");
  assert.match(navigation, /Access & Audit/);
  assert.match(navigation, /Users & Quotas/);
  assert.match(navigation, /Batch Jobs/);
  assert.match(navigation, /Redis Streams/);
  assert.match(navigation, /Service Status/);
});

test("batch jobs show operator metadata, timing, results, paginated history, and retry controls", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/JobsPage.jsx");
  assert.match(app, /jobs\.html/);
  assert.match(page, /\/jobs\/statuses/);
  assert.match(page, /\/jobs\/runs/);
  assert.match(page, /displayName/);
  assert.match(page, /description/);
  assert.match(page, /Last started/);
  assert.match(page, /Duration/);
  assert.match(page, /Latest result/);
  assert.match(page, /Pagination/);
  assert.match(page, /Retry job/);
  assert.match(page, /retryOfRunId/);
});

test("service status administration schedules, ends, and audits maintenance windows", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/ServiceStatusPage.jsx");
  assert.match(app, /service-status\.html/);
  assert.match(page, /Start maintenance/);
  assert.match(page, /Schedule maintenance/);
  assert.match(page, /End maintenance/);
  assert.match(page, /Maintenance history/);
  assert.match(page, /service-maintenance\/history/);
  assert.match(page, /titleKo/);
  assert.match(page, /titleEn/);
  assert.match(page, /titleJa/);
});

test("user administration is searchable, paginated, and keeps plans internal", async () => {
  const page = await source("pages/UsersPage.jsx");
  const adminApi = await source("admin/adminApi.js");
  assert.match(page, /const PAGE_SIZE = 20/);
  assert.match(page, /SearchField/);
  assert.match(page, /Pagination/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /ObjectInspector/);
  assert.match(page, /monthlyQuestionLimitOverride/);
  assert.match(adminApi, /sessionStorage/);
  assert.match(adminApi, /Authorization: `Bearer \$\{session\.token\}`/);
  assert.doesNotMatch(page, /payment|billing/i);
});

test("Redis Stream administration lives in monitoring Manage with bounded cursor navigation", async () => {
  const page = await source("pages/StreamsPage.jsx");
  const paths = await source("lib/streamPaths.js");
  assert.match(page, /Stream entries/);
  assert.match(page, /Event outbox/);
  assert.match(page, /Push outbox/);
  assert.match(page, /cursorStack/);
  assert.match(page, /ObjectInspector/);
  assert.match(paths, /streamEntriesPath/);
  assert.match(paths, /streamEntryPath/);
  assert.doesNotMatch(page, /innerHTML/);
});

test("navigation is fixed, collapsible, and keeps its version at the bottom", async () => {
  const css = await source("styles/manage.css");
  const shell = await source("app/AppShell.jsx");
  assert.match(css, /\.react-side-nav\s*\{[\s\S]*position:\s*fixed/);
  assert.match(css, /background:\s*var\(--nav-bg\)/);
  assert.match(css, /--nav-bg:\s*#000000/);
  assert.match(css, /\.react-side-nav\[data-collapsed="true"\]/);
  assert.match(css, /\.react-nav-footer\s*\{[\s\S]*position:\s*absolute/);
  assert.match(shell, /PanelLeftOpen/);
  assert.match(shell, /PanelLeftClose/);
  assert.match(shell, /monitoring:nav-mode-change/);
  assert.match(shell, /navigationGroups\.map/);
});

test("settings control navigation and access journal browser preferences", async () => {
  const page = await source("pages/SettingsPage.jsx");
  assert.match(page, /buddystudy\.monitoring\.nav\.mode/);
  assert.match(page, /buddystudy\.monitoring\.audit\.range/);
  assert.match(page, /buddystudy\.monitoring\.audit\.refreshSeconds/);
  assert.match(page, /buddystudy\.monitoring\.audit\.pageSize/);
  assert.match(page, /monitoring:nav-mode-change/);
});

test("access audit reads the monitoring gateway journal instead of backend API logs", async () => {
  const page = await source("pages/AuditPage.jsx");
  assert.match(page, /\{job="monitoring-access"\}/);
  assert.match(page, /parseMonitoringAccessLog/);
  assert.match(page, /DetailDrawer/);
  assert.doesNotMatch(page, /api_exchange/);
  assert.doesNotMatch(page, /parseApiExchange/);
});

test("server dashboard navigation and legacy URL open the detailed Grafana dashboard", async () => {
  const html = await text("system.html");
  const navigation = await source("app/navigation.js");
  assert.match(navigation, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.match(html, /http-equiv="refresh"/);
  assert.match(html, /window\.location\.replace/);
  assert.match(html, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.doesNotMatch(html, /system\.js/);
});
