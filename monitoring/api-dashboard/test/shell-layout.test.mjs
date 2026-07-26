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

test("Manage pages load the React application while observe pages keep the static shell", async () => {
  for (const page of ["audit.html", "settings.html", "users.html", "streams.html"]) {
    const html = await text(page);
    assert.match(html, /id="monitoring-react-root"/);
    assert.match(html, /src="\/react\/manage\.js\?/);
    assert.match(html, /href="\/react\/manage\.css\?/);
    assert.match(html, /src="\/nav-bootstrap\.js\?/);
  }
  for (const page of ["index.html", "performance.html", "testzone.html"]) {
    const html = await text(page);
    assert.match(html, /src="\/shell\.js\?/);
  }
  const navigation = await source("app/navigation.js");
  assert.match(navigation, /Access & Audit/);
  assert.match(navigation, /Users & Quotas/);
  assert.match(navigation, /Redis Streams/);
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
  const css = await text("styles.css");
  const shell = await text("shell.js");
  assert.match(css, /\.side-nav\s*\{[\s\S]*position:\s*fixed/);
  assert.match(css, /\.side-nav\s*\{[\s\S]*background:\s*var\(--nav\)/);
  assert.match(css, /--nav:\s*#000000/);
  assert.match(css, /\.side-nav-group summary\s*\{[\s\S]*background:\s*var\(--nav-2\)/);
  assert.match(css, /html\.nav-collapsed \.side-nav/);
  assert.match(css, /html\.nav-collapsed \.side-nav-link-label/);
  assert.match(css, /html:not\(\.nav-motion-ready\)[\s\S]*transition:\s*none !important/);
  assert.match(css, /--side-nav-rail-width:\s*64px/);
  assert.match(css, /\.side-nav-footer\s*\{[\s\S]*margin-top:\s*auto/);
  assert.match(shell, /createIcon\("menu"/);
  assert.match(shell, /setCollapsed\(!root\.classList\.contains\("nav-collapsed"\)\)/);
  assert.match(shell, /root\.classList\.add\("nav-motion-ready"\)/);
  assert.match(shell, /group\.dataset\.expandedOpen/);
  assert.match(shell, /group\.open = true/);
  assert.match(shell, /monitoring:nav-mode-change/);
  assert.doesNotMatch(shell, /reopen\.textContent/);
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
  const shell = await text("shell.js");
  assert.match(shell, /SERVER_DASHBOARD_URL/);
  assert.match(shell, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.match(html, /http-equiv="refresh"/);
  assert.match(html, /window\.location\.replace/);
  assert.match(html, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.doesNotMatch(html, /system\.js/);
});
