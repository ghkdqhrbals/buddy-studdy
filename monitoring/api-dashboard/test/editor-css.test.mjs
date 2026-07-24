import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const css = await readFile(new URL("../public/testzone.css", import.meta.url), "utf8");
const html = await readFile(new URL("../public/testzone.html", import.meta.url), "utf8");
const javascript = await readFile(new URL("../public/testzone.js", import.meta.url), "utf8");

test("script editor hides native glyphs behind the syntax highlight layer", () => {
  const editorRule = css.match(/\.code-editor #scriptEditor \{(?<body>[^}]+)\}/)?.groups?.body ?? "";
  assert.match(editorRule, /color:\s*transparent;/);
  assert.match(editorRule, /-webkit-text-fill-color:\s*transparent;/);
  assert.match(editorRule, /text-shadow:\s*none;/);

  const highlightRule = css.match(/\.script-highlight \{(?<body>[^}]+)\}/)?.groups?.body ?? "";
  assert.match(highlightRule, /color:\s*#dce6f4;/);
  assert.match(highlightRule, /pointer-events:\s*none;/);
  assert.match(highlightRule, /align-self:\s*stretch;/);
  assert.match(highlightRule, /max-height:\s*none;/);
  assert.doesNotMatch(highlightRule, /height:\s*100%;/);
});

test("script workspace contains only files, editor, and Run Plan controls", () => {
  for (const source of [html, css, javascript]) {
    assert.doesNotMatch(source, /assistant/i);
  }
  assert.match(html, /id="editorRunButton"/);
  assert.doesNotMatch(html, /id="runDialog"/);
});

test("workspace starts the selected script immediately without a run form", () => {
  assert.equal((html.match(/id="overviewRunButton"/g) || []).length, 1);
  assert.doesNotMatch(html, /id="headerRunButton"|>Run test</);
  assert.match(html, /id="overviewRunButton" class="run-launch-button"/);
  assert.match(html, /class="run-launch-icon" aria-hidden="true">\+<\/span>/);
  assert.match(css, /\.run-launch-button:focus-visible/);
  assert.doesNotMatch(html, /id="projectBaseUrl"|id="saveProjectButton"|id="newProjectBaseUrl"/);
  assert.doesNotMatch(html, /runTargetUrl|runHeaders|runEnvironment|startRunButton/);
  assert.match(javascript, /async function startRun\(scriptId = state\.scriptId/);
  assert.match(javascript, /body:\s*JSON\.stringify\(\{\s*projectId:\s*state\.projectId,\s*scriptId:\s*selectedScript\.id,\s*\}\)/);
  assert.match(javascript, /run\.targetUrl\s*\|\|\s*"-"/);
  assert.doesNotMatch(html, /id="runDuration"|id="runVus"|id="runMaxVus"|id="runTargetRps"/);
  assert.doesNotMatch(html, /id="runProfileControl"|id="profileShortcuts"|id="quickScriptSelect"/);
  assert.doesNotMatch(javascript, /openRunDialog|runForm|runTargetUrl|runHeaders|runEnvironment/);
  assert.match(javascript, /formatRunLoadPlan\(run\.options\)/);
});

test("run charts use vendored uPlot with unit-specific live series", () => {
  const chartCode = javascript.match(/function runChartData\(\)[\s\S]+?function formatRunLoadPlan/)?.[0] ?? "";
  assert.match(html, /vendor\/uplot\/uPlot\.iife\.min\.js\?v=1\.6\.32/);
  assert.match(html, /vendor\/uplot\/uPlot\.min\.css\?v=1\.6\.32/);
  assert.match(css, /\.run-history-chart \.u-legend/);
  assert.match(chartCode, /new window\.uPlot/);
  assert.match(chartCode, /label:\s*"RPS"/);
  assert.match(chartCode, /label:\s*"p95"/);
  assert.match(chartCode, /label:\s*"Error"/);
  assert.doesNotMatch(chartCode, /VUs|point\.vus/);
  assert.doesNotMatch(html, /runChartTooltip/);
});

test("run charts cap their content width and support horizontal drag inspection", () => {
  const chartCode = javascript.match(/const MAX_RUN_CHART_WIDTH[\s\S]+?function formatRunLoadPlan/)?.[0] ?? "";
  assert.match(chartCode, /MAX_RUN_CHART_WIDTH\s*=\s*1200/);
  assert.match(chartCode, /RUN_CHART_VISIBLE_SAMPLES\s*=\s*120/);
  assert.match(chartCode, /Math\.min\(MAX_RUN_CHART_WIDTH,\s*runChartViewportWidth\(host\)\)/);
  assert.match(chartCode, /function runChartPanPlugin/);
  assert.match(chartCode, /plot\.setScale\("x", range\)/);
  assert.match(chartCode, /overlay\.setPointerCapture\(pointerId\)/);
  assert.match(css, /\.run-history-chart\s*\{[\s\S]+?max-width:\s*1200px/);
  assert.match(css, /\.run-history-chart\.is-pannable \.u-over\s*\{[\s\S]+?cursor:\s*grab/);
});

test("selected history run renders its time-series inside the detail panel", () => {
  const detail = html.match(/<section id="runDetail"[\s\S]+?<\/section>\s*<\/section>/)?.[0] ?? "";
  assert.match(detail, /id="runDetailChart"/);
  assert.match(detail, /id="runDetailChartEmpty"/);
  assert.match(css, /\.run-detail-timeline/);
  assert.match(
    javascript,
    /renderRunChart\(elements\.runDetailChart,\s*elements\.runDetailChartEmpty,\s*"detail"\)/,
  );
  assert.doesNotMatch(detail, /runDetailChartTooltip/);
});

test("workspace manages projects and components expose restart without apply", () => {
  assert.match(html, /id="newProjectButton"/);
  assert.match(html, /id="deleteProjectButton"/);
  assert.match(html, /id="newProjectDialog"/);
  assert.match(javascript, /api\("\/projects",\s*\{[\s\S]+?method:\s*"POST"/);
  assert.match(javascript, /api\(`\/projects\/\$\{selected\.id\}`,\s*\{\s*method:\s*"DELETE"/);
  assert.match(javascript, /componentAction\("Restart", component\.id, "restart", true\)/);
  assert.doesNotMatch(javascript, /componentAction\("Apply"/);
  assert.doesNotMatch(javascript, /Use Apply/);
});
