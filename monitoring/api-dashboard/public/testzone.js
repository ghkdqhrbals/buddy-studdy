import {
  RUN_PROFILES,
  buildChartPoints,
  editorPosition,
  formatDate,
  formatMilliseconds,
  formatPercent,
  formatRate,
  lineNumbersFor,
  parseObjectJson,
  runScriptName,
  selectLatestRun,
} from "./testzone-model.js?v=2026072403";

const API_BASE = "/testzone/api";
const state = {
  status: null,
  projects: [],
  scripts: [],
  runs: [],
  components: [],
  projectId: null,
  scriptId: null,
  dirty: false,
  assistantDraft: null,
  confirmAction: null,
  pollTimer: null,
};

const elements = Object.fromEntries(
  [
    "serviceStatus", "projectSelect", "projectBaseUrl", "saveProjectButton", "projectFeedback",
    "headerRunButton", "overviewRunButton", "quickScriptSelect", "profileShortcuts",
    "summaryStatus", "summaryRps", "summaryP95", "summaryError", "runHistoryChart",
    "runRows", "runEmptyState", "runCount", "refreshRunsButton",
    "scriptList", "newScriptButton", "scriptNameInput", "scriptEditor", "editorLineNumbers",
    "editorPosition", "editorDirtyMark", "editorFeedback", "validateScriptButton",
    "saveScriptButton", "editorRunButton", "deleteScriptButton",
    "assistantAvailability", "assistantMessages", "assistantDraft", "assistantDraftMessage",
    "applyAssistantDraftButton", "assistantForm", "assistantPrompt", "assistantSendButton",
    "componentGrid", "refreshComponentsButton",
    "runDialog", "runForm", "runProjectName", "runTargetUrl", "runScriptSelect",
    "runProfileControl", "runDuration", "runVus", "runMaxVus", "runTargetRps",
    "runHeaders", "runEnvironment", "runFormError", "startRunButton",
    "newScriptDialog", "newScriptForm", "newScriptName", "newScriptDescription",
    "confirmDialog", "confirmForm", "confirmTitle", "confirmMessage", "confirmActionButton",
    "toastRegion",
  ].map((id) => [id, document.getElementById(id)]),
);

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
  document.querySelectorAll(".testzone-tabs button").forEach((button) => {
    button.setAttribute("aria-selected", String(button.dataset.tab === name));
  });
  document.querySelectorAll("[data-panel]").forEach((panel) => {
    panel.hidden = panel.dataset.panel !== name;
  });
  if (name === "components") loadComponents();
  if (name === "scripts") window.setTimeout(syncEditorMetrics, 0);
}

async function loadStatus() {
  try {
    state.status = await api("/status");
    elements.serviceStatus.textContent = "Ready";
    elements.serviceStatus.dataset.state = "ready";
    elements.assistantAvailability.dataset.enabled = String(state.status.integrations.openAI);
    elements.assistantAvailability.title = state.status.integrations.openAI
      ? "OpenAI is ready"
      : "OpenAI key is not configured";
    elements.assistantPrompt.disabled = !state.status.integrations.openAI;
    elements.assistantSendButton.disabled = !state.status.integrations.openAI;
    elements.assistantPrompt.placeholder = state.status.integrations.openAI
      ? "예: POST /api/v1/studies 요청을 200 RPS로 테스트하고 응답의 id를 검증해줘"
      : "OpenAI key is not configured on the TestZone server.";
  } catch (error) {
    elements.serviceStatus.textContent = "Service unavailable";
    elements.serviceStatus.dataset.state = "error";
    throw error;
  }
}

async function loadProjects() {
  const payload = await api("/projects");
  state.projects = payload.projects;
  const remembered = localStorage.getItem("testzone.projectId");
  state.projectId = state.projects.some((entry) => entry.id === remembered)
    ? remembered
    : state.projects[0]?.id ?? null;
  renderProjects();
  await Promise.all([loadScripts(), loadRuns()]);
  renderRuns();
}

function renderProjects() {
  elements.projectSelect.replaceChildren(...state.projects.map((entry) => {
    const option = document.createElement("option");
    option.value = entry.id;
    option.textContent = entry.name;
    option.selected = entry.id === state.projectId;
    return option;
  }));
  const selected = project();
  elements.projectBaseUrl.value = selected?.baseUrl || "";
}

async function saveProject() {
  const selected = project();
  if (!selected) return;
  setButtonBusy(elements.saveProjectButton, true, "Saving");
  try {
    const payload = await api(`/projects/${selected.id}`, {
      method: "PATCH",
      body: JSON.stringify({ baseUrl: elements.projectBaseUrl.value }),
    });
    Object.assign(selected, payload.project);
    setFeedback(elements.projectFeedback, "Saved", "success");
  } catch (error) {
    setFeedback(elements.projectFeedback, error.message, "error");
  } finally {
    setButtonBusy(elements.saveProjectButton, false);
  }
}

async function loadScripts() {
  if (!state.projectId) return;
  const payload = await api(`/scripts?projectId=${encodeURIComponent(state.projectId)}`);
  state.scripts = payload.scripts;
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
  elements.saveScriptButton.disabled = !selected;
  elements.validateScriptButton.disabled = !selected;
  elements.editorRunButton.disabled = !selected;
  elements.deleteScriptButton.disabled = !selected;
}

function renderDirtyState() {
  elements.editorDirtyMark.dataset.dirty = String(state.dirty);
}

function markDirty() {
  state.dirty = true;
  renderDirtyState();
  setFeedback(elements.editorFeedback, "Unsaved");
}

function syncEditorMetrics() {
  const code = elements.scriptEditor.value;
  elements.editorLineNumbers.textContent = lineNumbersFor(code);
  elements.editorLineNumbers.scrollTop = elements.scriptEditor.scrollTop;
  const position = editorPosition(code, elements.scriptEditor.selectionStart);
  elements.editorPosition.textContent = `Ln ${position.line}, Col ${position.column}`;
}

async function saveScript() {
  const selected = script();
  if (!selected) return;
  setButtonBusy(elements.saveScriptButton, true, "Saving");
  try {
    const payload = await api(`/scripts/${selected.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        name: elements.scriptNameInput.value,
        code: elements.scriptEditor.value,
      }),
    });
    Object.assign(selected, payload.script);
    state.dirty = false;
    renderDirtyState();
    renderScripts();
    setFeedback(elements.editorFeedback, "Saved", "success");
  } catch (error) {
    setFeedback(elements.editorFeedback, error.details?.[0] || error.message, "error");
  } finally {
    setButtonBusy(elements.saveScriptButton, false);
  }
}

async function validateCurrentScript() {
  const selected = script();
  if (!selected) return;
  setButtonBusy(elements.validateScriptButton, true, "Checking");
  try {
    const payload = await api(`/scripts/${selected.id}/validate`, {
      method: "POST",
      body: JSON.stringify({
        code: elements.scriptEditor.value,
        baseUrl: project()?.baseUrl,
      }),
    });
    setFeedback(elements.editorFeedback, `Valid · ${payload.validation.bytes.toLocaleString()} bytes`, "success");
    toast("Script validation passed.");
  } catch (error) {
    const message = [error.message, ...(error.details || [])].join(" ");
    setFeedback(elements.editorFeedback, error.details?.[0] || error.message, "error");
    toast(message, "error");
  } finally {
    setButtonBusy(elements.validateScriptButton, false);
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
  const code = script()?.code || elements.scriptEditor.value;
  try {
    const payload = await api("/scripts", {
      method: "POST",
      body: JSON.stringify({
        projectId: state.projectId,
        name: elements.newScriptName.value,
        description: elements.newScriptDescription.value,
        code,
      }),
    });
    state.scripts.unshift(payload.script);
    state.scriptId = payload.script.id;
    elements.newScriptDialog.close();
    renderScripts();
    toast("New script created.");
  } catch (error) {
    toast(error.details?.[0] || error.message, "error");
  }
}

async function deleteCurrentScript() {
  const selected = script();
  if (!selected) return;
  const accepted = await confirmAction(
    "Delete script?",
    `${selected.name} 파일을 삭제합니다. 완료된 실행 결과는 유지됩니다.`,
    "Delete script",
  );
  if (!accepted) return;
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

async function askAssistant(event) {
  event.preventDefault();
  const selected = script();
  const prompt = elements.assistantPrompt.value.trim();
  if (!selected || !prompt) return;
  appendAssistantMessage(prompt, "user");
  elements.assistantPrompt.value = "";
  setButtonBusy(elements.assistantSendButton, true, "Generating");
  try {
    const payload = await api(`/scripts/${selected.id}/ai`, {
      method: "POST",
      body: JSON.stringify({ prompt, currentCode: elements.scriptEditor.value }),
    });
    state.assistantDraft = payload.result.code;
    elements.assistantDraftMessage.textContent = payload.result.message;
    elements.assistantDraft.hidden = false;
    appendAssistantMessage(payload.result.message, "assistant");
  } catch (error) {
    appendAssistantMessage(error.message, "error");
  } finally {
    setButtonBusy(elements.assistantSendButton, false);
  }
}

function appendAssistantMessage(message, role) {
  const item = document.createElement("div");
  item.className = `assistant-message assistant-${role}`;
  const paragraph = document.createElement("p");
  paragraph.textContent = message;
  item.append(paragraph);
  elements.assistantMessages.append(item);
  elements.assistantMessages.scrollTop = elements.assistantMessages.scrollHeight;
}

function applyAssistantDraft() {
  if (!state.assistantDraft) return;
  elements.scriptEditor.value = state.assistantDraft;
  state.assistantDraft = null;
  elements.assistantDraft.hidden = true;
  markDirty();
  syncEditorMetrics();
  elements.scriptEditor.focus();
}

async function loadRuns() {
  if (!state.projectId) return;
  const payload = await api(`/runs?projectId=${encodeURIComponent(state.projectId)}`);
  state.runs = payload.runs;
  renderRuns();
  scheduleRunPolling();
}

function renderRuns() {
  const latest = selectLatestRun(state.runs);
  elements.summaryStatus.textContent = latest?.status || "No runs";
  elements.summaryRps.textContent = formatRate(latest?.summary?.requestRate);
  elements.summaryP95.textContent = formatMilliseconds(latest?.summary?.p95Ms);
  elements.summaryError.textContent = formatPercent(latest?.summary?.errorRate);
  elements.runCount.textContent = `${state.runs.length.toLocaleString()} runs`;
  elements.runEmptyState.hidden = state.runs.length > 0;
  elements.runRows.replaceChildren(...state.runs.map((run) => {
    const row = document.createElement("tr");
    const cells = [
      formatDate(run.startedAt || run.createdAt),
      runScriptName(run, state.scripts),
      run.profile,
    ].map((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      return cell;
    });
    const statusCell = document.createElement("td");
    const status = document.createElement("span");
    status.className = "status-pill";
    status.dataset.status = run.status;
    status.textContent = run.status;
    statusCell.append(status);
    const metrics = [
      formatRate(run.summary?.requestRate),
      formatMilliseconds(run.summary?.p95Ms),
      formatPercent(run.summary?.errorRate),
    ].map((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      return cell;
    });
    const actions = document.createElement("td");
    actions.className = "row-actions";
    if (["queued", "running", "cancelling"].includes(run.status)) {
      actions.append(actionButton("Cancel", () => cancelRun(run.id)));
    } else {
      const grafana = document.createElement("a");
      grafana.className = "row-action";
      grafana.href = run.grafanaUrl;
      grafana.target = "_blank";
      grafana.rel = "noreferrer";
      grafana.textContent = "Grafana";
      actions.append(grafana, actionButton("Delete", () => deleteRun(run), true));
    }
    row.append(...cells, statusCell, ...metrics, actions);
    return row;
  }));
  drawRunChart();
}

function actionButton(label, handler, danger = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `row-action${danger ? " danger" : ""}`;
  button.textContent = label;
  button.addEventListener("click", handler);
  return button;
}

function scheduleRunPolling() {
  window.clearTimeout(state.pollTimer);
  if (state.runs.some((run) => ["queued", "running", "cancelling"].includes(run.status))) {
    state.pollTimer = window.setTimeout(loadRuns, 2000);
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
  const accepted = await confirmAction(
    "Delete run?",
    `${formatDate(run.startedAt || run.createdAt)} 실행과 InfluxDB 시계열을 함께 삭제합니다.`,
    "Delete run",
  );
  if (!accepted) return;
  try {
    await api(`/runs/${run.id}`, { method: "DELETE" });
    state.runs = state.runs.filter((entry) => entry.id !== run.id);
    renderRuns();
    toast("Run and time-series data deleted.");
  } catch (error) {
    toast(error.message, "error");
  }
}

function drawRunChart() {
  const canvas = elements.runHistoryChart;
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(320, Math.round(rect.width));
  const height = 260;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const context = canvas.getContext("2d");
  context.scale(ratio, ratio);
  context.clearRect(0, 0, width, height);
  const points = buildChartPoints(state.runs);
  if (!points.length) {
    context.fillStyle = "#7b8a9e";
    context.font = "12px system-ui";
    context.textAlign = "center";
    context.fillText("Completed runs will appear here.", width / 2, height / 2);
    return;
  }
  const padding = { top: 22, right: 44, bottom: 34, left: 44 };
  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;
  const maxRps = Math.max(...points.map((point) => point.rps), 1) * 1.15;
  const maxP95 = Math.max(...points.map((point) => point.p95), 1) * 1.15;
  context.strokeStyle = "#e4e9ef";
  context.lineWidth = 1;
  for (let index = 0; index <= 4; index += 1) {
    const y = padding.top + (chartHeight * index) / 4;
    context.beginPath();
    context.moveTo(padding.left, y);
    context.lineTo(width - padding.right, y);
    context.stroke();
  }
  const step = chartWidth / points.length;
  const barWidth = Math.min(26, step * 0.5);
  points.forEach((point, index) => {
    const x = padding.left + step * index + step / 2;
    const barHeight = (point.rps / maxRps) * chartHeight;
    context.fillStyle = "#2166d1";
    context.fillRect(x - barWidth / 2, padding.top + chartHeight - barHeight, barWidth, barHeight);
    context.fillStyle = "#64748b";
    context.font = "9px system-ui";
    context.textAlign = "center";
    context.fillText(point.label, x, height - 12);
  });
  context.beginPath();
  points.forEach((point, index) => {
    const x = padding.left + step * index + step / 2;
    const y = padding.top + chartHeight - (point.p95 / maxP95) * chartHeight;
    if (index === 0) context.moveTo(x, y);
    else context.lineTo(x, y);
  });
  context.strokeStyle = "#e4982b";
  context.lineWidth = 2;
  context.stroke();
  context.fillStyle = "#64748b";
  context.font = "9px system-ui";
  context.textAlign = "left";
  context.fillText(`${Math.round(maxRps)} RPS`, 2, padding.top + 4);
  context.textAlign = "right";
  context.fillText(formatMilliseconds(maxP95), width - 2, padding.top + 4);
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
    const headers = parseObjectJson(elements.runHeaders.value, "Headers");
    const environment = parseObjectJson(elements.runEnvironment.value, "Environment");
    await api("/runs", {
      method: "POST",
      body: JSON.stringify({
        projectId: state.projectId,
        scriptId: elements.runScriptSelect.value,
        profile: elements.runForm.dataset.profile || "custom",
        options: {
          duration: elements.runDuration.value,
          vus: Number(elements.runVus.value),
          maxVus: Number(elements.runMaxVus.value),
          targetRps: Number(elements.runTargetRps.value),
        },
        headers,
        environment,
      }),
    });
    elements.runDialog.close();
    switchTab("overview");
    toast("Performance test started.");
    await loadRuns();
  } catch (error) {
    elements.runFormError.textContent = [error.message, ...(error.details || [])].join(" ");
  } finally {
    setButtonBusy(elements.startRunButton, false);
  }
}

async function loadComponents() {
  elements.componentGrid.setAttribute("aria-busy", "true");
  try {
    const payload = await api("/components");
    state.components = payload.components;
    renderComponents();
  } catch (error) {
    toast(error.message, "error");
  } finally {
    elements.componentGrid.removeAttribute("aria-busy");
  }
}

function renderComponents() {
  elements.componentGrid.replaceChildren(...state.components.map((component) => {
    const card = document.createElement("article");
    card.className = "component-card";
    const header = document.createElement("header");
    const heading = document.createElement("h3");
    heading.textContent = component.name;
    const status = document.createElement("span");
    status.className = "status-pill";
    status.dataset.status = component.status;
    status.textContent = component.status;
    header.append(heading, status);
    const endpoint = document.createElement("code");
    endpoint.textContent = component.endpoint;
    const actions = document.createElement("div");
    actions.className = "component-actions";
    if (component.status === "not-deployed" || component.status === "exited") {
      actions.append(componentAction("Deploy", component.id, "deploy", true));
    } else {
      actions.append(
        componentAction("Restart", component.id, "restart"),
        componentAction("Delete", component.id, "delete", false, true),
      );
    }
    card.append(header, endpoint, actions);
    return card;
  }));
}

function componentAction(label, id, action, primary = false, danger = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `button ${primary ? "button-primary" : danger ? "button-danger" : "button-secondary"}`;
  button.textContent = label;
  button.addEventListener("click", async () => {
    if (action === "delete") {
      const accepted = await confirmAction(
        `Delete ${id}?`,
        "테스트 컴포넌트 컨테이너를 즉시 제거합니다.",
        "Delete component",
      );
      if (!accepted) return;
    }
    setButtonBusy(button, true, action === "delete" ? "Deleting" : action === "restart" ? "Restarting" : "Deploying");
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
    const start = elements.scriptEditor.selectionStart;
    const end = elements.scriptEditor.selectionEnd;
    elements.scriptEditor.setRangeText("  ", start, end, "end");
    markDirty();
    syncEditorMetrics();
  }
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    saveScript();
  }
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
    localStorage.setItem("testzone.projectId", state.projectId);
    renderProjects();
    await Promise.all([loadScripts(), loadRuns()]);
    renderRuns();
  });
  elements.saveProjectButton.addEventListener("click", saveProject);
  elements.headerRunButton.addEventListener("click", () => openRunDialog());
  elements.overviewRunButton.addEventListener("click", () => openRunDialog());
  elements.profileShortcuts.addEventListener("click", (event) => {
    const button = event.target.closest("[data-profile]");
    if (button) openRunDialog(button.dataset.profile, elements.quickScriptSelect.value);
  });
  elements.refreshRunsButton.addEventListener("click", loadRuns);
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
  elements.scriptEditor.addEventListener("scroll", syncEditorMetrics);
  elements.scriptEditor.addEventListener("click", syncEditorMetrics);
  elements.scriptEditor.addEventListener("keyup", syncEditorMetrics);
  elements.scriptEditor.addEventListener("keydown", handleEditorKeydown);
  elements.scriptNameInput.addEventListener("input", markDirty);
  elements.saveScriptButton.addEventListener("click", saveScript);
  elements.validateScriptButton.addEventListener("click", validateCurrentScript);
  elements.editorRunButton.addEventListener("click", () => openRunDialog("standard", state.scriptId));
  elements.deleteScriptButton.addEventListener("click", deleteCurrentScript);
  elements.assistantForm.addEventListener("submit", askAssistant);
  elements.applyAssistantDraftButton.addEventListener("click", applyAssistantDraft);
  elements.runProfileControl.addEventListener("click", (event) => {
    const button = event.target.closest("[data-profile]");
    if (button) applyProfile(button.dataset.profile);
  });
  elements.runForm.addEventListener("submit", startRun);
  elements.refreshComponentsButton.addEventListener("click", loadComponents);
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
    toast(error.message, "error");
    document.querySelectorAll("button, input, select, textarea").forEach((control) => {
      if (!control.closest(".side-nav")) control.disabled = true;
    });
  }
}

initialize();
