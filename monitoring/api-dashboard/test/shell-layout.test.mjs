import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicRoot = new URL("../public/", import.meta.url);

async function text(file) {
  return readFile(new URL(file, publicRoot), "utf8");
}

test("all monitoring pages load the shared navigation shell", async () => {
  for (const page of ["index.html", "performance.html", "testzone.html", "audit.html", "settings.html", "users.html"]) {
    const html = await text(page);
    assert.match(html, /src="\/shell\.js\?/);
    assert.match(html, /src="\/nav-bootstrap\.js\?/);
    assert.ok(
      html.indexOf("/nav-bootstrap.js") < html.indexOf("/styles.css"),
      `${page} must restore navigation state before styles load`,
    );
  }
  const shell = await text("shell.js");
  assert.match(shell, /Access & Audit/);
  assert.match(shell, /Settings/);
  assert.match(shell, /Users & Quotas/);
  assert.match(shell, /Load testing/);
  assert.match(shell, /side-nav-footer/);
  assert.match(shell, /NAV_COLLAPSED_KEY/);
});

test("user administration is searchable, paginated, and keeps plans internal", async () => {
  const html = await text("users.html");
  const js = await text("users.js");
  const css = await text("styles.css");
  assert.match(html, /id="adminUserSearch"/);
  assert.match(html, /id="adminPreviousButton"/);
  assert.match(html, /id="adminNextButton"/);
  assert.match(html, /Plan limits/);
  assert.match(js, /const PAGE_SIZE = 20/);
  assert.match(js, /monthlyQuestionLimitOverride/);
  assert.match(js, /sessionStorage/);
  assert.match(js, /function escapeHTML/);
  assert.match(js, /Array\.isArray\(page\.users\)/);
  assert.match(js, /Array\.isArray\(tiers\)/);
  assert.match(css, /\[hidden\]\s*\{[\s\S]*display:\s*none !important/);
  assert.doesNotMatch(html, /payment|billing/i);
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
  const html = await text("settings.html");
  const js = await text("settings.js");
  assert.match(html, /id="navigationMode"/);
  assert.match(html, /id="auditDefaultRange"/);
  assert.match(html, /id="auditRefreshSeconds"/);
  assert.match(html, /id="auditPageSize"/);
  const css = await text("styles.css");
  assert.match(css, /\.settings-row select\s*\{[\s\S]*height:\s*38px/);
  assert.match(css, /\.settings-actions \.status-message:empty\s*\{[\s\S]*display:\s*none/);
  assert.match(js, /buddystudy\.monitoring\.nav\.mode/);
  assert.match(js, /buddystudy\.monitoring\.audit\.range/);
  assert.match(js, /monitoring:nav-mode-change/);
});

test("access audit reads the monitoring gateway journal instead of backend API logs", async () => {
  const html = await text("audit.html");
  const js = await text("audit.js");
  assert.match(html, /Monitoring access journal/);
  assert.match(html, /passwords and request bodies are never recorded/);
  assert.match(js, /\{job="monitoring-access"\}/);
  assert.match(js, /parseMonitoringAccessLog/);
  assert.doesNotMatch(js, /api_exchange/);
  assert.doesNotMatch(js, /parseApiExchange/);
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
