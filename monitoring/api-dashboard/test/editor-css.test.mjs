import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const css = await readFile(new URL("../public/testzone.css", import.meta.url), "utf8");
const html = await readFile(new URL("../public/testzone.html", import.meta.url), "utf8");
const javascript = await readFile(new URL("../public/testzone.js", import.meta.url), "utf8");

test("TestZone uses a white monitoring workspace", () => {
  assert.match(css, /--tz-bg:\s*#ffffff;/);
  assert.match(css, /\.testzone-workspace\s*\{[\s\S]+?background:\s*#ffffff;/);
  assert.match(css, /\.workspace-section\s*\{[\s\S]+?background:\s*var\(--tz-surface\)/);
});

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

test("run detail charts use vendored uPlot with cursor tooltips", () => {
  const chartCode = javascript.match(/function runChartData\(\)[\s\S]+?function formatRunLoadPlan/)?.[0] ?? "";
  assert.match(html, /vendor\/uplot\/uPlot\.iife\.min\.js\?v=1\.6\.32/);
  assert.match(html, /vendor\/uplot\/uPlot\.min\.css\?v=1\.6\.32/);
  assert.match(css, /\.run-chart-tooltip/);
  assert.match(css, /\.run-chart-tooltip-row\.is-success/);
  assert.match(css, /--tooltip-series-color/);
  assert.match(css, /\.run-chart-tooltip-swatch[\s\S]+background:\s*var\(--tooltip-series-color\)/);
  assert.match(chartCode, /new window\.uPlot/);
  assert.match(chartCode, /function runChartTooltipPlugin/);
  assert.match(chartCode, /tooltipSeriesClasses/);
  assert.doesNotMatch(chartCode, /swatch\.style\.background/);
  assert.match(chartCode, /function nearestTimestampIndex/);
  assert.match(chartCode, /plot\.over\.addEventListener\("pointermove",\s*updateFromPointer\)/);
  assert.match(chartCode, /setCursor:/);
  assert.match(chartCode, /legend:\s*\{\s*show:\s*false/);
  assert.match(chartCode, /label:\s*"Average"/);
  assert.match(chartCode, /label:\s*"p90"/);
  assert.match(chartCode, /label:\s*"p95"/);
  assert.match(chartCode, /label:\s*"HTTP success"/);
  assert.match(chartCode, /label:\s*"HTTP errors"/);
  assert.match(html, /id="runDetailOutcomeChart"/);
  assert.match(html, /id="runDetailLatencyChart"/);
  assert.doesNotMatch(html, /id="runDetailCompositeChart"/);
  assert.doesNotMatch(html, /id="runTrafficChart"|id="runLatencyChart"/);
  assert.doesNotMatch(chartCode, /VUs|point\.vus/);
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
  assert.match(detail, /id="runDetailOutcomeChart"/);
  assert.match(detail, /id="runDetailLatencyChart"/);
  assert.doesNotMatch(detail, /id="runDetailCompositeChart"/);
  assert.match(detail, /id="detailTps"/);
  assert.match(detail, /id="detailP95"/);
  assert.match(detail, /id="detailVus"/);
  assert.match(detail, /id="runDetailChartEmpty"/);
  assert.match(css, /\.run-detail-timeline/);
  assert.match(
    javascript,
    /renderRunCharts\("detail",\s*\{[\s\S]+?outcome:\s*elements\.runDetailOutcomeChart,[\s\S]+?latency:\s*elements\.runDetailLatencyChart/,
  );
  assert.doesNotMatch(detail, /runDetailChartTooltip/);
});

test("run details expose a multi-scenario plan and metric filter", () => {
  assert.match(html, /id="runScenarioRows"/);
  assert.match(html, /id="runScenarioFilter"/);
  assert.match(html, /<th>Scenario<\/th>[\s\S]+?<th>Executor<\/th>[\s\S]+?<th>Function<\/th>/);
  assert.match(javascript, /function runScenarios\(run\)/);
  assert.match(javascript, /function renderScenarioPlan\(run\)/);
  assert.match(javascript, /function renderScenarioFilter\(run\)/);
  assert.match(html, /<th>Target RPS<\/th>[\s\S]+?<th>VUsers<\/th>/);
  assert.match(javascript, /Measured RPS/);
  assert.match(javascript, /formatScenarioVUsers\(scenario\)/);
  assert.doesNotMatch(javascript, /iter\/s|pre \//);
  assert.match(javascript, /point\.scenarios\?\.\[state\.selectedRunScenario\]/);
  assert.match(css, /\.run-scenario-table/);
  assert.match(css, /\.run-scenario-filter button\[aria-pressed="true"\]/);
});

test("run history paginates by ten and can rerun immutable script snapshots", () => {
  assert.match(html, /id="runPreviousPageButton"/);
  assert.match(html, /id="runPaginationTotal"/);
  assert.match(html, /id="runPageNumbers"/);
  assert.match(html, /id="runNextPageButton"/);
  assert.match(html, /id="runPageJumpDialog"/);
  assert.match(html, /id="rerunSelectedRunButton"/);
  assert.match(javascript, /&page=\$\{state\.runPage\}/);
  assert.match(javascript, /payload\.pagination\?\.pageSize\s*\?\?\s*10/);
  assert.match(javascript, /paginationItems\(state\.runPage,\s*state\.runTotalPages\)/);
  assert.match(javascript, /openRunPageJump\(item\.start,\s*item\.end\)/);
  assert.match(javascript, /api\(`\/runs\/\$\{run\.id\}\/rerun`,\s*\{\s*method:\s*"POST"\s*\}\)/);
  assert.match(javascript, /actionButton\("Rerun"/);
});

test("active runs auto-refresh their graph and history rows never wrap", () => {
  assert.match(html, /id="runAutoRefreshStatus"/);
  assert.match(javascript, /Live · 2s/);
  assert.match(javascript, /visibilitychange/);
  assert.match(javascript, /state\.loadingRuns/);
  assert.match(css, /\.testzone-table th,[\s\S]*white-space:\s*nowrap/);
  assert.match(css, /\.row-actions\s*\{[\s\S]*flex-wrap:\s*nowrap/);
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

test("PostgreSQL components expose important runtime parameters as first-class settings", () => {
  assert.match(javascript, /configField\("maxConnections",\s*"Max connections"/);
  assert.match(javascript, /configField\("sharedBuffersMb",\s*"Shared buffers \(MB\)"/);
  assert.match(javascript, /configField\("workMemMb",\s*"Work memory per operation \(MB\)"/);
  assert.match(javascript, /configField\("maintenanceWorkMemMb",\s*"Maintenance work memory \(MB\)"/);
  assert.match(javascript, /configField\("effectiveCacheSizeMb",\s*"Effective cache size \(MB\)"/);
  assert.match(javascript, /configField\("statementTimeoutMs",\s*"Statement timeout \(ms, 0 = disabled\)"/);
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
  assert.match(javascript, /const commandKey = event\.metaKey \|\| event\.ctrlKey/);
  assert.match(javascript, /commandKey && key === "s"/);
  assert.match(javascript, /if \(!elements\.saveScriptButton\.disabled\) void saveScript\(\)/);
  assert.match(javascript, /elements\.scriptNameInput\.addEventListener\("input",[\s\S]+?renderEditorFileName\(\)/);
  assert.match(javascript, /detail\.textContent = "Unsaved"/);
  assert.doesNotMatch(javascript, /elements\.scriptEditor\.addEventListener\("keydown",\s*handleEditorKeydown\)/);
});

test("script editor supports undo and redo with control or command shortcuts", () => {
  assert.match(javascript, /function resetEditorHistory\(\)/);
  assert.match(javascript, /function recordEditorHistory\(inputType = ""\)/);
  assert.match(javascript, /function restoreEditorHistory\(direction\)/);
  assert.match(javascript, /commandKey && key === "z"/);
  assert.match(javascript, /restoreEditorHistory\(event\.shiftKey \? 1 : -1\)/);
  assert.match(javascript, /commandKey && key === "y"/);
  assert.match(javascript, /recordEditorHistory\(event\.inputType\)/);
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

test("stale automatic validation cannot overwrite a newer save result", () => {
  assert.match(javascript, /function cancelPendingValidation\(\)/);
  assert.match(javascript, /function validationIsCurrent\(snapshot\)/);
  assert.match(javascript, /async function saveScript\(\)[\s\S]+?cancelPendingValidation\(\)/);
  assert.match(javascript, /requestId:\s*\+\+state\.validationRequestId/);
  assert.match(javascript, /if \(!validationIsCurrent\(snapshot\)\) return false;/);
  assert.match(javascript, /if \(quiet\) return false;[\s\S]+?title:\s*"Validation unavailable"/);
});

test("history is the overview and run details appear only after selecting a row", () => {
  assert.doesNotMatch(html, /id="overviewRunButton"|>New run</i);
  assert.match(html, /data-tab="overview"[^>]+>History</);
  assert.match(html, /<h2>Run history<\/h2>/);
  assert.doesNotMatch(html, /Execution overview|id="runSummary"|latencySummaryRun|recentRunSelect/);
  assert.match(javascript, /state\.selectedRunId\s*=\s*null;/);
  assert.match(javascript, /elements\.runDetail\.hidden\s*=\s*!run/);
  assert.match(javascript, /row\.addEventListener\("click",\s*\(\)\s*=>\s*void selectRun\(run\.id\)\)/);
  assert.doesNotMatch(javascript, /elements\.runDetail\.scrollIntoView/);
  assert.match(javascript, /renderScenarioPlan\(run\)/);
  assert.match(javascript, /renderScenarioSummary\(run\)/);
});
