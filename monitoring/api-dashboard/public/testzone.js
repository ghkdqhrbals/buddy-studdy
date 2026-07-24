import {
  diagnosticMessage,
  editorPosition,
  formatDate,
  formatMilliseconds,
  formatPercent,
  formatRate,
  highlightJavaScript,
  lineNumbersFor,
  runScriptName,
  selectLatestRun,
} from "./testzone-model.js?v=2026072406";

const API_BASE = "/testzone/api";
const ACTIVE_STATUSES = new Set(["queued", "running", "cancelling"]);
const MAX_RUN_CHART_WIDTH = 1200;
const RUN_CHART_VISIBLE_SAMPLES = 120;
const state = {
  status: null,
  projects: [],
  scripts: [],
  runs: [],
  components: [],
  projectId: null,
  scriptId: null,
  creatingScript: false,
  selectedRunId: null,
  runSeries: [],
  dirty: false,
  confirmAction: null,
  pollTimer: null,
  componentPollTimer: null,
  activeTab: "overview",
  lintTimer: null,
  componentId: null,
  runCharts: {
    overview: { traffic: null, latency: null },
    detail: { traffic: null, latency: null },
  },
  runChartRanges: {
    overview: null,
    detail: null,
  },
  runSeriesId: null,
  chartResizeTimer: null,
};

const elementIds = [
  "serviceStatus", "projectSelect",
  "newProjectButton", "deleteProjectButton", "newProjectDialog", "newProjectForm",
  "newProjectName", "createProjectButton",
  "summaryStatus", "summaryRps", "summaryP95", "summaryError",
  "summaryAverage", "summaryMinimum", "summaryMedian", "summaryMaximum",
  "summaryP90", "summaryLatencyP95", "latencySummaryRun",
  "runTrafficChart", "runLatencyChart",
  "recentRunSelect", "timelineGrafanaLink", "timelineTitle", "timelineDescription",
  "timelineEmptyState", "timelineRunMeta", "liveRunStrip", "liveRps", "liveProgress",
  "cancelSelectedRunButton", "viewRunScriptButton",
  "runRows", "runEmptyState", "runCount", "refreshRunsButton",
  "runDetail", "runDetailTitle", "runDetailMeta", "runDetailTarget", "runDetailConfig",
  "runDetailScriptButton", "runDetailStatus", "runDetailTrafficChart", "runDetailLatencyChart",
  "detailAverage", "detailMinimum", "detailMedian", "detailMaximum", "detailP90", "detailP95",
  "runDetailChartEmpty", "runLogTail", "closeRunDetailButton",
  "scriptList", "newScriptButton", "scriptNameInput", "scriptEditor", "scriptHighlight",
  "editorLineNumbers", "editorPosition", "editorDirtyMark", "editorFeedback",
  "editorProblemPanel", "editorProblemTitle", "editorProblemMessage", "lintPanel",
  "toggleFilesButton", "focusEditorButton", "validateScriptButton",
  "saveScriptButton", "editorRunButton", "deleteScriptButton",
  "componentGrid", "refreshComponentsButton",
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
  const options = state.projects.map((entry) => {
    const option = document.createElement("option");
    option.value = entry.id;
    option.textContent = entry.name;
    option.selected = entry.id === state.projectId;
    return option;
  });
  if (!options.length) {
    const option = document.createElement("option");
    option.textContent = "No projects";
    option.disabled = true;
    option.selected = true;
    options.push(option);
  }
  elements.projectSelect.replaceChildren(...options);
  const selected = project();
  elements.projectSelect.disabled = !selected;
  elements.deleteProjectButton.disabled = !selected;
  elements.newScriptButton.disabled = !selected;
}

function openNewProjectDialog() {
  elements.newProjectForm.reset();
  elements.newProjectDialog.showModal();
  elements.newProjectName.focus();
}

async function createProject(event) {
  event.preventDefault();
  setButtonBusy(elements.createProjectButton, true, "Creating");
  try {
    const created = (await api("/projects", {
      method: "POST",
      body: JSON.stringify({
        name: elements.newProjectName.value,
      }),
    })).project;
    state.projects.unshift(created);
    state.projectId = created.id;
    state.scriptId = null;
    state.selectedRunId = null;
    localStorage.setItem("testzone.projectId", created.id);
    elements.newProjectDialog.close();
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
    switchTab("scripts");
    toast(`${created.name} project created.`);
  } catch (error) {
    toast(error.message, "error");
  } finally {
    setButtonBusy(elements.createProjectButton, false);
  }
}

async function deleteProject() {
  const selected = project();
  if (!selected) return;
  const scriptCount = state.scripts.length;
  const runCount = state.runs.length;
  const message = `${scriptCount} scripts and ${runCount} runs, including their stored time-series, will be deleted. Active runs must be cancelled first.`;
  if (!await confirmAction(`Delete ${selected.name}?`, message, "Delete project")) return;
  setButtonBusy(elements.deleteProjectButton, true, "Deleting");
  try {
    await api(`/projects/${selected.id}`, { method: "DELETE" });
    state.projects = state.projects.filter((entry) => entry.id !== selected.id);
    state.projectId = state.projects[0]?.id ?? null;
    state.scriptId = null;
    state.selectedRunId = null;
    if (state.projectId) localStorage.setItem("testzone.projectId", state.projectId);
    else localStorage.removeItem("testzone.projectId");
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
    toast(`${selected.name} project deleted.`);
  } catch (error) {
    toast(error.message, "error");
  } finally {
    setButtonBusy(elements.deleteProjectButton, false);
    renderProjects();
  }
}

async function loadScripts() {
  if (!state.projectId) {
    state.scripts = [];
    state.scriptId = null;
    state.creatingScript = false;
    renderScripts();
    return;
  }
  state.scripts = (await api(`/scripts?projectId=${encodeURIComponent(state.projectId)}`)).scripts;
  if (!state.scripts.some((entry) => entry.id === state.scriptId)) {
    state.scriptId = state.scripts[0]?.id ?? null;
  }
  state.creatingScript = false;
  renderScripts();
}

function renderScripts() {
  const items = state.scripts.map((entry) => {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.scriptId = entry.id;
    button.setAttribute("aria-current", String(!state.creatingScript && entry.id === state.scriptId));
    const name = document.createElement("strong");
    name.textContent = entry.name;
    const detail = document.createElement("span");
    detail.textContent = formatDate(entry.updatedAt);
    button.append(name, detail);
    return button;
  });
  if (state.creatingScript) {
    const draft = document.createElement("button");
    draft.type = "button";
    draft.dataset.scriptDraft = "true";
    draft.setAttribute("aria-current", "true");
    const name = document.createElement("strong");
    name.textContent = elements.scriptNameInput.value || "untitled.js";
    const detail = document.createElement("span");
    detail.textContent = "Unsaved";
    draft.append(name, detail);
    items.unshift(draft);
  } else if (!items.length) {
    const empty = document.createElement("div");
    empty.className = "script-list-empty";
    empty.innerHTML = "<strong>No scripts</strong><span>Use + to create a file.</span>";
    items.push(empty);
  }
  elements.scriptList.replaceChildren(...items);
  loadScriptIntoEditor();
}

function loadScriptIntoEditor() {
  if (state.creatingScript) {
    elements.scriptNameInput.value = "untitled.js";
    elements.scriptEditor.value = "";
    state.dirty = true;
    renderDirtyState();
    syncEditorMetrics();
    clearEditorProblem();
    setFeedback(elements.editorFeedback, "New file");
    updateEditorControls();
    window.setTimeout(() => elements.scriptEditor.focus(), 0);
    return;
  }
  const selected = script();
  elements.scriptNameInput.value = selected?.name || "";
  elements.scriptEditor.value = selected?.code || "";
  state.dirty = false;
  renderDirtyState();
  syncEditorMetrics();
  clearEditorProblem();
  setFeedback(elements.editorFeedback, selected ? "" : "No files yet. Use + to create a script.");
  updateEditorControls();
}

function updateEditorControls() {
  const selected = script();
  const canEdit = Boolean(selected || state.creatingScript);
  elements.scriptNameInput.disabled = !canEdit;
  elements.scriptEditor.disabled = !canEdit;
  elements.saveScriptButton.disabled = !canEdit;
  elements.validateScriptButton.disabled = !selected || state.creatingScript;
  elements.editorRunButton.disabled = !selected || state.creatingScript;
  elements.deleteScriptButton.disabled = !selected || state.creatingScript;
}

function renderDirtyState() {
  elements.editorDirtyMark.dataset.dirty = String(state.dirty);
}

function renderEditorFileName() {
  const button = state.creatingScript
    ? elements.scriptList.querySelector("[data-script-draft]")
    : [...elements.scriptList.querySelectorAll("[data-script-id]")]
      .find((entry) => entry.dataset.scriptId === state.scriptId);
  const name = button?.querySelector("strong");
  const detail = button?.querySelector("span");
  if (name) name.textContent = elements.scriptNameInput.value || "untitled.js";
  if (detail) detail.textContent = "Unsaved";
}

function markDirty() {
  if (!script() && !state.creatingScript) return;
  state.dirty = true;
  renderDirtyState();
  setFeedback(elements.editorFeedback, "Unsaved");
  window.clearTimeout(state.lintTimer);
  if (!state.creatingScript) {
    state.lintTimer = window.setTimeout(() => void validateCurrentScript(true), 700);
  }
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

function clearEditorProblem() {
  elements.editorProblemPanel.hidden = true;
  elements.lintPanel.replaceChildren();
}

function renderDiagnostics(
  diagnostics = [],
  {
    title = "Script needs attention",
    message = "Fix the highlighted problems, then save again.",
    focus = false,
  } = {},
) {
  const values = diagnostics.length ? diagnostics : [{ message }];
  elements.editorProblemTitle.textContent = title;
  elements.editorProblemMessage.textContent = message;
  elements.editorProblemPanel.hidden = false;
  elements.lintPanel.replaceChildren(...values.map((diagnostic) => {
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
  if (focus) {
    window.requestAnimationFrame(() => {
      elements.editorProblemPanel.focus({ preventScroll: true });
      elements.editorProblemPanel.scrollIntoView({ block: "nearest" });
    });
  }
}

async function saveScript() {
  const selected = script();
  if (!selected && !state.creatingScript) return false;
  setButtonBusy(elements.saveScriptButton, true, "Saving");
  try {
    if (state.creatingScript) {
      const created = (await api("/scripts", {
        method: "POST",
        body: JSON.stringify({
          projectId: state.projectId,
          name: elements.scriptNameInput.value,
          description: "",
          code: elements.scriptEditor.value,
        }),
      })).script;
      state.scripts.unshift(created);
      state.scriptId = created.id;
      state.creatingScript = false;
    } else {
      Object.assign(selected, (await api(`/scripts/${selected.id}`, {
        method: "PATCH",
        body: JSON.stringify({ name: elements.scriptNameInput.value, code: elements.scriptEditor.value }),
      })).script);
    }
    state.dirty = false;
    renderDirtyState();
    renderScripts();
    clearEditorProblem();
    setFeedback(elements.editorFeedback, "Saved", "success");
    return true;
  } catch (error) {
    renderDiagnostics(error.details || [], {
      title: "Save failed",
      message: "Changes were not saved. Fix the script errors and try again.",
      focus: true,
    });
    setFeedback(elements.editorFeedback, "Save failed", "error");
    return false;
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
      }),
    })).validation;
    clearEditorProblem();
    setFeedback(elements.editorFeedback, `Valid · ${validation.bytes.toLocaleString()} bytes`, "success");
    if (!quiet) toast("Script validation passed.");
    return true;
  } catch (error) {
    renderDiagnostics(error.details || [], {
      title: "Validation failed",
      message: "The script cannot run until these problems are fixed.",
      focus: !quiet,
    });
    setFeedback(elements.editorFeedback, "Validation failed", "error");
    if (!quiet) toast((error.details || []).map(diagnosticMessage).join(" ") || error.message, "error");
    return false;
  } finally {
    if (!quiet) setButtonBusy(elements.validateScriptButton, false);
  }
}

function beginNewScript() {
  if (!state.projectId) {
    toast("Create a project before adding a script.", "error");
    return;
  }
  if (state.dirty && !window.confirm("Discard unsaved editor changes?")) return;
  window.clearTimeout(state.lintTimer);
  state.scriptId = null;
  state.creatingScript = true;
  renderScripts();
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
  if (!state.projectId) {
    state.runs = [];
    state.selectedRunId = null;
    state.runSeries = [];
    renderRuns();
    renderSelectedRun();
    return;
  }
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
  const map = {
    requestRate: "requestRate",
    averageMs: "averageMs",
    minimumMs: "minimumMs",
    medianMs: "medianMs",
    maximumMs: "maximumMs",
    p90Ms: "p90Ms",
    p95Ms: "p95Ms",
    errorRate: "errorRate",
  };
  return run.live?.[map[key]];
}

function renderLatencySummary(run, targets) {
  for (const [key, element] of Object.entries(targets)) {
    element.textContent = formatMilliseconds(run ? runMetric(run, key) : null);
  }
}

function renderRuns() {
  const latest = selectLatestRun(state.runs);
  const summaryRun = selectedRun() || latest;
  elements.summaryStatus.textContent = summaryRun?.status || "No runs";
  elements.summaryRps.textContent = formatRate(summaryRun ? runMetric(summaryRun, "requestRate") : null);
  elements.summaryP95.textContent = formatMilliseconds(summaryRun ? runMetric(summaryRun, "p95Ms") : null);
  elements.summaryError.textContent = formatPercent(summaryRun ? runMetric(summaryRun, "errorRate") : null);
  elements.latencySummaryRun.textContent = summaryRun
    ? `${summaryRun.name || summaryRun.scriptName} · ${formatDate(summaryRun.startedAt || summaryRun.createdAt)}`
    : "Select a run to inspect its completed-request latency.";
  renderLatencySummary(summaryRun, {
    averageMs: elements.summaryAverage,
    minimumMs: elements.summaryMinimum,
    medianMs: elements.summaryMedian,
    maximumMs: elements.summaryMaximum,
    p90Ms: elements.summaryP90,
    p95Ms: elements.summaryLatencyP95,
  });
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
    const loadPlan = document.createElement("td");
    loadPlan.textContent = formatRunLoadPlan(run.options);
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
    row.append(started, name, scriptCell, loadPlan, statusCell, ...metrics, actions);
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
  if (!run) {
    state.runSeries = [];
    state.runSeriesId = null;
    state.runChartRanges.overview = null;
    state.runChartRanges.detail = null;
    renderSelectedRun();
    return;
  }
  if (state.runSeriesId !== run.id) {
    state.runChartRanges.overview = null;
    state.runChartRanges.detail = null;
  }
  try {
    state.runSeries = (await api(`/runs/${run.id}/series`)).series;
  } catch {
    state.runSeries = [];
  }
  state.runSeriesId = run.id;
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
  elements.timelineRunMeta.textContent = `${formatRunLoadPlan(run.options)} · ${formatDate(run.startedAt || run.createdAt)} · ${run.status}`;
  elements.liveRps.textContent = formatRate(run.live?.requestRate);
  elements.liveProgress.textContent = run.live
    ? `${Math.round((run.live.progress || 0) * 100)}% · ${run.live.vus || 0} VUs · p95 ${formatMilliseconds(run.live.p95Ms)}`
    : "Preparing k6 metrics";
  elements.runDetailTitle.textContent = run.name || run.scriptName;
  elements.runDetailMeta.textContent = `${formatDate(run.startedAt || run.createdAt)} · ${run.id}`;
  elements.runDetailTarget.textContent = run.targetUrl || "-";
  elements.runDetailConfig.textContent = formatRunLoadPlan(run.options);
  elements.runDetailScriptButton.textContent = run.scriptName || "Run script";
  elements.runDetailStatus.textContent = run.error ? `${run.status}: ${run.error}` : run.status;
  renderLatencySummary(run, {
    averageMs: elements.detailAverage,
    minimumMs: elements.detailMinimum,
    medianMs: elements.detailMedian,
    maximumMs: elements.detailMaximum,
    p90Ms: elements.detailP90,
    p95Ms: elements.detailP95,
  });
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

function positiveScaleRange(_plot, minimum, maximum) {
  const upper = Math.max(Number(maximum) || 0, 1);
  return [Math.min(0, Number(minimum) || 0), upper * 1.08];
}

function errorScaleRange(_plot, _minimum, maximum) {
  return [0, Math.max(1, (Number(maximum) || 0) * 1.08)];
}

function chartTime(timestampSeconds) {
  if (timestampSeconds === null || timestampSeconds === undefined) return "-";
  return new Date(timestampSeconds * 1000).toLocaleTimeString("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

function runChartData() {
  const points = [...state.runSeries].sort(
    (left, right) => new Date(left.timestamp).getTime() - new Date(right.timestamp).getTime(),
  );
  const metric = (point, key, fallback = null) => {
    const value = point[key] ?? (fallback ? point[fallback] : null);
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  };
  const timestamps = points.map((point) => new Date(point.timestamp).getTime() / 1000);
  return {
    timestamps,
    traffic: [
      timestamps,
      points.map((point) => metric(point, "requestRate") ?? 0),
      points.map((point) => (metric(point, "errorRate") ?? 0) * 100),
    ],
    latency: [
      timestamps,
      points.map((point) => metric(point, "medianMs", "p50Ms")),
      points.map((point) => metric(point, "p90Ms")),
      points.map((point) => metric(point, "p95Ms")),
    ],
  };
}

function runChartRange(key, timestamps) {
  if (timestamps.length < 2) return null;
  const dataMinimum = timestamps[0];
  const dataMaximum = timestamps.at(-1);
  const stored = state.runChartRanges[key];
  const defaultMinimum = timestamps[Math.max(0, timestamps.length - RUN_CHART_VISIBLE_SAMPLES)];
  const requestedMinimum = stored?.min ?? defaultMinimum;
  const requestedMaximum = stored?.max ?? dataMaximum;
  const span = Math.min(
    Math.max(requestedMaximum - requestedMinimum, 1),
    dataMaximum - dataMinimum,
  );
  const minimum = Math.max(dataMinimum, Math.min(requestedMinimum, dataMaximum - span));
  return { min: minimum, max: minimum + span };
}

function runChartPanPlugin(host, scope) {
  return {
    hooks: {
      ready: [(plot) => {
        const timestamps = plot.data[0];
        if (timestamps.length <= RUN_CHART_VISIBLE_SAMPLES) return;
        const overlay = plot.over;
        let pointerId = null;
        let pointerStartX = 0;
        let rangeStart = null;
        let moved = false;

        overlay.addEventListener("pointerdown", (event) => {
          if (event.button !== 0) return;
          pointerId = event.pointerId;
          pointerStartX = event.clientX;
          rangeStart = { min: plot.scales.x.min, max: plot.scales.x.max };
          moved = false;
          overlay.setPointerCapture(pointerId);
          host.classList.add("is-panning");
        });

        overlay.addEventListener("pointermove", (event) => {
          if (event.pointerId !== pointerId || !rangeStart) return;
          const delta = pointerStartX - event.clientX;
          if (Math.abs(delta) >= 4) moved = true;
          if (!moved) return;
          event.preventDefault();
          const dataMinimum = timestamps[0];
          const dataMaximum = timestamps.at(-1);
          const span = rangeStart.max - rangeStart.min;
          const shift = (delta / Math.max(1, overlay.clientWidth)) * span;
          const minimum = Math.max(
            dataMinimum,
            Math.min(rangeStart.min + shift, dataMaximum - span),
          );
          const range = { min: minimum, max: minimum + span };
          state.runChartRanges[scope] = range;
          for (const chart of Object.values(state.runCharts[scope])) {
            chart?.setScale("x", range);
          }
        });

        const finishPan = (event) => {
          if (event.pointerId !== pointerId) return;
          if (overlay.hasPointerCapture(pointerId)) overlay.releasePointerCapture(pointerId);
          pointerId = null;
          rangeStart = null;
          host.classList.remove("is-panning");
        };

        overlay.addEventListener("pointerup", finishPan);
        overlay.addEventListener("pointercancel", finishPan);
      }],
    },
  };
}

function baseRunChartOptions(width, host, scope, range) {
  return {
    width,
    height: 240,
    padding: [12, 10, 0, 0],
    plugins: [runChartPanPlugin(host, scope)],
    cursor: {
      show: true,
      drag: { x: false, y: false },
      points: { size: 7, width: 2 },
    },
    legend: {
      show: true,
      live: true,
    },
    scales: {
      x: range
        ? { time: true, range: () => [range.min, range.max] }
        : { time: true },
      rps: { auto: true, range: positiveScaleRange },
      error: { auto: true, range: errorScaleRange },
    },
  };
}

function trafficChartOptions(width, host, scope, range) {
  return {
    ...baseRunChartOptions(width, host, scope, range),
    series: [
      {
        label: "Time",
        value: (_plot, value) => chartTime(value),
      },
      {
        label: "RPS",
        scale: "rps",
        stroke: "#2166d1",
        width: 2.5,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : formatRate(value),
      },
      {
        label: "Error",
        scale: "error",
        stroke: "#c63a3a",
        width: 2,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : `${Number(value).toFixed(2)}%`,
      },
    ],
    axes: [
      {
        scale: "x",
        stroke: "#64748b",
        grid: { stroke: "#e4e9ef", width: 1 },
        ticks: { stroke: "#cbd5e1", width: 1 },
        values: (_plot, values) => values.map(chartTime),
      },
      {
        scale: "rps",
        side: 3,
        label: "RPS",
        stroke: "#2166d1",
        grid: { stroke: "#e4e9ef", width: 1 },
      },
      {
        scale: "error",
        side: 1,
        label: "Error (%)",
        stroke: "#c63a3a",
        grid: { show: false },
      },
    ],
  };
}

function latencyChartOptions(width, host, scope, range) {
  const options = baseRunChartOptions(width, host, scope, range);
  options.scales = {
    x: options.scales.x,
    latency: { auto: true, range: positiveScaleRange },
  };
  return {
    ...options,
    series: [
      {
        label: "Time",
        value: (_plot, value) => chartTime(value),
      },
      {
        label: "Median",
        scale: "latency",
        stroke: "#2166d1",
        width: 2,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : formatMilliseconds(value),
      },
      {
        label: "p90",
        scale: "latency",
        stroke: "#8a5bc7",
        width: 2,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : formatMilliseconds(value),
      },
      {
        label: "p95",
        scale: "latency",
        stroke: "#e4982b",
        width: 2.5,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : formatMilliseconds(value),
      },
    ],
    axes: [
      {
        scale: "x",
        stroke: "#64748b",
        grid: { stroke: "#e4e9ef", width: 1 },
        ticks: { stroke: "#cbd5e1", width: 1 },
        values: (_plot, values) => values.map(chartTime),
      },
      {
        scale: "latency",
        side: 3,
        label: "Latency (ms)",
        stroke: "#b56c06",
        grid: { stroke: "#e4e9ef", width: 1 },
      },
    ],
  };
}

function disposeRunCharts(scope) {
  for (const kind of ["traffic", "latency"]) {
    state.runCharts[scope][kind]?.destroy();
    state.runCharts[scope][kind] = null;
  }
}

function runChartViewportWidth(host) {
  return Math.max(320, Math.floor(host.clientWidth || host.parentElement?.clientWidth || 0));
}

function runChartWidth(host) {
  return Math.min(MAX_RUN_CHART_WIDTH, runChartViewportWidth(host));
}

function renderRunCharts(scope, hosts, emptyState) {
  disposeRunCharts(scope);
  for (const host of Object.values(hosts)) host.classList.remove("is-pannable", "is-panning");
  const hasPoints = state.runSeries.length > 0;
  emptyState.hidden = hasPoints;
  for (const host of Object.values(hosts)) {
    host.hidden = !hasPoints;
    host.replaceChildren();
  }
  if (!hasPoints) return;
  if (!window.uPlot) {
    hosts.traffic.hidden = false;
    hosts.traffic.textContent = "Chart library unavailable.";
    hosts.latency.hidden = true;
    return;
  }
  const data = runChartData();
  const range = runChartRange(scope, data.timestamps);
  state.runChartRanges[scope] = range;
  for (const host of Object.values(hosts)) {
    host.classList.toggle("is-pannable", data.timestamps.length > RUN_CHART_VISIBLE_SAMPLES);
  }
  state.runCharts[scope].traffic = new window.uPlot(
    trafficChartOptions(runChartWidth(hosts.traffic), hosts.traffic, scope, range),
    data.traffic,
    hosts.traffic,
  );
  state.runCharts[scope].latency = new window.uPlot(
    latencyChartOptions(runChartWidth(hosts.latency), hosts.latency, scope, range),
    data.latency,
    hosts.latency,
  );
}

function drawRunChart() {
  renderRunCharts("overview", {
    traffic: elements.runTrafficChart,
    latency: elements.runLatencyChart,
  }, elements.timelineEmptyState);
  renderRunCharts("detail", {
    traffic: elements.runDetailTrafficChart,
    latency: elements.runDetailLatencyChart,
  }, elements.runDetailChartEmpty);
}

function resizeRunCharts() {
  for (const [scope, hosts] of Object.entries({
    overview: {
      traffic: elements.runTrafficChart,
      latency: elements.runLatencyChart,
    },
    detail: {
      traffic: elements.runDetailTrafficChart,
      latency: elements.runDetailLatencyChart,
    },
  })) {
    for (const [kind, host] of Object.entries(hosts)) {
      const chart = state.runCharts[scope][kind];
      if (!chart || host.hidden) continue;
      chart.setSize({ width: runChartWidth(host), height: 240 });
    }
  }
}

function scheduleRunChartResize() {
  window.clearTimeout(state.chartResizeTimer);
  state.chartResizeTimer = window.setTimeout(resizeRunCharts, 100);
}

function formatRunLoadPlan(options = {}) {
  const duration = options.duration || "-";
  const rate = Number(options.targetRps || 0);
  const maxVus = Number(options.maxVus || options.vus || 0);
  return rate > 0
    ? `${duration} · ${rate.toLocaleString()} RPS · ${maxVus.toLocaleString()} max VUs`
    : `${duration} · ${maxVus.toLocaleString()} VUs`;
}

async function startRun(scriptId = state.scriptId, button = elements.editorRunButton) {
  const selectedProject = project();
  const selectedScript = state.scripts.find((entry) => entry.id === scriptId);
  if (!selectedProject || !selectedScript) {
    toast("Select a project script before starting a run.", "error");
    return;
  }
  if (state.dirty && selectedScript.id === state.scriptId) {
    const saved = await saveScript();
    if (!saved) return;
  }
  button.disabled = true;
  button.setAttribute("aria-busy", "true");
  try {
    const payload = await api("/runs", {
      method: "POST",
      body: JSON.stringify({
        projectId: state.projectId,
        scriptId: selectedScript.id,
      }),
    });
    state.selectedRunId = payload.run.id;
    switchTab("overview");
    toast("Performance test started.");
    await loadRuns();
  } catch (error) {
    toast([error.message, ...error.details.map(diagnosticMessage)].join(" "), "error");
  } finally {
    button.disabled = false;
    button.removeAttribute("aria-busy");
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
        componentAction("Restart", component.id, "restart", true),
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
    toast("Parameters saved. Use Restart to recreate the container with these settings. Reset is required to replace PostgreSQL data.");
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
  if (event.key === "Tab" && event.target === elements.scriptEditor) {
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
    if (!elements.saveScriptButton.disabled) void saveScript();
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
    if (state.dirty && !window.confirm("Discard unsaved editor changes?")) {
      elements.projectSelect.value = state.projectId;
      return;
    }
    state.projectId = elements.projectSelect.value;
    state.scriptId = null;
    state.creatingScript = false;
    state.selectedRunId = null;
    localStorage.setItem("testzone.projectId", state.projectId);
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
  });
  elements.newProjectButton.addEventListener("click", openNewProjectDialog);
  elements.newProjectForm.addEventListener("submit", createProject);
  elements.deleteProjectButton.addEventListener("click", deleteProject);
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
    state.creatingScript = false;
    state.scriptId = button.dataset.scriptId;
    renderScripts();
  });
  elements.newScriptButton.addEventListener("click", beginNewScript);
  elements.scriptEditor.addEventListener("input", () => {
    markDirty();
    syncEditorMetrics();
  });
  for (const eventName of ["scroll", "click", "keyup"]) {
    elements.scriptEditor.addEventListener(eventName, syncEditorMetrics);
  }
  document.querySelector(".editor-pane").addEventListener("keydown", handleEditorKeydown);
  elements.scriptNameInput.addEventListener("input", () => {
    markDirty();
    renderEditorFileName();
  });
  elements.saveScriptButton.addEventListener("click", saveScript);
  elements.validateScriptButton.addEventListener("click", () => void validateCurrentScript(false));
  elements.editorRunButton.addEventListener("click", () => void startRun(state.scriptId, elements.editorRunButton));
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
  elements.refreshComponentsButton.addEventListener("click", loadComponents);
  elements.componentConfigForm.addEventListener("submit", saveComponentConfig);
  elements.confirmForm.addEventListener("submit", (event) => {
    event.preventDefault();
    elements.confirmDialog.close();
    state.confirmAction?.(true);
    state.confirmAction = null;
  });
  window.addEventListener("resize", scheduleRunChartResize);
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
