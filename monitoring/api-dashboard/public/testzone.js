import {
  RUN_PROFILES,
  chartSampleIndex,
  diagnosticMessage,
  editorPosition,
  formatDate,
  formatMilliseconds,
  formatPercent,
  formatRate,
  highlightJavaScript,
  lineNumbersFor,
  parseObjectJson,
  runScriptName,
  selectLatestRun,
} from "./testzone-model.js?v=2026072406";

const API_BASE = "/testzone/api";
const ACTIVE_STATUSES = new Set(["queued", "running", "cancelling"]);
const state = {
  status: null,
  projects: [],
  scripts: [],
  runs: [],
  components: [],
  projectId: null,
  scriptId: null,
  selectedRunId: null,
  runSeries: [],
  dirty: false,
  confirmAction: null,
  pollTimer: null,
  componentPollTimer: null,
  activeTab: "overview",
  lintTimer: null,
  componentId: null,
  runChartHoverIndex: null,
};

const elementIds = [
  "serviceStatus", "projectSelect", "projectBaseUrl", "saveProjectButton", "projectFeedback",
  "headerRunButton", "overviewRunButton", "quickScriptSelect", "profileShortcuts",
  "summaryStatus", "summaryRps", "summaryP95", "summaryError", "runHistoryChart",
  "runChartTooltip",
  "recentRunSelect", "timelineGrafanaLink", "timelineTitle", "timelineDescription",
  "timelineEmptyState", "timelineRunMeta", "liveRunStrip", "liveRps", "liveProgress",
  "cancelSelectedRunButton", "viewRunScriptButton",
  "runRows", "runEmptyState", "runCount", "refreshRunsButton",
  "runDetail", "runDetailTitle", "runDetailMeta", "runDetailTarget", "runDetailConfig",
  "runDetailScriptButton", "runDetailStatus", "runLogTail", "closeRunDetailButton",
  "scriptList", "newScriptButton", "scriptNameInput", "scriptEditor", "scriptHighlight",
  "editorLineNumbers", "editorPosition", "editorDirtyMark", "editorFeedback", "lintPanel",
  "toggleFilesButton", "focusEditorButton", "validateScriptButton",
  "saveScriptButton", "editorRunButton", "deleteScriptButton",
  "componentGrid", "refreshComponentsButton",
  "runDialog", "runForm", "runProjectName", "runTargetUrl", "runName", "runScriptSelect",
  "runProfileControl", "runDuration", "runVus", "runMaxVus", "runTargetRps",
  "runHeaders", "runEnvironment", "runFormError", "startRunButton",
  "newScriptDialog", "newScriptForm", "newScriptName", "newScriptDescription",
  "scriptSnapshotDialog", "scriptSnapshotTitle", "scriptSnapshotCode",
  "componentConfigDialog", "componentConfigForm", "componentConfigTitle", "componentConfigFields",
  "componentCredentialsDialog", "componentCredentialsTitle", "componentCredentialsBody",
  "confirmDialog", "confirmForm", "confirmTitle", "confirmMessage", "confirmActionButton",
  "toastRegion",
];
const elements = Object.fromEntries(elementIds.map((id) => [id, document.getElementById(id)]));

async function api(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {}),
    },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(payload.error || `TestZone request failed (${response.status}).`);
    error.details = payload.details || [];
    throw error;
  }
  return payload;
}

function project() {
  return state.projects.find((entry) => entry.id === state.projectId) || null;
}

function script() {
  return state.scripts.find((entry) => entry.id === state.scriptId) || null;
}

function selectedRun() {
  return state.runs.find((entry) => entry.id === state.selectedRunId) || null;
}

function setFeedback(element, message, status = "") {
  element.textContent = message;
  element.dataset.state = status;
}

function toast(message, status = "success") {
  const item = document.createElement("div");
  item.className = "toast";
  item.dataset.state = status;
  item.textContent = message;
  elements.toastRegion.append(item);
  window.setTimeout(() => item.remove(), 3600);
}

function setButtonBusy(button, busy, label) {
  if (!button.dataset.label) button.dataset.label = button.textContent;
  button.disabled = busy;
  button.textContent = busy ? label : button.dataset.label;
}

function switchTab(name) {
  state.activeTab = name;
  window.clearTimeout(state.componentPollTimer);
  document.querySelectorAll(".testzone-tabs button").forEach((button) => {
    button.setAttribute("aria-selected", String(button.dataset.tab === name));
  });
  document.querySelectorAll("[data-panel]").forEach((panel) => {
    panel.hidden = panel.dataset.panel !== name;
  });
  if (name === "components") void loadComponents();
  if (name === "scripts") window.setTimeout(syncEditorMetrics, 0);
}

async function loadStatus() {
  state.status = await api("/status");
  elements.serviceStatus.textContent = "Ready";
  elements.serviceStatus.dataset.state = "ready";
}

async function loadProjects() {
  state.projects = (await api("/projects")).projects;
  const remembered = localStorage.getItem("testzone.projectId");
  state.projectId = state.projects.some((entry) => entry.id === remembered)
    ? remembered
    : state.projects[0]?.id ?? null;
  renderProjects();
  await Promise.all([loadScripts(), loadRuns()]);
}

function renderProjects() {
  elements.projectSelect.replaceChildren(...state.projects.map((entry) => {
    const option = document.createElement("option");
    option.value = entry.id;
    option.textContent = entry.name;
    option.selected = entry.id === state.projectId;
    return option;
  }));
  elements.projectBaseUrl.value = project()?.baseUrl || "";
}

async function saveProject() {
  const selected = project();
  if (!selected) return;
  setButtonBusy(elements.saveProjectButton, true, "Saving");
  try {
    Object.assign(selected, (await api(`/projects/${selected.id}`, {
      method: "PATCH",
      body: JSON.stringify({ baseUrl: elements.projectBaseUrl.value }),
    })).project);
    setFeedback(elements.projectFeedback, "Saved", "success");
  } catch (error) {
    setFeedback(elements.projectFeedback, error.message, "error");
  } finally {
    setButtonBusy(elements.saveProjectButton, false);
  }
}

async function loadScripts() {
  if (!state.projectId) return;
  state.scripts = (await api(`/scripts?projectId=${encodeURIComponent(state.projectId)}`)).scripts;
  if (!state.scripts.some((entry) => entry.id === state.scriptId)) {
    state.scriptId = state.scripts[0]?.id ?? null;
  }
  renderScripts();
}

function renderScripts() {
  elements.scriptList.replaceChildren(...state.scripts.map((entry) => {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.scriptId = entry.id;
    button.setAttribute("aria-current", String(entry.id === state.scriptId));
    const name = document.createElement("strong");
    name.textContent = entry.name;
    const detail = document.createElement("span");
    detail.textContent = formatDate(entry.updatedAt);
    button.append(name, detail);
    return button;
  }));
  const options = state.scripts.map((entry) => {
    const option = document.createElement("option");
    option.value = entry.id;
    option.textContent = entry.name;
    return option;
  });
  elements.quickScriptSelect.replaceChildren(...options.map((entry) => entry.cloneNode(true)));
  elements.runScriptSelect.replaceChildren(...options);
  if (state.scriptId) {
    elements.quickScriptSelect.value = state.scriptId;
    elements.runScriptSelect.value = state.scriptId;
  }
  loadScriptIntoEditor();
}

function loadScriptIntoEditor() {
  const selected = script();
  elements.scriptNameInput.value = selected?.name || "";
  elements.scriptEditor.value = selected?.code || "";
  state.dirty = false;
  renderDirtyState();
  syncEditorMetrics();
  renderDiagnostics([]);
  for (const button of [
    elements.saveScriptButton,
    elements.validateScriptButton,
    elements.editorRunButton,
    elements.deleteScriptButton,
  ]) button.disabled = !selected;
}

function renderDirtyState() {
  elements.editorDirtyMark.dataset.dirty = String(state.dirty);
}

function markDirty() {
  state.dirty = true;
  renderDirtyState();
  setFeedback(elements.editorFeedback, "Unsaved");
  window.clearTimeout(state.lintTimer);
  state.lintTimer = window.setTimeout(() => void validateCurrentScript(true), 700);
}

function syncEditorMetrics() {
  const code = elements.scriptEditor.value;
  elements.editorLineNumbers.textContent = lineNumbersFor(code);
  elements.scriptHighlight.innerHTML = highlightJavaScript(code);
  elements.editorLineNumbers.scrollTop = elements.scriptEditor.scrollTop;
  elements.scriptHighlight.scrollTop = elements.scriptEditor.scrollTop;
  elements.scriptHighlight.scrollLeft = elements.scriptEditor.scrollLeft;
  const position = editorPosition(code, elements.scriptEditor.selectionStart);
  elements.editorPosition.textContent = `Ln ${position.line}, Col ${position.column}`;
}

function renderDiagnostics(diagnostics = []) {
  elements.lintPanel.hidden = diagnostics.length === 0;
  elements.lintPanel.replaceChildren(...diagnostics.map((diagnostic) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = diagnosticMessage(diagnostic);
    button.addEventListener("click", () => {
      const lines = elements.scriptEditor.value.split("\n");
      const line = Math.max(1, Number(diagnostic.line || 1));
      const column = Math.max(1, Number(diagnostic.column || 1));
      const offset = lines.slice(0, line - 1).reduce((sum, value) => sum + value.length + 1, 0) + column - 1;
      elements.scriptEditor.focus();
      elements.scriptEditor.setSelectionRange(offset, offset);
      syncEditorMetrics();
    });
    return button;
  }));
}

async function saveScript() {
  const selected = script();
  if (!selected) return;
  setButtonBusy(elements.saveScriptButton, true, "Saving");
  try {
    Object.assign(selected, (await api(`/scripts/${selected.id}`, {
      method: "PATCH",
      body: JSON.stringify({ name: elements.scriptNameInput.value, code: elements.scriptEditor.value }),
    })).script);
    state.dirty = false;
    renderDirtyState();
    renderScripts();
    setFeedback(elements.editorFeedback, "Saved", "success");
  } catch (error) {
    renderDiagnostics(error.details);
    setFeedback(elements.editorFeedback, diagnosticMessage(error.details?.[0]) || error.message, "error");
  } finally {
    setButtonBusy(elements.saveScriptButton, false);
  }
}

async function validateCurrentScript(quiet = false) {
  const selected = script();
  if (!selected) return false;
  if (!quiet) setButtonBusy(elements.validateScriptButton, true, "Checking");
  try {
    const validation = (await api(`/scripts/${selected.id}/validate`, {
      method: "POST",
      body: JSON.stringify({
        code: elements.scriptEditor.value,
        baseUrl: project()?.baseUrl,
        duration: elements.runDuration.value,
      }),
    })).validation;
    renderDiagnostics([]);
    setFeedback(elements.editorFeedback, `Valid · ${validation.bytes.toLocaleString()} bytes`, "success");
    if (!quiet) toast("Script validation passed.");
    return true;
  } catch (error) {
    renderDiagnostics(error.details);
    setFeedback(elements.editorFeedback, diagnosticMessage(error.details?.[0]) || error.message, "error");
    if (!quiet) toast(error.details.map(diagnosticMessage).join(" ") || error.message, "error");
    return false;
  } finally {
    if (!quiet) setButtonBusy(elements.validateScriptButton, false);
  }
}

function openNewScriptDialog() {
  elements.newScriptName.value = "api-test.js";
  elements.newScriptDescription.value = "";
  elements.newScriptDialog.showModal();
  elements.newScriptName.select();
}

async function createScript(event) {
  event.preventDefault();
  try {
    const payload = await api("/scripts", {
      method: "POST",
      body: JSON.stringify({
        projectId: state.projectId,
        name: elements.newScriptName.value,
        description: elements.newScriptDescription.value,
        code: script()?.code || elements.scriptEditor.value,
      }),
    });
    state.scripts.unshift(payload.script);
    state.scriptId = payload.script.id;
    elements.newScriptDialog.close();
    renderScripts();
    toast("New script created.");
  } catch (error) {
    toast(error.details.map(diagnosticMessage).join(" ") || error.message, "error");
  }
}

async function deleteCurrentScript() {
  const selected = script();
  if (!selected) return;
  if (!await confirmAction("Delete script?", `${selected.name} 파일을 삭제합니다. 실행 스냅샷은 유지됩니다.`, "Delete script")) return;
  try {
    await api(`/scripts/${selected.id}`, { method: "DELETE" });
    state.scripts = state.scripts.filter((entry) => entry.id !== selected.id);
    state.scriptId = state.scripts[0]?.id ?? null;
    renderScripts();
    toast("Script deleted.");
  } catch (error) {
    toast(error.message, "error");
  }
}

async function loadRuns() {
  if (!state.projectId) return;
  state.runs = (await api(`/runs?projectId=${encodeURIComponent(state.projectId)}`)).runs;
  if (!state.runs.some((run) => run.id === state.selectedRunId)) {
    state.selectedRunId = selectLatestRun(state.runs)?.id || null;
  }
  renderRuns();
  await loadSelectedRunSeries();
  scheduleRunPolling();
}

function runMetric(run, key) {
  if (run.summary?.[key] !== null && run.summary?.[key] !== undefined) return run.summary[key];
  const map = { requestRate: "requestRate", p95Ms: "p95Ms", errorRate: "errorRate" };
  return run.live?.[map[key]];
}

function renderRuns() {
  const latest = selectLatestRun(state.runs);
  elements.summaryStatus.textContent = latest?.status || "No runs";
  elements.summaryRps.textContent = formatRate(latest ? runMetric(latest, "requestRate") : null);
  elements.summaryP95.textContent = formatMilliseconds(latest ? runMetric(latest, "p95Ms") : null);
  elements.summaryError.textContent = formatPercent(latest ? runMetric(latest, "errorRate") : null);
  elements.runCount.textContent = `${state.runs.length.toLocaleString()} runs`;
  elements.runEmptyState.hidden = state.runs.length > 0;

  elements.recentRunSelect.replaceChildren(...state.runs.map((run) => {
    const option = document.createElement("option");
    option.value = run.id;
    option.textContent = `${run.name || run.scriptName} · ${formatDate(run.startedAt || run.createdAt)}`;
    option.selected = run.id === state.selectedRunId;
    return option;
  }));

  elements.runRows.replaceChildren(...state.runs.map((run) => {
    const row = document.createElement("tr");
    row.dataset.runId = run.id;
    row.classList.toggle("is-selected", run.id === state.selectedRunId);
    row.addEventListener("click", () => void selectRun(run.id));
    const started = document.createElement("td");
    started.textContent = formatDate(run.startedAt || run.createdAt);
    const name = document.createElement("td");
    const nameButton = document.createElement("button");
    nameButton.type = "button";
    nameButton.className = "table-link";
    nameButton.textContent = run.name || run.scriptName || "Untitled test";
    nameButton.addEventListener("click", (event) => {
      event.stopPropagation();
      void selectRun(run.id);
    });
    name.append(nameButton);
    const scriptCell = document.createElement("td");
    const scriptButton = document.createElement("button");
    scriptButton.type = "button";
    scriptButton.className = "table-link";
    scriptButton.textContent = runScriptName(run, state.scripts);
    scriptButton.addEventListener("click", (event) => {
      event.stopPropagation();
      void openRunScript(run);
    });
    scriptCell.append(scriptButton);
    const profile = document.createElement("td");
    profile.textContent = run.profile;
    const statusCell = document.createElement("td");
    const status = document.createElement("span");
    status.className = "status-pill";
    status.dataset.status = run.status;
    status.textContent = run.status;
    statusCell.append(status);
    const metrics = [
      formatRate(runMetric(run, "requestRate")),
      formatMilliseconds(runMetric(run, "p95Ms")),
      formatPercent(runMetric(run, "errorRate")),
    ].map((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      return cell;
    });
    const actions = document.createElement("td");
    actions.className = "row-actions";
    const grafana = document.createElement("a");
    grafana.className = "row-action";
    grafana.href = run.grafanaUrl;
    grafana.target = "_blank";
    grafana.rel = "noreferrer";
    grafana.textContent = "Grafana";
    grafana.addEventListener("click", (event) => event.stopPropagation());
    actions.append(grafana);
    if (ACTIVE_STATUSES.has(run.status)) {
      actions.append(actionButton("Cancel", () => cancelRun(run.id), true));
    } else {
      actions.append(actionButton("Delete", () => deleteRun(run), true));
    }
    row.append(started, name, scriptCell, profile, statusCell, ...metrics, actions);
    return row;
  }));
  renderSelectedRun();
}

function actionButton(label, handler, danger = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `row-action${danger ? " danger" : ""}`;
  button.textContent = label;
  button.addEventListener("click", (event) => {
    event.stopPropagation();
    void handler();
  });
  return button;
}

async function selectRun(id) {
  state.selectedRunId = id;
  renderRuns();
  await loadSelectedRunSeries();
}

async function loadSelectedRunSeries() {
  const run = selectedRun();
  state.runChartHoverIndex = null;
  elements.runChartTooltip.hidden = true;
  if (!run) {
    state.runSeries = [];
    renderSelectedRun();
    return;
  }
  try {
    state.runSeries = (await api(`/runs/${run.id}/series`)).series;
  } catch {
    state.runSeries = [];
  }
  renderSelectedRun();
}

function renderSelectedRun() {
  const run = selectedRun();
  elements.runDetail.hidden = !run;
  elements.liveRunStrip.hidden = !run || !ACTIVE_STATUSES.has(run.status);
  elements.cancelSelectedRunButton.disabled = !run || !ACTIVE_STATUSES.has(run.status);
  elements.viewRunScriptButton.disabled = !run;
  if (!run) {
    elements.timelineTitle.textContent = "Run time-series";
    elements.timelineRunMeta.textContent = "Select a run from history.";
    elements.timelineGrafanaLink.href = "https://grafana.lowfidev.cloud/";
    drawRunChart();
    return;
  }
  elements.timelineTitle.textContent = run.name || run.scriptName;
  elements.timelineGrafanaLink.href = run.grafanaUrl;
  elements.timelineRunMeta.textContent = `${run.profile} · ${formatDate(run.startedAt || run.createdAt)} · ${run.status}`;
  elements.liveRps.textContent = formatRate(run.live?.requestRate);
  elements.liveProgress.textContent = run.live
    ? `${Math.round((run.live.progress || 0) * 100)}% · ${run.live.vus || 0} VUs · p95 ${formatMilliseconds(run.live.p95Ms)}`
    : "Preparing k6 metrics";
  elements.runDetailTitle.textContent = run.name || run.scriptName;
  elements.runDetailMeta.textContent = `${formatDate(run.startedAt || run.createdAt)} · ${run.id}`;
  elements.runDetailTarget.textContent = project()?.baseUrl || "-";
  elements.runDetailConfig.textContent = `${run.options?.duration || "-"} · ${run.options?.targetRps || 0} RPS · ${run.options?.maxVus || 0} max VUs`;
  elements.runDetailScriptButton.textContent = run.scriptName || "Run script";
  elements.runDetailStatus.textContent = run.error ? `${run.status}: ${run.error}` : run.status;
  elements.runLogTail.textContent = (run.logTail || []).join("\n") || "No k6 log output.";
  drawRunChart();
}

function scheduleRunPolling() {
  window.clearTimeout(state.pollTimer);
  if (state.runs.some((run) => ACTIVE_STATUSES.has(run.status))) {
    state.pollTimer = window.setTimeout(() => void loadRuns(), 1500);
  }
}

async function cancelRun(id) {
  try {
    await api(`/runs/${id}/cancel`, { method: "POST" });
    toast("Run cancellation requested.");
    await loadRuns();
  } catch (error) {
    toast(error.message, "error");
  }
}

async function deleteRun(run) {
  if (!await confirmAction("Delete run?", `${run.name || run.scriptName} 실행과 InfluxDB 시계열을 삭제합니다.`, "Delete run")) return;
  try {
    await api(`/runs/${run.id}`, { method: "DELETE" });
    state.runs = state.runs.filter((entry) => entry.id !== run.id);
    state.selectedRunId = selectLatestRun(state.runs)?.id || null;
    renderRuns();
    await loadSelectedRunSeries();
    toast("Run and time-series data deleted.");
  } catch (error) {
    toast(error.message, "error");
  }
}

async function openRunScript(run) {
  try {
    const payload = await api(`/runs/${run.id}/script`);
    elements.scriptSnapshotTitle.textContent = `${run.name || run.scriptName} · ${payload.script.name || "Script snapshot"}`;
    elements.scriptSnapshotCode.innerHTML = highlightJavaScript(payload.script.code);
    elements.scriptSnapshotDialog.showModal();
  } catch (error) {
    toast(error.message, "error");
  }
}

function drawRunChart() {
  const canvas = elements.runHistoryChart;
  const points = state.runSeries;
  elements.timelineEmptyState.hidden = points.length > 0;
  canvas.hidden = points.length === 0;
  if (!points.length) {
    state.runChartHoverIndex = null;
    elements.runChartTooltip.hidden = true;
    return;
  }
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(320, Math.round(rect.width));
  const height = 260;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const context = canvas.getContext("2d");
  context.scale(ratio, ratio);
  context.clearRect(0, 0, width, height);
  const padding = { top: 18, right: 44, bottom: 30, left: 48 };
  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;
  const maxRps = Math.max(...points.map((point) => Number(point.requestRate) || 0), 1) * 1.12;
  const maxP95 = Math.max(...points.map((point) => Number(point.p95Ms) || 0), 1) * 1.12;
  const maxVus = Math.max(...points.map((point) => Number(point.vus) || 0), 1) * 1.12;
  context.strokeStyle = "#e4e9ef";
  context.lineWidth = 1;
  for (let index = 0; index <= 4; index += 1) {
    const y = padding.top + (chartHeight * index) / 4;
    context.beginPath();
    context.moveTo(padding.left, y);
    context.lineTo(width - padding.right, y);
    context.stroke();
  }
  const xAt = (index) => padding.left + (chartWidth * index) / Math.max(1, points.length - 1);
  const drawLine = (selector, maximum, color, widthValue = 2) => {
    context.beginPath();
    points.forEach((point, index) => {
      const x = xAt(index);
      const y = padding.top + chartHeight - ((Number(selector(point)) || 0) / maximum) * chartHeight;
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.strokeStyle = color;
    context.lineWidth = widthValue;
    context.stroke();
  };
  drawLine((point) => point.requestRate, maxRps, "#2166d1", 2.5);
  drawLine((point) => point.p95Ms, maxP95, "#e4982b");
  drawLine((point) => point.errorRate, 1, "#c63a3a");
  drawLine((point) => point.vus, maxVus, "#16825d", 1.5);
  context.fillStyle = "#64748b";
  context.font = "10px system-ui";
  context.textAlign = "left";
  context.fillText(`${Math.round(maxRps)} RPS`, 2, padding.top + 4);
  context.textAlign = "right";
  context.fillText(formatMilliseconds(maxP95), width - 2, padding.top + 4);
  const first = new Date(points[0].timestamp);
  const last = new Date(points.at(-1).timestamp);
  context.textAlign = "left";
  context.fillText(first.toLocaleTimeString("ko-KR"), padding.left, height - 8);
  context.textAlign = "right";
  context.fillText(last.toLocaleTimeString("ko-KR"), width - padding.right, height - 8);

  if (state.runChartHoverIndex === null || state.runChartHoverIndex >= points.length) {
    elements.runChartTooltip.hidden = true;
    return;
  }
  const hoverIndex = state.runChartHoverIndex;
  const hoverPoint = points[hoverIndex];
  const hoverX = xAt(hoverIndex);
  context.save();
  context.strokeStyle = "#64748b";
  context.lineWidth = 1;
  context.setLineDash([3, 3]);
  context.beginPath();
  context.moveTo(hoverX, padding.top);
  context.lineTo(hoverX, padding.top + chartHeight);
  context.stroke();
  context.setLineDash([]);
  for (const series of [
    { value: hoverPoint.requestRate, maximum: maxRps, color: "#2166d1" },
    { value: hoverPoint.p95Ms, maximum: maxP95, color: "#e4982b" },
    { value: hoverPoint.errorRate, maximum: 1, color: "#c63a3a" },
    { value: hoverPoint.vus, maximum: maxVus, color: "#16825d" },
  ]) {
    const y = padding.top + chartHeight - ((Number(series.value) || 0) / series.maximum) * chartHeight;
    context.fillStyle = "#fff";
    context.strokeStyle = series.color;
    context.lineWidth = 2;
    context.beginPath();
    context.arc(hoverX, y, 4, 0, Math.PI * 2);
    context.fill();
    context.stroke();
  }
  context.restore();
  renderRunChartTooltip(hoverPoint, hoverX, width);
}

function renderRunChartTooltip(point, x, chartWidth) {
  const title = document.createElement("strong");
  title.textContent = formatDate(point.timestamp);
  const metrics = document.createElement("dl");
  for (const [label, value] of [
    ["RPS", formatRate(point.requestRate)],
    ["p95", formatMilliseconds(point.p95Ms)],
    ["Error", formatPercent(point.errorRate)],
    ["VUs", String(Number(point.vus) || 0)],
  ]) {
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    description.textContent = value;
    metrics.append(term, description);
  }
  elements.runChartTooltip.replaceChildren(title, metrics);
  elements.runChartTooltip.hidden = false;
  const halfWidth = elements.runChartTooltip.offsetWidth / 2;
  const safeX = Math.min(Math.max(x, halfWidth + 8), chartWidth - halfWidth - 8);
  elements.runChartTooltip.style.left = `${safeX}px`;
}

function setRunChartHoverFromClientX(clientX) {
  const canvas = elements.runHistoryChart;
  const rect = canvas.getBoundingClientRect();
  const index = chartSampleIndex(clientX - rect.left, state.runSeries.length, 48, rect.width - 44);
  if (index === state.runChartHoverIndex) return;
  state.runChartHoverIndex = index;
  drawRunChart();
}

function clearRunChartHover() {
  if (state.runChartHoverIndex === null) return;
  state.runChartHoverIndex = null;
  elements.runChartTooltip.hidden = true;
  drawRunChart();
}

function handleRunChartKeydown(event) {
  if (!state.runSeries.length) return;
  const current = state.runChartHoverIndex ?? state.runSeries.length - 1;
  let next = current;
  if (event.key === "ArrowLeft") next = Math.max(0, current - 1);
  else if (event.key === "ArrowRight") next = Math.min(state.runSeries.length - 1, current + 1);
  else if (event.key === "Home") next = 0;
  else if (event.key === "End") next = state.runSeries.length - 1;
  else if (event.key === "Escape") {
    clearRunChartHover();
    return;
  } else return;
  event.preventDefault();
  state.runChartHoverIndex = next;
  drawRunChart();
}

function applyProfile(name) {
  const profile = RUN_PROFILES[name] || RUN_PROFILES.custom;
  elements.runProfileControl.querySelectorAll("button").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.profile === name));
  });
  elements.runDuration.value = profile.duration;
  elements.runVus.value = profile.vus;
  elements.runMaxVus.value = profile.maxVus;
  elements.runTargetRps.value = profile.targetRps;
  elements.runForm.dataset.profile = name;
  const selectedScript = state.scripts.find((entry) => entry.id === elements.runScriptSelect.value);
  elements.runName.value = `${selectedScript?.name?.replace(/\.js$/, "") || "API test"} · ${profile.label}`;
}

function openRunDialog(profileName = "standard", scriptId = null) {
  const selectedProject = project();
  if (!selectedProject || !state.scripts.length) {
    toast("Create a project script before starting a run.", "error");
    return;
  }
  elements.runProjectName.textContent = selectedProject.name;
  elements.runTargetUrl.textContent = selectedProject.baseUrl;
  elements.runScriptSelect.value = scriptId || state.scriptId || state.scripts[0].id;
  applyProfile(profileName);
  elements.runFormError.textContent = "";
  elements.runDialog.showModal();
}

async function startRun(event) {
  event.preventDefault();
  setButtonBusy(elements.startRunButton, true, "Starting");
  elements.runFormError.textContent = "";
  try {
    const payload = await api("/runs", {
      method: "POST",
      body: JSON.stringify({
        name: elements.runName.value,
        projectId: state.projectId,
        scriptId: elements.runScriptSelect.value,
        profile: elements.runForm.dataset.profile || "custom",
        options: {
          duration: elements.runDuration.value,
          vus: Number(elements.runVus.value),
          maxVus: Number(elements.runMaxVus.value),
          targetRps: Number(elements.runTargetRps.value),
        },
        headers: parseObjectJson(elements.runHeaders.value, "Headers"),
        environment: parseObjectJson(elements.runEnvironment.value, "Environment"),
      }),
    });
    state.selectedRunId = payload.run.id;
    elements.runDialog.close();
    switchTab("overview");
    toast("Performance test started.");
    await loadRuns();
  } catch (error) {
    elements.runFormError.textContent = [error.message, ...error.details.map(diagnosticMessage)].join(" ");
  } finally {
    setButtonBusy(elements.startRunButton, false);
  }
}

async function loadComponents() {
  elements.componentGrid.setAttribute("aria-busy", "true");
  try {
    state.components = (await api("/components")).components;
    renderComponents();
  } catch (error) {
    toast(error.message, "error");
  } finally {
    elements.componentGrid.removeAttribute("aria-busy");
    scheduleComponentPolling();
  }
}

function scheduleComponentPolling() {
  window.clearTimeout(state.componentPollTimer);
  if (state.activeTab === "components") {
    state.componentPollTimer = window.setTimeout(() => void loadComponents(), 3_000);
  }
}

function renderComponents() {
  elements.componentGrid.replaceChildren(...state.components.map((component) => {
    const card = document.createElement("article");
    card.className = "component-card";
    const header = document.createElement("header");
    const heading = document.createElement("div");
    const title = document.createElement("h3");
    title.textContent = component.name;
    const image = document.createElement("span");
    image.textContent = component.image;
    heading.append(title, image);
    const status = document.createElement("span");
    status.className = "status-pill";
    status.dataset.status = component.status;
    status.textContent = component.status;
    header.append(heading, status);
    const endpoint = document.createElement("code");
    endpoint.textContent = component.endpoint;
    const metrics = document.createElement("dl");
    metrics.className = "component-metrics";
    const values = [
      ["CPU", component.metrics ? `${component.metrics.cpuPercent.toFixed(1)}%` : "-"],
      ["Memory", component.metrics ? `${component.metrics.memoryUsedMb.toFixed(0)} / ${component.metrics.memoryLimitMb.toFixed(0)} MB` : "-"],
      ["Host port", String(component.config.hostPort)],
      ["Resources", `${component.config.cpus} CPU · ${component.config.memoryMb} MB`],
    ];
    for (const [label, value] of values) {
      const term = document.createElement("dt");
      term.textContent = label;
      const detail = document.createElement("dd");
      detail.textContent = value;
      metrics.append(term, detail);
    }
    const actions = document.createElement("div");
    actions.className = "component-actions";
    actions.append(
      componentAction("Configure", component.id, "config"),
      componentAction("Credentials", component.id, "credentials"),
    );
    if (component.status === "not-deployed" || component.status === "exited") {
      actions.append(componentAction("Deploy", component.id, "deploy", true));
    } else {
      actions.append(
        componentAction("Apply", component.id, "deploy", true),
        componentAction("Restart", component.id, "restart"),
        componentAction("Reset", component.id, "reset", false, true),
        componentAction("Return", component.id, "delete", false, true),
      );
    }
    card.append(header, endpoint, metrics, actions);
    return card;
  }));
}

function componentAction(label, id, action, primary = false, danger = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `button ${primary ? "button-primary" : danger ? "button-danger" : "button-secondary"}`;
  button.textContent = label;
  button.addEventListener("click", async () => {
    if (action === "config") return openComponentConfig(id);
    if (action === "credentials") return openComponentCredentials(id);
    if (["delete", "reset"].includes(action)) {
      const message = action === "reset"
        ? "저장된 테스트 데이터를 삭제하고 새 인스턴스로 다시 배포합니다."
        : "컨테이너와 테스트 데이터 볼륨을 반납합니다.";
      if (!await confirmAction(`${label} ${id}?`, message, label)) return;
    }
    setButtonBusy(button, true, `${label}…`);
    try {
      await api(`/components/${id}${action === "delete" ? "" : `/${action}`}`, {
        method: action === "delete" ? "DELETE" : "POST",
      });
      toast(`${id} ${action} completed.`);
      await loadComponents();
    } catch (error) {
      toast(error.message, "error");
    } finally {
      setButtonBusy(button, false);
    }
  });
  return button;
}

function configField(name, label, value, options = null) {
  const wrapper = document.createElement("label");
  wrapper.textContent = label;
  let control;
  if (options) {
    control = document.createElement("select");
    for (const optionValue of options) {
      const option = document.createElement("option");
      option.value = optionValue;
      option.textContent = optionValue;
      option.selected = optionValue === value;
      control.append(option);
    }
  } else {
    control = document.createElement("input");
    control.value = value;
    control.type = typeof value === "number" ? "number" : "text";
    if (name === "cpus") control.step = "0.1";
  }
  control.name = name;
  wrapper.append(control);
  return wrapper;
}

function openComponentConfig(id) {
  const component = state.components.find((entry) => entry.id === id);
  if (!component) return;
  state.componentId = id;
  elements.componentConfigTitle.textContent = `Configure ${component.name}`;
  const config = component.config;
  const fields = [
    configField("imageTag", "Image version", config.imageTag, id === "postgres" ? ["16-alpine", "17-alpine"] : ["7.4-alpine", "8-alpine"]),
    configField("hostPort", "Host port", config.hostPort),
    configField("cpus", "CPU limit", config.cpus),
    configField("memoryMb", "Memory limit (MB)", config.memoryMb),
  ];
  if (id === "postgres") {
    fields.push(
      configField("database", "Database", config.database),
      configField("username", "Username", config.username),
    );
  } else {
    fields.push(
      configField("maxMemoryMb", "Redis max memory (MB)", config.maxMemoryMb),
      configField("evictionPolicy", "Eviction policy", config.evictionPolicy, ["allkeys-lru", "allkeys-lfu", "volatile-lru", "noeviction"]),
    );
  }
  elements.componentConfigFields.replaceChildren(...fields);
  elements.componentConfigDialog.showModal();
}

async function saveComponentConfig(event) {
  event.preventDefault();
  const values = Object.fromEntries(new FormData(elements.componentConfigForm));
  for (const key of ["hostPort", "cpus", "memoryMb", "maxMemoryMb"]) {
    if (values[key] !== undefined) values[key] = Number(values[key]);
  }
  try {
    await api(`/components/${state.componentId}/config`, {
      method: "PUT",
      body: JSON.stringify(values),
    });
    elements.componentConfigDialog.close();
    toast("Parameters saved. Use Apply to recreate the container. Reset is required for PostgreSQL database or username changes.");
    await loadComponents();
  } catch (error) {
    toast(error.message, "error");
  }
}

async function openComponentCredentials(id) {
  try {
    const credentials = (await api(`/components/${id}/credentials`)).credentials;
    state.componentId = id;
    elements.componentCredentialsTitle.textContent = `${id} credentials`;
    elements.componentCredentialsBody.replaceChildren(...Object.entries(credentials).map(([key, value]) => {
      const row = document.createElement("div");
      row.className = "credential-row";
      const label = document.createElement("span");
      label.textContent = key;
      const code = document.createElement("code");
      code.textContent = value;
      const copy = document.createElement("button");
      copy.type = "button";
      copy.className = "row-action";
      copy.textContent = "Copy";
      copy.addEventListener("click", async () => {
        await navigator.clipboard.writeText(String(value));
        toast(`${key} copied.`);
      });
      row.append(label, code, copy);
      return row;
    }));
    elements.componentCredentialsDialog.showModal();
  } catch (error) {
    toast(error.message, "error");
  }
}

function confirmAction(title, message, actionLabel) {
  elements.confirmTitle.textContent = title;
  elements.confirmMessage.textContent = message;
  elements.confirmActionButton.textContent = actionLabel;
  elements.confirmDialog.showModal();
  return new Promise((resolve) => {
    state.confirmAction = resolve;
  });
}

function closeDialog(button) {
  const dialog = button.closest("dialog");
  if (dialog === elements.confirmDialog && state.confirmAction) {
    state.confirmAction(false);
    state.confirmAction = null;
  }
  dialog.close();
}

function handleEditorKeydown(event) {
  if (event.key === "Tab") {
    event.preventDefault();
    elements.scriptEditor.setRangeText(
      "  ",
      elements.scriptEditor.selectionStart,
      elements.scriptEditor.selectionEnd,
      "end",
    );
    markDirty();
    syncEditorMetrics();
  }
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    void saveScript();
  }
}

function togglePane(pane, button, className) {
  pane.classList.toggle("is-hidden");
  button.setAttribute("aria-pressed", String(!pane.classList.contains("is-hidden")));
  document.querySelector(".ide-shell").classList.toggle(className, pane.classList.contains("is-hidden"));
}

function bindEvents() {
  document.querySelectorAll(".testzone-tabs button").forEach((button) => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
  });
  document.querySelectorAll("[data-close-dialog]").forEach((button) => {
    button.addEventListener("click", () => closeDialog(button));
  });
  elements.projectSelect.addEventListener("change", async () => {
    state.projectId = elements.projectSelect.value;
    state.scriptId = null;
    state.selectedRunId = null;
    localStorage.setItem("testzone.projectId", state.projectId);
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
  });
  elements.saveProjectButton.addEventListener("click", saveProject);
  elements.headerRunButton.addEventListener("click", () => openRunDialog());
  elements.overviewRunButton.addEventListener("click", () => openRunDialog());
  elements.profileShortcuts.addEventListener("click", (event) => {
    const button = event.target.closest("[data-profile]");
    if (button) openRunDialog(button.dataset.profile, elements.quickScriptSelect.value);
  });
  elements.refreshRunsButton.addEventListener("click", loadRuns);
  elements.recentRunSelect.addEventListener("change", () => void selectRun(elements.recentRunSelect.value));
  elements.cancelSelectedRunButton.addEventListener("click", () => {
    if (state.selectedRunId) void cancelRun(state.selectedRunId);
  });
  elements.viewRunScriptButton.addEventListener("click", () => {
    if (selectedRun()) void openRunScript(selectedRun());
  });
  elements.runDetailScriptButton.addEventListener("click", () => {
    if (selectedRun()) void openRunScript(selectedRun());
  });
  elements.closeRunDetailButton.addEventListener("click", () => {
    elements.runDetail.hidden = true;
  });
  elements.scriptList.addEventListener("click", (event) => {
    const button = event.target.closest("[data-script-id]");
    if (!button) return;
    if (state.dirty && !window.confirm("Discard unsaved editor changes?")) return;
    state.scriptId = button.dataset.scriptId;
    renderScripts();
  });
  elements.newScriptButton.addEventListener("click", openNewScriptDialog);
  elements.newScriptForm.addEventListener("submit", createScript);
  elements.scriptEditor.addEventListener("input", () => {
    markDirty();
    syncEditorMetrics();
  });
  for (const eventName of ["scroll", "click", "keyup"]) {
    elements.scriptEditor.addEventListener(eventName, syncEditorMetrics);
  }
  elements.scriptEditor.addEventListener("keydown", handleEditorKeydown);
  elements.scriptNameInput.addEventListener("input", markDirty);
  elements.saveScriptButton.addEventListener("click", saveScript);
  elements.validateScriptButton.addEventListener("click", () => void validateCurrentScript(false));
  elements.editorRunButton.addEventListener("click", () => openRunDialog("standard", state.scriptId));
  elements.deleteScriptButton.addEventListener("click", deleteCurrentScript);
  elements.toggleFilesButton.addEventListener("click", () =>
    togglePane(document.querySelector(".file-pane"), elements.toggleFilesButton, "files-hidden"));
  elements.focusEditorButton.addEventListener("click", () => {
    document.querySelector(".ide-shell").classList.toggle("focus-mode");
    elements.focusEditorButton.setAttribute(
      "aria-pressed",
      String(document.querySelector(".ide-shell").classList.contains("focus-mode")),
    );
  });
  elements.runHistoryChart.addEventListener("pointermove", (event) => setRunChartHoverFromClientX(event.clientX));
  elements.runHistoryChart.addEventListener("pointerdown", (event) => setRunChartHoverFromClientX(event.clientX));
  elements.runHistoryChart.addEventListener("pointerleave", clearRunChartHover);
  elements.runHistoryChart.addEventListener("focus", () => {
    if (state.runSeries.length && state.runChartHoverIndex === null) {
      state.runChartHoverIndex = state.runSeries.length - 1;
      drawRunChart();
    }
  });
  elements.runHistoryChart.addEventListener("blur", clearRunChartHover);
  elements.runHistoryChart.addEventListener("keydown", handleRunChartKeydown);
  elements.runProfileControl.addEventListener("click", (event) => {
    const button = event.target.closest("[data-profile]");
    if (button) applyProfile(button.dataset.profile);
  });
  elements.runScriptSelect.addEventListener("change", () => applyProfile(elements.runForm.dataset.profile || "custom"));
  elements.runForm.addEventListener("submit", startRun);
  elements.refreshComponentsButton.addEventListener("click", loadComponents);
  elements.componentConfigForm.addEventListener("submit", saveComponentConfig);
  elements.confirmForm.addEventListener("submit", (event) => {
    event.preventDefault();
    elements.confirmDialog.close();
    state.confirmAction?.(true);
    state.confirmAction = null;
  });
  window.addEventListener("resize", drawRunChart);
}

async function initialize() {
  bindEvents();
  try {
    await loadStatus();
    await loadProjects();
  } catch (error) {
    elements.serviceStatus.textContent = "Service unavailable";
    elements.serviceStatus.dataset.state = "error";
    toast(error.message, "error");
  }
}

void initialize();
