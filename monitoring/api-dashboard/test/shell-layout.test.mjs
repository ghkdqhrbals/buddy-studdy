import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicRoot = new URL("../public/", import.meta.url);

async function text(file) {
  return readFile(new URL(file, publicRoot), "utf8");
}

test("all monitoring pages load the shared navigation shell", async () => {
  for (const page of ["index.html", "performance.html", "system.html", "testzone.html", "audit.html"]) {
    assert.match(await text(page), /src="\/shell\.js\?/);
  }
  const shell = await text("shell.js");
  assert.match(shell, /Access & Audit/);
  assert.match(shell, /Load testing/);
  assert.match(shell, /side-nav-footer/);
  assert.match(shell, /NAV_COLLAPSED_KEY/);
});

test("navigation is fixed, collapsible, and keeps its version at the bottom", async () => {
  const css = await text("styles.css");
  const shell = await text("shell.js");
  assert.match(css, /\.side-nav\s*\{[\s\S]*position:\s*fixed/);
  assert.match(css, /\.side-nav\s*\{[\s\S]*background:\s*var\(--nav\)/);
  assert.match(css, /\.side-nav-group summary\s*\{[\s\S]*background:\s*var\(--nav-2\)/);
  assert.match(css, /body\.nav-collapsed \.side-nav/);
  assert.match(css, /body\.nav-collapsed \.side-nav-link-label/);
  assert.match(css, /--side-nav-rail-width:\s*64px/);
  assert.match(css, /\.side-nav-footer\s*\{[\s\S]*margin-top:\s*auto/);
  assert.match(shell, /createIcon\("menu"/);
  assert.match(shell, /setCollapsed\(!document\.body\.classList\.contains\("nav-collapsed"\)\)/);
  assert.doesNotMatch(shell, /reopen\.textContent/);
});

test("server charts expose hover tooltips and JVM pressure summaries", async () => {
  const html = await text("system.html");
  const js = await text("system.js");
  assert.match(html, /id="heapSummary"/);
  assert.match(html, /id="gcPauseSummary"/);
  assert.match(html, /id="heapPressureChart"/);
  assert.match(html, /id="gcCountChart"/);
  assert.match(js, /configureChartTooltip/);
  assert.match(js, /mousemove/);
  assert.match(js, /metric-chart-tooltip/);
  assert.match(js, /updateCustomRangeVisibility\(\);\s*render\(\);\s*loadMetrics/);
});
