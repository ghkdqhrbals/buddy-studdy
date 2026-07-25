import {
  diagnosticMessage,
  editorPosition,
  formatDate,
  formatMilliseconds,
  formatPercent,
  formatRate,
  highlightJavaScript,
  lineNumbersFor,
  paginationItems,
  runScriptName,
} from "./testzone-model.js?v=2026072509";

const API_BASE = "/testzone/api";
const ACTIVE_STATUSES = new Set(["queued", "running", "cancelling"]);
const MAX_RUN_CHART_WIDTH = 1200;
const RUN_CHART_VISIBLE_SAMPLES = 120;
const SCENARIO_COLORS = ["#2166d1", "#0f8f8b", "#d28a0b", "#8a5bc7", "#c63a3a"];
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
  selectedRunScenario: "all",
  runPage: 1,
  runPageSize: 10,
  runTotal: 0,
  runTotalPages: 1,
  runSeries: [],
  dirty: false,
  confirmAction: null,
  pollTimer: null,
  loadingRuns: false,
  componentPollTimer: null,
  activeTab: "overview",
  lintTimer: null,
  componentId: null,
  runCharts: {
    detail: { outcome: null, latency: null },
  },
  runChartRanges: {
    detail: null,
  },
  runSeriesId: null,
  chartResizeTimer: null,
};

const elementIds = [
  "serviceStatus", "projectSelect",
  "newProjectButton", "deleteProjectButton", "newProjectDialog", "newProjectForm",
  "newProjectName", "createProjectButton",
  "runRows", "runEmptyState", "runCount", "refreshRunsButton", "runAutoRefreshStatus",
  "runPagination", "runPaginationTotal", "runPreviousPageButton", "runPageNumbers",
  "runNextPageButton", "runPageJumpDialog", "runPageJumpForm",
  "runPageJumpTitle", "runPageJumpSelect",
  "runDetail", "runDetailTitle", "runDetailMeta", "runDetailTarget", "runDetailScenarioCount",
  "runDetailDuration", "runDetailScriptButton", "runDetailStatus", "runScenarioRows",
  "runScenarioFilter", "runDetailTimelineDescription", "runDetailOutcomeChart", "runDetailLatencyChart",
  "runOutcomeChartTitle", "runOutcomeChartSubtitle", "runOutcomeChartLegend",
  "runLatencyChartTitle", "runLatencyChartSubtitle", "runLatencyChartLegend",
  "detailTps", "detailP95", "detailErrorRate", "detailVus", "detailDropped",
  "runDetailChartEmpty", "runLogTail", "rerunSelectedRunButton", "closeRunDetailButton",
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
    state.runPage = 1;
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
  const runCount = state.runTotal;
  const message = `${scriptCount} scripts and ${runCount} runs, including their stored time-series, will be deleted. Active runs must be cancelled first.`;
  if (!await confirmAction(`Delete ${selected.name}?`, message, "Delete project")) return;
  setButtonBusy(elements.deleteProjectButton, true, "Deleting");
  try {
    await api(`/projects/${selected.id}`, { method: "DELETE" });
    state.projects = state.projects.filter((entry) => entry.id !== selected.id);
    state.projectId = state.projects[0]?.id ?? null;
    state.scriptId = null;
    state.selectedRunId = null;
    state.runPage = 1;
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
  if (state.loadingRuns) return;
  if (!state.projectId) {
    state.runs = [];
    state.selectedRunId = null;
    state.runPage = 1;
    state.runTotal = 0;
    state.runTotalPages = 1;
    state.runSeries = [];
    renderRuns();
    renderSelectedRun();
    updateRunAutoRefreshStatus(false);
    return;
  }
  state.loadingRuns = true;
  elements.refreshRunsButton.disabled = true;
  try {
    const payload = await api(
      `/runs?projectId=${encodeURIComponent(state.projectId)}&page=${state.runPage}`,
    );
    state.runs = payload.runs;
    state.runPage = payload.pagination?.page ?? 1;
    state.runPageSize = payload.pagination?.pageSize ?? 10;
    state.runTotal = payload.pagination?.total ?? state.runs.length;
    state.runTotalPages = payload.pagination?.totalPages ?? 1;
    if (!state.runs.some((run) => run.id === state.selectedRunId)) {
      state.selectedRunId = null;
    }
    renderRuns();
    await loadSelectedRunSeries();
  } finally {
    state.loadingRuns = false;
    elements.refreshRunsButton.disabled = false;
    scheduleRunPolling();
  }
}

function runMetric(run, key) {
  if (run.summary?.[key] !== null && run.summary?.[key] !== undefined) return run.summary[key];
  if (["successCount", "errorCount"].includes(key) && run.summary?.requests !== null && run.summary?.requests !== undefined) {
    const errors = Math.round(Number(run.summary.requests) * Number(run.summary.errorRate || 0));
    return key === "errorCount" ? errors : Math.max(0, Number(run.summary.requests) - errors);
  }
  if (key === "tps") return run.summary?.requestRate ?? run.live?.tps ?? run.live?.requestRate;
  if (key === "mttMs") return run.summary?.averageMs ?? run.live?.mttMs ?? run.live?.averageMs;
  const map = {
    requestRate: "requestRate",
    averageMs: "averageMs",
    minimumMs: "minimumMs",
    medianMs: "medianMs",
    maximumMs: "maximumMs",
    p90Ms: "p90Ms",
    p95Ms: "p95Ms",
    errorRate: "errorRate",
    tps: "tps",
    mttMs: "mttMs",
    mttfbMs: "mttfbMs",
    successCount: "successCount",
    errorCount: "errorCount",
    maxVus: "vus",
    droppedIterations: "droppedIterations",
  };
  return run.live?.[map[key]];
}

function formatCount(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number).toLocaleString() : "-";
}

function runScenarios(run) {
  if (Array.isArray(run?.options?.scenarios) && run.options.scenarios.length) {
    return run.options.scenarios;
  }
  return [{
    name: "default",
    executor: run?.options?.targetRps > 0 ? "constant-arrival-rate" : "constant-vus",
    exec: "default",
    targetRps: Number(run?.options?.targetRps || 0),
    rate: Number(run?.options?.targetRps || 0),
    timeUnit: "1s",
    duration: run?.options?.duration || "-",
    preAllocatedVUs: Number(run?.options?.vus || 0),
    maxVus: Number(run?.options?.maxVus || run?.options?.vus || 0),
    vus: Number(run?.options?.vus || 0),
  }];
}

function scenarioMetrics(run, scenarioName = state.selectedRunScenario) {
  if (scenarioName === "all") {
    return {
      requestRate: runMetric(run, "requestRate"),
      p95Ms: runMetric(run, "p95Ms"),
      errorRate: runMetric(run, "errorRate"),
      vus: runMetric(run, "maxVus") ?? run.options?.maxVus,
      droppedIterations: runMetric(run, "droppedIterations"),
    };
  }
  const points = state.runSeries
    .map((point) => point.scenarios?.[scenarioName])
    .filter(Boolean);
  if (!points.length) return {};
  const sum = (key) => points.reduce((total, point) => total + (Number(point[key]) || 0), 0);
  const maximum = (key) => Math.max(...points.map((point) => Number(point[key]) || 0));
  const requests = sum("requestRate");
  const errors = sum("errorCount");
  return {
    requestRate: requests / points.length,
    p95Ms: maximum("p95Ms"),
    errorRate: requests > 0 ? errors / requests : 0,
    vus: maximum("vus"),
    droppedIterations: sum("droppedIterations"),
  };
}

function formatScenarioSchedule(scenario) {
  const start = scenario.startTime && scenario.startTime !== "0s"
    ? `${scenario.startTime} start · `
    : "";
  if (Number(scenario.rate) > 0) {
    return `${start}${Number(scenario.rate).toLocaleString()} iter/${scenario.timeUnit || "1s"} · ${scenario.duration || "-"}`;
  }
  return `${start}${scenario.vus || scenario.preAllocatedVUs || 0} VUs · ${scenario.duration || "-"}`;
}

function renderScenarioPlan(run) {
  const scenarios = runScenarios(run);
  elements.runScenarioRows.replaceChildren(...scenarios.map((scenario, index) => {
    const row = document.createElement("tr");
    const name = document.createElement("td");
    const label = document.createElement("span");
    label.className = "run-scenario-name";
    label.dataset.color = String(index % 3);
    label.textContent = scenario.name;
    name.append(label);
    const executor = document.createElement("td");
    executor.textContent = scenario.executor || "-";
    const exec = document.createElement("td");
    exec.textContent = scenario.exec || "default";
    const schedule = document.createElement("td");
    schedule.textContent = formatScenarioSchedule(scenario);
    const rate = document.createElement("td");
    rate.textContent = Number(scenario.targetRps) > 0
      ? `${Number(scenario.targetRps).toLocaleString()} iter/s`
      : `${Number(scenario.vus || 0).toLocaleString()} VUs`;
    const capacity = document.createElement("td");
    capacity.textContent = Number(scenario.rate) > 0
      ? `${Number(scenario.preAllocatedVUs || 0).toLocaleString()} pre / ${Number(scenario.maxVus || 0).toLocaleString()} max`
      : `${Number(scenario.maxVus || scenario.vus || 0).toLocaleString()} max`;
    row.append(name, executor, exec, schedule, rate, capacity);
    return row;
  }));
}

function renderScenarioFilter(run) {
  const scenarios = runScenarios(run);
  const available = new Set(["all", ...scenarios.map((scenario) => scenario.name)]);
  if (!available.has(state.selectedRunScenario)) state.selectedRunScenario = "all";
  const items = [
    { value: "all", label: "All scenarios" },
    ...scenarios.map((scenario) => ({ value: scenario.name, label: scenario.name })),
  ];
  elements.runScenarioFilter.replaceChildren(...items.map((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = item.label;
    button.dataset.scenario = item.value;
    button.setAttribute("aria-pressed", String(item.value === state.selectedRunScenario));
    button.addEventListener("click", () => {
      state.selectedRunScenario = item.value;
      state.runChartRanges.detail = null;
      renderSelectedRun();
    });
    return button;
  }));
}

function renderChartLegend(element, items) {
  element.replaceChildren(...items.map((item) => {
    const label = document.createElement("span");
    const swatch = document.createElement("i");
    swatch.dataset.color = item.color;
    swatch.setAttribute("aria-hidden", "true");
    label.append(swatch, item.label);
    return label;
  }));
}

function renderScenarioSummary(run) {
  const metrics = scenarioMetrics(run);
  elements.detailTps.textContent = formatRate(metrics.requestRate);
  elements.detailP95.textContent = formatMilliseconds(metrics.p95Ms);
  elements.detailErrorRate.textContent = formatPercent(metrics.errorRate);
  elements.detailVus.textContent = formatCount(metrics.vus);
  elements.detailDropped.textContent = formatCount(metrics.droppedIterations);
  const label = state.selectedRunScenario === "all" ? "All scenarios" : state.selectedRunScenario;
  elements.runDetailTimelineDescription.textContent =
    `${label}의 초 단위 처리량과 응답시간을 확인합니다.`;
  if (state.selectedRunScenario === "all") {
    const scenarios = runScenarios(run);
    elements.runOutcomeChartTitle.textContent = "Throughput by scenario";
    elements.runOutcomeChartSubtitle.textContent = "total / scenario request rate";
    elements.runLatencyChartTitle.textContent = "p95 latency by scenario";
    elements.runLatencyChartSubtitle.textContent = "scenario p95";
    renderChartLegend(elements.runOutcomeChartLegend, [
      { label: "Total", color: "total" },
      ...scenarios.map((scenario, index) => ({
        label: scenario.name,
        color: String(index % SCENARIO_COLORS.length),
      })),
    ]);
    renderChartLegend(elements.runLatencyChartLegend, scenarios.map((scenario, index) => ({
      label: scenario.name,
      color: String(index % SCENARIO_COLORS.length),
    })));
    return;
  }
  elements.runOutcomeChartTitle.textContent = "Throughput & outcomes";
  elements.runOutcomeChartSubtitle.textContent = "success / errors per second";
  elements.runLatencyChartTitle.textContent = "Response time";
  elements.runLatencyChartSubtitle.textContent = "average / p90 / p95";
  renderChartLegend(elements.runOutcomeChartLegend, [
    { label: "HTTP success", color: "success" },
    { label: "HTTP errors", color: "error" },
  ]);
  renderChartLegend(elements.runLatencyChartLegend, [
    { label: "Average", color: "0" },
    { label: "p90", color: "3" },
    { label: "p95", color: "2" },
  ]);
}

async function goToRunPage(page) {
  const nextPage = Math.max(1, Math.min(Number(page) || 1, state.runTotalPages));
  if (nextPage === state.runPage) return;
  state.runPage = nextPage;
  state.selectedRunId = null;
  await loadRuns();
  document.querySelector(".runs-section")?.scrollIntoView({ block: "start" });
}

function openRunPageJump(start, end) {
  elements.runPageJumpTitle.textContent = `Choose page ${start}-${end}`;
  elements.runPageJumpSelect.replaceChildren(
    ...Array.from({ length: end - start + 1 }, (_, index) => {
      const option = document.createElement("option");
      option.value = String(start + index);
      option.textContent = `Page ${start + index}`;
      return option;
    }),
  );
  elements.runPageJumpDialog.showModal();
}

function renderRunPageNumbers() {
  const controls = paginationItems(state.runPage, state.runTotalPages).map((item) => {
    const button = document.createElement("button");
    button.type = "button";
    if (item.type === "gap") {
      button.className = "page-gap";
      button.textContent = "…";
      button.title = `Choose page ${item.start}-${item.end}`;
      button.setAttribute("aria-label", `Choose a page between ${item.start} and ${item.end}`);
      button.addEventListener("click", () => openRunPageJump(item.start, item.end));
      return button;
    }
    button.className = "page-number";
    button.textContent = String(item.page);
    button.setAttribute("aria-label", `Page ${item.page}`);
    if (item.page === state.runPage) button.setAttribute("aria-current", "page");
    button.addEventListener("click", () => void goToRunPage(item.page));
    return button;
  });
  elements.runPageNumbers.replaceChildren(...controls);
}

function renderRuns() {
  elements.runCount.textContent = `${state.runTotal.toLocaleString()} total runs`;
  elements.runEmptyState.hidden = state.runs.length > 0;
  elements.runPagination.hidden = state.runTotalPages <= 1;
  elements.runPaginationTotal.textContent = `${state.runTotal.toLocaleString()} total`;
  elements.runPreviousPageButton.disabled = state.runPage <= 1;
  elements.runNextPageButton.disabled = state.runPage >= state.runTotalPages;
  renderRunPageNumbers();

  elements.runRows.replaceChildren(...state.runs.map((run) => {
    const row = document.createElement("tr");
    row.dataset.runId = run.id;
    row.classList.toggle("is-selected", run.id === state.selectedRunId);
    row.addEventListener("click", () => void selectRun(run.id));
    const started = document.createElement("td");
    started.className = "run-started-cell";
    started.textContent = formatDate(run.startedAt || run.createdAt);
    const name = document.createElement("td");
    name.className = "run-name-cell";
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
    scriptCell.className = "run-script-cell";
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
    loadPlan.className = "run-plan-cell";
    loadPlan.textContent = formatRunLoadPlan(run.options);
    loadPlan.title = loadPlan.textContent;
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
      actions.append(actionButton("Rerun", (button) => rerunRun(run, button)));
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
    void handler(button);
  });
  return button;
}

async function selectRun(id) {
  if (state.selectedRunId !== id) state.selectedRunScenario = "all";
  state.selectedRunId = id;
  state.runSeries = [];
  state.runSeriesId = null;
  renderRuns();
  await loadSelectedRunSeries();
  elements.runDetail.scrollIntoView({ block: "start" });
}

async function loadSelectedRunSeries() {
  const run = selectedRun();
  if (!run) {
    state.runSeries = [];
    state.runSeriesId = null;
    state.runChartRanges.detail = null;
    renderSelectedRun();
    return;
  }
  if (state.runSeriesId !== run.id) {
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
  elements.rerunSelectedRunButton.hidden = !run || ACTIVE_STATUSES.has(run.status);
  elements.rerunSelectedRunButton.disabled = !run || ACTIVE_STATUSES.has(run.status);
  if (!run) {
    drawRunChart();
    return;
  }
  elements.runDetailTitle.textContent = run.name || run.scriptName;
  elements.runDetailMeta.textContent = `${formatDate(run.startedAt || run.createdAt)} · ${run.id}`;
  elements.runDetailTarget.textContent = run.targetUrl || "-";
  const scenarios = runScenarios(run);
  elements.runDetailScenarioCount.textContent = scenarios.length.toLocaleString();
  elements.runDetailDuration.textContent = run.options?.duration || "-";
  elements.runDetailScriptButton.textContent = run.scriptName || "Run script";
  elements.runDetailStatus.textContent = run.error ? `${run.status}: ${run.error}` : run.status;
  renderScenarioPlan(run);
  renderScenarioFilter(run);
  renderScenarioSummary(run);
  elements.runLogTail.textContent = (run.logTail || []).join("\n") || "No k6 log output.";
  drawRunChart();
}

function scheduleRunPolling() {
  window.clearTimeout(state.pollTimer);
  const active = state.runs.some((run) => ACTIVE_STATUSES.has(run.status));
  updateRunAutoRefreshStatus(active);
  if (active && !document.hidden) {
    state.pollTimer = window.setTimeout(() => void loadRuns(), 2000);
  }
}

function updateRunAutoRefreshStatus(active) {
  elements.runAutoRefreshStatus.textContent = active ? "Live · 2s" : "Manual";
  elements.runAutoRefreshStatus.classList.toggle("is-live", active);
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
    if (state.selectedRunId === run.id) state.selectedRunId = null;
    await loadRuns();
    toast("Run and time-series data deleted.");
  } catch (error) {
    toast(error.message, "error");
  }
}

async function rerunRun(run, button = elements.rerunSelectedRunButton) {
  if (!run || ACTIVE_STATUSES.has(run.status)) return;
  setButtonBusy(button, true, "Starting");
  try {
    await api(`/runs/${run.id}/rerun`, { method: "POST" });
    state.runPage = 1;
    state.selectedRunId = null;
    switchTab("overview");
    toast(`${run.name || run.scriptName} rerun started.`);
    await loadRuns();
  } catch (error) {
    toast(error.message, "error");
  } finally {
    setButtonBusy(button, false);
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
  const sourcePoints = [...state.runSeries].sort(
    (left, right) => new Date(left.timestamp).getTime() - new Date(right.timestamp).getTime(),
  );
  const metric = (point, key, fallback = null) => {
    const value = point[key] ?? (fallback ? point[fallback] : null);
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  };
  if (state.selectedRunScenario === "all") {
    const scenarioNames = runScenarios(selectedRun()).map((scenario) => scenario.name);
    const timestamps = sourcePoints.map((point) => new Date(point.timestamp).getTime() / 1000);
    return {
      mode: "all",
      scenarioNames,
      timestamps,
      outcome: [
        timestamps,
        sourcePoints.map((point) => metric(point, "requestRate", "tps")),
        ...scenarioNames.map((name) => sourcePoints.map((point) =>
          metric(point.scenarios?.[name] || {}, "requestRate", "tps"))),
      ],
      latency: [
        timestamps,
        ...scenarioNames.map((name) => sourcePoints.map((point) =>
          metric(point.scenarios?.[name] || {}, "p95Ms"))),
      ],
    };
  }
  const points = sourcePoints.map((point) => {
    const scenario = point.scenarios?.[state.selectedRunScenario];
    return scenario ? { ...scenario, timestamp: point.timestamp } : null;
  }).filter(Boolean);
  const timestamps = points.map((point) => new Date(point.timestamp).getTime() / 1000);
  const requestCount = (point) => metric(point, "requestRate", "tps") ?? 0;
  const errorCount = (point) => metric(point, "errorCount")
    ?? requestCount(point) * (metric(point, "errorRate") ?? 0);
  const successCount = (point) => metric(point, "successCount")
    ?? Math.max(0, requestCount(point) - errorCount(point));
  return {
    mode: "scenario",
    scenarioNames: [state.selectedRunScenario],
    timestamps,
    outcome: [
      timestamps,
      points.map(successCount),
      points.map(errorCount),
    ],
    latency: [
      timestamps,
      points.map((point) => metric(point, "averageMs", "mttMs")),
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

function runChartTooltipPlugin() {
  let tooltip = null;

  const tooltipSeriesClasses = new Map([
    ["Total", "is-total"],
    ["HTTP success", "is-success"],
    ["HTTP errors", "is-error"],
    ["Average", "is-average"],
    ["p90", "is-p90"],
    ["p95", "is-p95"],
  ]);

  function hideTooltip() {
    if (tooltip) tooltip.hidden = true;
  }

  function nearestTimestampIndex(timestamps, target) {
    if (!timestamps.length || !Number.isFinite(target)) return null;
    let low = 0;
    let high = timestamps.length - 1;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      if (timestamps[middle] < target) low = middle + 1;
      else high = middle;
    }
    if (low === 0) return 0;
    const previous = low - 1;
    return Math.abs(timestamps[low] - target) < Math.abs(timestamps[previous] - target)
      ? low
      : previous;
  }

  function showTooltip(plot, index, cursorLeft, cursorTop) {
    if (!tooltip || index === null || index === undefined) {
      hideTooltip();
      return;
    }
    const timestamp = plot.data[0][index];
    if (!Number.isFinite(timestamp)) {
      hideTooltip();
      return;
    }

    const time = document.createElement("div");
    time.className = "run-chart-tooltip-time";
    time.textContent = chartTime(timestamp);
    const rows = plot.series.slice(1).map((series, seriesIndex) => {
      const value = plot.data[seriesIndex + 1][index];
      const row = document.createElement("div");
      row.className = "run-chart-tooltip-row";
      const seriesClass = tooltipSeriesClasses.get(series.label);
      row.classList.add(seriesClass || `is-scenario-${seriesIndex % SCENARIO_COLORS.length}`);
      const swatch = document.createElement("span");
      swatch.className = "run-chart-tooltip-swatch";
      swatch.setAttribute("aria-hidden", "true");
      const label = document.createElement("span");
      label.className = "run-chart-tooltip-label";
      label.textContent = series.label;
      const formattedValue = document.createElement("strong");
      formattedValue.className = "run-chart-tooltip-value";
      formattedValue.textContent = typeof series.value === "function"
        ? series.value(plot, value)
        : String(value ?? "-");
      row.append(swatch, label, formattedValue);
      return row;
    });
    tooltip.replaceChildren(time, ...rows);
    tooltip.hidden = false;

    const left = Math.min(
      Math.max((cursorLeft ?? 0) + 12, 8),
      Math.max(8, plot.over.clientWidth - tooltip.offsetWidth - 8),
    );
    const top = Math.min(
      Math.max((cursorTop ?? 0) + 12, 8),
      Math.max(8, plot.over.clientHeight - tooltip.offsetHeight - 8),
    );
    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${top}px`;
  }

  return {
    hooks: {
      ready: [(plot) => {
        tooltip = document.createElement("div");
        tooltip.className = "run-chart-tooltip";
        tooltip.hidden = true;
        plot.over.append(tooltip);
        const updateFromPointer = (event) => {
          const bounds = plot.over.getBoundingClientRect();
          const cursorLeft = event.clientX - bounds.left;
          const cursorTop = event.clientY - bounds.top;
          const timestamp = plot.posToVal(cursorLeft, "x");
          showTooltip(
            plot,
            nearestTimestampIndex(plot.data[0], timestamp),
            cursorLeft,
            cursorTop,
          );
        };
        plot.over.addEventListener("pointermove", updateFromPointer);
        plot.over.addEventListener("mousemove", updateFromPointer);
        plot.over.addEventListener("pointerleave", hideTooltip);
        plot.over.addEventListener("mouseleave", hideTooltip);
      }],
      setCursor: [(plot) => {
        showTooltip(plot, plot.cursor.idx, plot.cursor.left, plot.cursor.top);
      }],
      destroy: [hideTooltip],
    },
  };
}

function baseRunChartOptions(width, host, scope, range) {
  return {
    width,
    height: 240,
    padding: [12, 10, 0, 0],
    plugins: [runChartPanPlugin(host, scope), runChartTooltipPlugin()],
    cursor: {
      show: true,
      drag: { x: false, y: false },
      points: { size: 7, width: 2 },
    },
    legend: {
      show: false,
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

function outcomeChartOptions(width, host, scope, range, data) {
  const series = data.mode === "all"
    ? [
        {
          label: "Total",
          scale: "rps",
          stroke: "#30343a",
          width: 2.7,
          points: { show: false },
          value: (_plot, value) => value === null ? "-" : formatRate(value),
        },
        ...data.scenarioNames.map((name, index) => ({
          label: name,
          scale: "rps",
          stroke: SCENARIO_COLORS[index % SCENARIO_COLORS.length],
          width: 2,
          points: { show: false },
          value: (_plot, value) => value === null ? "-" : formatRate(value),
        })),
      ]
    : [
        {
          label: "HTTP success",
          scale: "rps",
          stroke: "#16835f",
          width: 2.5,
          points: { show: false },
          value: (_plot, value) => value === null ? "-" : `${Math.round(value).toLocaleString()} /s`,
        },
        {
          label: "HTTP errors",
          scale: "rps",
          stroke: "#c63a3a",
          width: 2,
          points: { show: false },
          value: (_plot, value) => value === null ? "-" : `${Math.round(value).toLocaleString()} /s`,
        },
      ];
  return {
    ...baseRunChartOptions(width, host, scope, range),
    series: [
      {
        label: "Time",
        value: (_plot, value) => chartTime(value),
      },
      ...series,
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
        label: "Requests / sec",
        stroke: "#16835f",
        grid: { stroke: "#e4e9ef", width: 1 },
      },
    ],
  };
}

function latencyChartOptions(width, host, scope, range, data) {
  const options = baseRunChartOptions(width, host, scope, range);
  options.scales = {
    x: options.scales.x,
    latency: { auto: true, range: positiveScaleRange },
  };
  const series = data.mode === "all"
    ? data.scenarioNames.map((name, index) => ({
        label: name,
        scale: "latency",
        stroke: SCENARIO_COLORS[index % SCENARIO_COLORS.length],
        width: 2.2,
        points: { show: false },
        value: (_plot, value) => value === null ? "-" : formatMilliseconds(value),
      }))
    : [
        {
          label: "Average",
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
      ];
  return {
    ...options,
    series: [
      {
        label: "Time",
        value: (_plot, value) => chartTime(value),
      },
      ...series,
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
  for (const kind of ["outcome", "latency"]) {
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
  const data = runChartData();
  const hasPoints = data.timestamps.length > 0;
  emptyState.hidden = hasPoints;
  for (const host of Object.values(hosts)) {
    host.hidden = !hasPoints;
    host.replaceChildren();
  }
  if (!hasPoints) return;
  if (!window.uPlot) {
    hosts.outcome.hidden = false;
    hosts.outcome.textContent = "Chart library unavailable.";
    hosts.latency.hidden = true;
    hosts.composite.hidden = true;
    return;
  }
  const range = runChartRange(scope, data.timestamps);
  state.runChartRanges[scope] = range;
  for (const host of Object.values(hosts)) {
    host.classList.toggle("is-pannable", data.timestamps.length > RUN_CHART_VISIBLE_SAMPLES);
  }
  state.runCharts[scope].outcome = new window.uPlot(
    outcomeChartOptions(runChartWidth(hosts.outcome), hosts.outcome, scope, range, data),
    data.outcome,
    hosts.outcome,
  );
  state.runCharts[scope].latency = new window.uPlot(
    latencyChartOptions(runChartWidth(hosts.latency), hosts.latency, scope, range, data),
    data.latency,
    hosts.latency,
  );
}

function drawRunChart() {
  renderRunCharts("detail", {
    outcome: elements.runDetailOutcomeChart,
    latency: elements.runDetailLatencyChart,
  }, elements.runDetailChartEmpty);
}

function resizeRunCharts() {
  for (const [scope, hosts] of Object.entries({
    detail: {
      outcome: elements.runDetailOutcomeChart,
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
  const scenarioCount = Array.isArray(options.scenarios) ? options.scenarios.length : 0;
  if (scenarioCount > 1) {
    return rate > 0
      ? `${scenarioCount} scenarios · ${rate.toLocaleString()} iter/s · ${maxVus.toLocaleString()} max VUs`
      : `${scenarioCount} scenarios · ${duration} · ${maxVus.toLocaleString()} max VUs`;
  }
  return rate > 0
    ? `${duration} · ${rate.toLocaleString()} ${scenarioCount ? "iter/s" : "RPS"} · ${maxVus.toLocaleString()} max VUs`
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
    await api("/runs", {
      method: "POST",
      body: JSON.stringify({
        projectId: state.projectId,
        scriptId: selectedScript.id,
      }),
    });
    state.runPage = 1;
    state.selectedRunId = null;
    switchTab("overview");
    toast("Performance test started.");
    await loadRuns();
  } catch (error) {
    toast([error.message, ...(error.details || []).map(diagnosticMessage)].join(" "), "error");
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
      ...(component.id === "postgres" ? [
        ["Connections", component.metrics?.maxConnections
          ? `${component.metrics.connections} / ${component.metrics.maxConnections} (${component.metrics.activeConnections} active)`
          : "-"],
        ["DB size", component.metrics?.databaseSizeBytes
          ? `${(component.metrics.databaseSizeBytes / 1_048_576).toFixed(1)} MB`
          : "-"],
        ["Cache hit", component.metrics
          ? `${(Number(component.metrics.cacheHitRatio || 0) * 100).toFixed(1)}%`
          : "-"],
      ] : [
        ["Redis memory", component.metrics?.redisUsedMemoryBytes
          ? `${(component.metrics.redisUsedMemoryBytes / 1_048_576).toFixed(1)} MB`
          : "-"],
        ["Clients", component.metrics ? String(component.metrics.connectedClients ?? 0) : "-"],
        ["Operations", component.metrics ? `${component.metrics.operationsPerSecond ?? 0} /s` : "-"],
        ["Cache hit", component.metrics
          ? `${(Number(component.metrics.cacheHitRatio || 0) * 100).toFixed(1)}%`
          : "-"],
      ]),
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

function environmentRow(key = "", value = "") {
  const row = document.createElement("div");
  row.className = "environment-row";
  const keyInput = document.createElement("input");
  keyInput.className = "environment-key";
  keyInput.placeholder = "KEY";
  keyInput.value = key;
  keyInput.setAttribute("aria-label", "Environment variable key");
  const valueInput = document.createElement("input");
  valueInput.className = "environment-value";
  valueInput.placeholder = "Value";
  valueInput.value = value;
  valueInput.setAttribute("aria-label", `${key || "Environment variable"} value`);
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "icon-button danger";
  remove.textContent = "×";
  remove.title = "Remove variable";
  remove.addEventListener("click", () => row.remove());
  row.append(keyInput, valueInput, remove);
  return row;
}

function environmentEditor(values = {}) {
  const section = document.createElement("section");
  section.className = "environment-editor";
  const heading = document.createElement("div");
  const copy = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = "Environment variables";
  const description = document.createElement("span");
  description.textContent = "Container key-value settings. Restart applies saved values.";
  copy.append(title, description);
  const add = document.createElement("button");
  add.type = "button";
  add.className = "button button-secondary";
  add.textContent = "Add";
  const rows = document.createElement("div");
  rows.className = "environment-rows";
  const entries = Object.entries(values);
  rows.append(...(entries.length ? entries : [["", ""]]).map(([key, value]) => environmentRow(key, value)));
  add.addEventListener("click", () => {
    const row = environmentRow();
    rows.append(row);
    row.querySelector(".environment-key").focus();
  });
  heading.append(copy, add);
  section.append(heading, rows);
  return section;
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
  fields.push(environmentEditor(config.environment || {}));
  elements.componentConfigFields.replaceChildren(...fields);
  elements.componentConfigDialog.showModal();
}

async function saveComponentConfig(event) {
  event.preventDefault();
  const values = Object.fromEntries(new FormData(elements.componentConfigForm));
  for (const key of ["hostPort", "cpus", "memoryMb", "maxMemoryMb"]) {
    if (values[key] !== undefined) values[key] = Number(values[key]);
  }
  values.environment = Object.fromEntries(
    [...elements.componentConfigFields.querySelectorAll(".environment-row")]
      .map((row) => [
        row.querySelector(".environment-key").value.trim(),
        row.querySelector(".environment-value").value,
      ])
      .filter(([key]) => key),
  );
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
    state.runPage = 1;
    localStorage.setItem("testzone.projectId", state.projectId);
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
  });
  elements.newProjectButton.addEventListener("click", openNewProjectDialog);
  elements.newProjectForm.addEventListener("submit", createProject);
  elements.deleteProjectButton.addEventListener("click", deleteProject);
  elements.refreshRunsButton.addEventListener("click", loadRuns);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      window.clearTimeout(state.pollTimer);
      return;
    }
    scheduleRunPolling();
  });
  elements.runPreviousPageButton.addEventListener("click", () => {
    if (state.runPage > 1) void goToRunPage(state.runPage - 1);
  });
  elements.runNextPageButton.addEventListener("click", () => {
    if (state.runPage < state.runTotalPages) void goToRunPage(state.runPage + 1);
  });
  elements.runPageJumpForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const page = Number(elements.runPageJumpSelect.value);
    elements.runPageJumpDialog.close();
    void goToRunPage(page);
  });
  elements.runDetailScriptButton.addEventListener("click", () => {
    if (selectedRun()) void openRunScript(selectedRun());
  });
  elements.rerunSelectedRunButton.addEventListener("click", () => {
    if (selectedRun()) void rerunRun(selectedRun(), elements.rerunSelectedRunButton);
  });
  elements.closeRunDetailButton.addEventListener("click", () => {
    state.selectedRunId = null;
    state.runSeries = [];
    state.runSeriesId = null;
    renderRuns();
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
