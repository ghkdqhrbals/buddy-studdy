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

test("script workspace contains only files, editor, and editor actions", () => {
  for (const source of [html, css, javascript]) {
    assert.doesNotMatch(source, /assistant/i);
  }
  assert.match(html, /id="editorRunButton"/);
  assert.doesNotMatch(html, /id="runDialog"/);
});

test("workspace runs tests only from the saved script editor", () => {
  assert.doesNotMatch(html, /id="overviewRunButton"|id="headerRunButton"|>New run<|>Run test</);
  assert.doesNotMatch(css, /\.run-launch-button|\.run-launch-icon/);
  assert.equal((html.match(/id="editorRunButton"/g) || []).length, 1);
  assert.doesNotMatch(html, /id="projectBaseUrl"|id="saveProjectButton"|id="newProjectBaseUrl"/);
  assert.doesNotMatch(html, /runTargetUrl|runHeaders|runEnvironment|startRunButton/);
  assert.match(
    javascript,
    /async function startRun\(scriptId = state\.scriptId,\s*button = elements\.editorRunButton\)/,
  );
  assert.match(javascript, /body:\s*JSON\.stringify\(\{\s*projectId:\s*state\.projectId,\s*scriptId:\s*selectedScript\.id,\s*\}\)/);
  assert.match(javascript, /run\.targetUrl\s*\|\|\s*"-"/);
  assert.doesNotMatch(html, /id="runDuration"|id="runVus"|id="runMaxVus"|id="runTargetRps"/);
  assert.doesNotMatch(html, /id="runProfileControl"|id="profileShortcuts"|id="quickScriptSelect"/);
  assert.doesNotMatch(javascript, /openRunDialog|runForm|runTargetUrl|runHeaders|runEnvironment/);
  assert.doesNotMatch(javascript, /overviewRunButton/);
  assert.match(javascript, /formatRunLoadPlan\(run\.options\)/);
});

test("run charts use vendored uPlot with separate RED and latency percentile views", () => {
  const chartCode = javascript.match(/function runChartData\(\)[\s\S]+?function formatRunLoadPlan/)?.[0] ?? "";
  assert.match(html, /vendor\/uplot\/uPlot\.iife\.min\.js\?v=1\.6\.32/);
  assert.match(html, /vendor\/uplot\/uPlot\.min\.css\?v=1\.6\.32/);
  assert.match(css, /\.run-history-chart \.u-legend/);
  assert.match(chartCode, /new window\.uPlot/);
  assert.match(chartCode, /label:\s*"RPS"/);
  assert.match(chartCode, /label:\s*"Median"/);
  assert.match(chartCode, /label:\s*"p90"/);
  assert.match(chartCode, /label:\s*"p95"/);
  assert.match(chartCode, /label:\s*"Error"/);
  assert.match(html, /id="runTrafficChart"/);
  assert.match(html, /id="runLatencyChart"/);
  assert.doesNotMatch(chartCode, /VUs|point\.vus/);
  assert.doesNotMatch(html, /runChartTooltip/);
});

test("run charts cap their content width and support horizontal drag inspection", () => {
  const chartCode = javascript.match(/const MAX_RUN_CHART_WIDTH[\s\S]+?function formatRunLoadPlan/)?.[0] ?? "";
  assert.match(chartCode, /MAX_RUN_CHART_WIDTH\s*=\s*1200/);
  assert.match(chartCode, /RUN_CHART_VISIBLE_SAMPLES\s*=\s*120/);
  assert.match(chartCode, /Math\.min\(MAX_RUN_CHART_WIDTH,\s*runChartViewportWidth\(host\)\)/);
  assert.match(chartCode, /function runChartPanPlugin/);
  assert.match(chartCode, /chart\?\.setScale\("x", range\)/);
  assert.match(chartCode, /overlay\.setPointerCapture\(pointerId\)/);
  assert.match(css, /\.run-history-chart\s*\{[\s\S]+?max-width:\s*1200px/);
  assert.match(css, /\.run-history-chart\.is-pannable \.u-over\s*\{[\s\S]+?cursor:\s*grab/);
});

test("selected history run renders its time-series inside the detail panel", () => {
  const detail = html.match(/<section id="runDetail"[\s\S]+?<\/section>\s*<\/section>/)?.[0] ?? "";
  assert.match(detail, /id="runDetailTrafficChart"/);
  assert.match(detail, /id="runDetailLatencyChart"/);
  assert.match(detail, /id="runDetailChartEmpty"/);
  assert.match(css, /\.run-detail-timeline/);
  assert.match(
    javascript,
    /renderRunCharts\("detail",\s*\{[\s\S]+?traffic:\s*elements\.runDetailTrafficChart,[\s\S]+?latency:\s*elements\.runDetailLatencyChart/,
  );
  assert.doesNotMatch(detail, /runDetailChartTooltip/);
});

test("run history paginates by ten and can rerun immutable script snapshots", () => {
  assert.match(html, /id="runPreviousPageButton"/);
  assert.match(html, /id="runPageLabel"/);
  assert.match(html, /id="runNextPageButton"/);
  assert.match(html, /id="rerunSelectedRunButton"/);
  assert.match(javascript, /&page=\$\{state\.runPage\}/);
  assert.match(javascript, /payload\.pagination\?\.pageSize\s*\?\?\s*10/);
  assert.match(javascript, /api\(`\/runs\/\$\{run\.id\}\/rerun`,\s*\{\s*method:\s*"POST"\s*\}\)/);
  assert.match(javascript, /actionButton\("Rerun"/);
});

test("TestZone chart cards do not inherit the global fixed metric chart height", () => {
  assert.match(html, /class="run-metric-grid"/);
  assert.match(html, /class="run-metric-card"/);
  assert.doesNotMatch(html, /class="metric-chart"/);
  assert.match(css, /\.run-metric-card\s*\{[\s\S]+?height:\s*auto;/);
  assert.match(css, /\.run-metric-card\s*\{[\s\S]+?overflow:\s*hidden;/);
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

test("new projects stay empty and the plus button opens an unsaved blank script", () => {
  assert.doesNotMatch(html, /id="newScriptDialog"|id="newScriptForm"|id="newScriptDescription"/);
  assert.match(javascript, /function beginNewScript\(\)/);
  assert.match(javascript, /state\.scriptId\s*=\s*null;\s*state\.creatingScript\s*=\s*true;\s*renderScripts\(\);/);
  assert.match(javascript, /elements\.scriptEditor\.value\s*=\s*"";/);
  assert.match(javascript, /elements\.newScriptButton\.addEventListener\("click",\s*beginNewScript\)/);
  assert.match(javascript, /switchTab\("scripts"\);\s*toast\(`\$\{created\.name\} project created\.`\)/);
  assert.match(javascript, /function renderEditorFileName\(\)/);
  assert.match(
    javascript,
    /if\s*\(state\.creatingScript\)\s*\{[\s\S]+?api\("\/scripts",\s*\{[\s\S]+?method:\s*"POST"/,
  );
  assert.match(javascript, /code:\s*elements\.scriptEditor\.value/);
  assert.doesNotMatch(javascript, /code:\s*script\(\)\?\.code\s*\|\|/);
  assert.match(css, /\.script-list-empty/);
});

test("file names update immediately and save shortcuts work across the editor toolbar", () => {
  assert.match(javascript, /document\.querySelector\("\.editor-pane"\)\.addEventListener\("keydown",\s*handleEditorKeydown\)/);
  assert.match(javascript, /\(event\.metaKey \|\| event\.ctrlKey\) && event\.key\.toLowerCase\(\) === "s"/);
  assert.match(javascript, /if \(!elements\.saveScriptButton\.disabled\) void saveScript\(\)/);
  assert.match(javascript, /elements\.scriptNameInput\.addEventListener\("input",[\s\S]+?renderEditorFileName\(\)/);
  assert.match(javascript, /detail\.textContent = "Unsaved"/);
  assert.doesNotMatch(javascript, /elements\.scriptEditor\.addEventListener\("keydown",\s*handleEditorKeydown\)/);
});

test("save failures stay visible beside the editor and clear only after a successful save", () => {
  assert.match(html, /id="editorProblemPanel"[\s\S]+?role="alert"/);
  assert.match(html, /id="editorProblemTitle"/);
  assert.match(html, /id="editorProblemMessage"/);
  assert.match(css, /\.editor-problem-panel\s*\{/);
  assert.match(javascript, /title:\s*"Save failed"/);
  assert.match(javascript, /message:\s*"Changes were not saved\./);
  assert.match(javascript, /state\.dirty\s*=\s*false;[\s\S]+?clearEditorProblem\(\);[\s\S]+?"Saved"/);
});

test("overview keeps run creation in the script editor and exposes six latency summary values", () => {
  assert.doesNotMatch(html, /id="overviewRunButton"|>New run</i);
  for (const id of [
    "summaryAverage",
    "summaryMinimum",
    "summaryMedian",
    "summaryMaximum",
    "summaryP90",
    "summaryLatencyP95",
  ]) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(javascript, /function renderLatencySummary/);
});
