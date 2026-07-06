import {
  durationLabel,
  lokiMetricTimestampToMs,
  parseApiError,
  parseApiExchange,
  parseRelatedLog,
  safeJson,
  statusTone,
} from "./logs.js?v=2026070614";

const DEFAULT_RANGE_MS = 3_600_000;

const state = {
  requests: [],
  filtered: [],
  expandedRequestId: "",
  detailCache: new Map(),
  loadingDetails: new Set(),
  selectedRange: null,
  timeline: [],
  drag: null,
  loadingRequests: false,
  pageSize: 100,
  pageIndex: 0,
  pageCursors: [null],
  hasNextPage: false,
};

const els = {
  rangeSelect: document.querySelector("#rangeSelect"),
  customRangeFields: document.querySelector("#customRangeFields"),
  customStartDateInput: document.querySelector("#customStartDateInput"),
  customStartTimeInput: document.querySelector("#customStartTimeInput"),
  customEndDateInput: document.querySelector("#customEndDateInput"),
  customEndTimeInput: document.querySelector("#customEndTimeInput"),
  applyCustomRangeButton: document.querySelector("#applyCustomRangeButton"),
  methodSelect: document.querySelector("#methodSelect"),
  statusSelect: document.querySelector("#statusSelect"),
  pathInput: document.querySelector("#pathInput"),
  requestIdInput: document.querySelector("#requestIdInput"),
  logSearchInput: document.querySelector("#logSearchInput"),
  refreshButton: document.querySelector("#refreshButton"),
  requestRows: document.querySelector("#requestRows"),
  requestLoadingOverlay: document.querySelector("#requestLoadingOverlay"),
  statusMessage: document.querySelector("#statusMessage"),
  rangeLabel: document.querySelector("#rangeLabel"),
  emptyTemplate: document.querySelector("#emptyTemplate"),
  timelineCanvas: document.querySelector("#timelineCanvas"),
  timelineSelection: document.querySelector("#timelineSelection"),
  timelineRangeLabel: document.querySelector("#timelineRangeLabel"),
  timelineCountLabel: document.querySelector("#timelineCountLabel"),
  resetTimelineButton: document.querySelector("#resetTimelineButton"),
  pageSizeSelect: document.querySelector("#pageSizeSelect"),
  prevPageButton: document.querySelector("#prevPageButton"),
  nextPageButton: document.querySelector("#nextPageButton"),
  pageInfo: document.querySelector("#pageInfo"),
};

const API_EXCHANGE_QUERY = '{container=~".+"} |= "api_exchange"';
const TIMELINE_QUERY = 'sum(count_over_time(({container=~".+"} |= "api_exchange")[$__range]))';

function nowMs() {
  return Date.now();
}

function ns(ms) {
  return (BigInt(ms) * 1_000_000n).toString();
}

function timeRange() {
  if (state.selectedRange) {
    return {
      ...state.selectedRange,
      startNs: ns(state.selectedRange.startMs),
      endNs: ns(state.selectedRange.endMs),
    };
  }
  const endMs = nowMs();
  const selectedDuration = Number(els.rangeSelect.value);
  const durationMs = Number.isFinite(selectedDuration) ? selectedDuration : DEFAULT_RANGE_MS;
  const startMs = endMs - durationMs;
  return { startMs, endMs, startNs: ns(startMs), endNs: ns(endMs) };
}

async function lokiQueryRange(query, { startNs, endNs, limit = 500, direction = "backward", step = null }) {
  const params = new URLSearchParams({
    query,
    start: startNs,
    end: endNs,
    limit: String(limit),
    direction,
  });
  if (step) {
    params.set("step", step);
  }
  const response = await fetch(`/loki/api/v1/query_range?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Loki query failed: ${response.status}`);
  }
  const payload = await response.json();
  return (payload.data?.result ?? []).flatMap((stream) => stream.values ?? []);
}

async function loadRequests() {
  resetPagination();
  await loadRequestPage({ refreshTimeline: true });
}

async function loadRequestPage({ refreshTimeline = false } = {}) {
  state.loadingRequests = true;
  renderLoadingState();
  setStatus("Loading", "loading");
  const range = timeRange();
  try {
    const pageEndNs = state.pageCursors[state.pageIndex] ?? range.endNs;
    const pageQuery = lokiQueryRange(buildApiExchangeQuery(), {
      ...range,
      endNs: pageEndNs,
      limit: state.pageSize + 1,
    });
    const [timelineValues, values] = await Promise.all([
      refreshTimeline ? loadTimeline(range) : Promise.resolve(state.timeline),
      pageQuery,
    ]);
    state.timeline = timelineValues;
    const parsed = values
      .map((value) => {
        try {
          return parseApiExchange(value);
        } catch (error) {
          console.warn("Failed to parse api_exchange", error, value);
          return null;
        }
      })
      .filter(Boolean)
      .sort((a, b) => Number(BigInt(b.nanoseconds) - BigInt(a.nanoseconds)));
    state.hasNextPage = parsed.length > state.pageSize;
    state.requests = parsed.slice(0, state.pageSize);
    const oldest = state.requests[state.requests.length - 1];
    if (oldest && state.hasNextPage) {
      state.pageCursors[state.pageIndex + 1] = (BigInt(oldest.nanoseconds) - 1n).toString();
    } else {
      state.pageCursors.length = state.pageIndex + 1;
    }
    state.detailCache.clear();
    state.expandedRequestId = "";
    applyFilters();
    render();
    setStatus("Ready", "ready");
  } finally {
    state.loadingRequests = false;
    renderLoadingState();
  }
}

function resetPagination() {
  state.pageIndex = 0;
  state.pageCursors = [null];
  state.hasNextPage = false;
}

async function loadTimeline(range) {
  const stepMs = chooseTimelineStepMs(range.endMs - range.startMs);
  const query = TIMELINE_QUERY.replace("$__range", formatLogqlDuration(stepMs));
  const values = await lokiQueryRange(query, {
    ...range,
    limit: 1000,
    direction: "forward",
    step: formatLogqlDuration(stepMs),
  });
  return values.map(([nanoseconds, value]) => ({
    nanoseconds,
    ms: lokiMetricTimestampToMs(nanoseconds),
    count: Number(value),
  }));
}

function applyFilters() {
  state.filtered = state.requests;
}

function buildApiExchangeQuery() {
  const parts = [API_EXCHANGE_QUERY];
  const method = els.methodSelect.value.trim();
  const statusPrefix = els.statusSelect.value.trim();
  const path = els.pathInput.value.trim();
  const requestId = els.requestIdInput.value.trim();
  const logSearch = els.logSearchInput.value.trim();
  if (method) {
    parts.push(`|= ${quoteLogql(`"method":"${method}"`)}`);
  }
  if (statusPrefix) {
    parts.push(`|~ ${quoteLogql(`"status":${statusPrefix}[0-9][0-9]`)}`);
  }
  if (path) {
    parts.push(`|= ${quoteLogql(path)}`);
  }
  if (requestId) {
    parts.push(`|= ${quoteLogql(requestId)}`);
  }
  if (logSearch) {
    parts.push(`|= ${quoteLogql(logSearch)}`);
  }
  return parts.join(" ");
}

function quoteLogql(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function render() {
  renderRangeLabel();
  renderTimeline();
  renderRows();
  renderLoadingState();
}

function renderRangeLabel() {
  const count = state.filtered.length;
  const range = timeRange();
  const suffix = state.hasNextPage ? `page ${state.pageIndex + 1}, ${state.pageSize} per page` : `page ${state.pageIndex + 1}`;
  els.rangeLabel.textContent = `${count} visible requests, ${suffix}`;
  els.timelineRangeLabel.textContent = `${formatKstShort(range.startMs)} - ${formatKstShort(range.endMs)} · drag to zoom`;
  els.resetTimelineButton.disabled = !state.selectedRange;
  syncCustomRangeControls(range);
  els.pageInfo.textContent = `Page ${state.pageIndex + 1}`;
  els.prevPageButton.disabled = state.pageIndex === 0;
  els.nextPageButton.disabled = !state.hasNextPage;
  els.pageSizeSelect.value = String(state.pageSize);
}

function renderRows() {
  els.requestRows.innerHTML = "";
  if (state.filtered.length === 0 && !state.loadingRequests) {
    els.requestRows.append(els.emptyTemplate.content.cloneNode(true));
    return;
  }
  for (const request of state.filtered) {
    els.requestRows.append(renderRequestRow(request));
    if (state.expandedRequestId === request.requestId) {
      els.requestRows.append(renderDetailElement(request));
    }
  }
}

function renderLoadingState() {
  els.requestLoadingOverlay.hidden = !state.loadingRequests;
  els.requestRows.classList.toggle("is-loading", state.loadingRequests);
  els.refreshButton.disabled = state.loadingRequests;
  els.prevPageButton.disabled = state.loadingRequests || state.pageIndex === 0;
  els.nextPageButton.disabled = state.loadingRequests || !state.hasNextPage;
  els.pageSizeSelect.disabled = state.loadingRequests;
  els.customStartDateInput.disabled = state.loadingRequests;
  els.customStartTimeInput.disabled = state.loadingRequests;
  els.customEndDateInput.disabled = state.loadingRequests;
  els.customEndTimeInput.disabled = state.loadingRequests;
  els.applyCustomRangeButton.disabled = state.loadingRequests;
}

function renderRequestRow(request) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = `request-row data-row ${state.expandedRequestId === request.requestId ? "selected" : ""}`;
  row.setAttribute("role", "row");
  row.setAttribute("aria-expanded", String(state.expandedRequestId === request.requestId));
  row.addEventListener("click", () => toggleDetails(request));
  row.innerHTML = `
    <div class="time-cell" role="cell">${escapeHtml(request.time)}</div>
    <div role="cell"><span class="method-badge method-${escapeHtml(request.method.toLowerCase())}">${escapeHtml(request.method)}</span></div>
    <div class="path-cell" role="cell" title="${escapeHtml(request.path)}">${escapeHtml(request.path)}</div>
    <div role="cell"><span class="status-badge ${statusTone(request.status)}">${request.status || "-"}</span></div>
    <div role="cell">${durationLabel(request.durationMs)}</div>
    <div class="ip-cell" role="cell">${escapeHtml(request.clientIp || "-")}</div>
    <div class="request-id-cell" role="cell">${escapeHtml(request.requestId)}</div>
  `;
  return row;
}

function renderDetailElement(request) {
  const detail = document.createElement("section");
  detail.className = "detail-panel inline-detail";
  detail.setAttribute("aria-label", `Request details for ${request.requestId}`);
  const details = state.detailCache.get(request.requestId);
  if (!details) {
    detail.innerHTML = `
      <div class="detail-loading">
        <span class="loading-spinner" aria-hidden="true"></span>
        <strong>Loading connected logs</strong>
      </div>
    `;
    return detail;
  }

  const error = details.errors[0];
  detail.innerHTML = `
    <div class="detail-toolbar">
      <div>
        <strong>${escapeHtml(request.method)} ${escapeHtml(request.path)}</strong>
        <span>${escapeHtml(request.time)} KST</span>
      </div>
      <button type="button" data-copy="${escapeHtml(request.requestId)}">Copy requestId</button>
    </div>
    <div class="detail-grid">
      ${jsonPanel("Request", request.request)}
      ${jsonPanel("Response", request.response)}
    </div>
    ${error ? stackPanel(error) : ""}
    ${relatedLogsPanel(details.logs)}
  `;
  detail.querySelector("[data-copy]")?.addEventListener("click", (event) => {
    event.stopPropagation();
    navigator.clipboard?.writeText(request.requestId);
  });
  return detail;
}

function jsonPanel(title, value) {
  return `
    <article class="json-panel">
      <h3>${escapeHtml(title)}</h3>
      <pre>${escapeHtml(safeJson(value))}</pre>
    </article>
  `;
}

function stackPanel(error) {
  return `
    <article class="stack-panel">
      <div class="stack-heading">
        <h3>Error Stack Trace</h3>
        <span>${escapeHtml(error.code || "ERROR")} · ${escapeHtml(error.message || "")}</span>
      </div>
      <pre>${escapeHtml(error.stack || error.rawLine)}</pre>
    </article>
  `;
}

function relatedLogsPanel(logs) {
  const items = logs.map((log) => `
    <li class="log-line ${log.level.toLowerCase()}">
      <time>${escapeHtml(log.time)}</time>
      <span>${escapeHtml(log.level)}</span>
      <code>${escapeHtml(log.message)}</code>
    </li>
  `).join("");
  return `
    <article class="related-logs">
      <h3>Related Logs</h3>
      <ol>${items || "<li class=\"log-line empty\">No connected logs found.</li>"}</ol>
    </article>
  `;
}

async function toggleDetails(request) {
  state.expandedRequestId = state.expandedRequestId === request.requestId ? "" : request.requestId;
  render();
  if (!state.expandedRequestId || state.detailCache.has(request.requestId) || state.loadingDetails.has(request.requestId)) {
    return;
  }
  state.loadingDetails.add(request.requestId);
  try {
    const details = await loadDetails(request);
    state.detailCache.set(request.requestId, details);
  } catch (error) {
    state.detailCache.set(request.requestId, { logs: [{ ...request, level: "ERROR", message: error.message }], errors: [] });
  } finally {
    state.loadingDetails.delete(request.requestId);
    render();
  }
}

async function loadDetails(request) {
  const requestMs = Number(BigInt(request.nanoseconds) / 1_000_000n);
  const startNs = ns(requestMs - 10 * 60 * 1000);
  const endNs = ns(requestMs + 10 * 60 * 1000);
  const query = `{container=~".+"} |= "${request.requestId}"`;
  const values = await lokiQueryRange(query, { startNs, endNs, limit: 200, direction: "forward" });
  const logs = values.map(parseRelatedLog).sort((a, b) => Number(BigInt(a.nanoseconds) - BigInt(b.nanoseconds)));
  const errors = values
    .map((value) => {
      try {
        return parseApiError(value);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  return { logs, errors };
}

function renderTimeline() {
  const canvas = els.timelineCanvas;
  const context = canvas.getContext("2d");
  const rect = canvas.getBoundingClientRect();
  const pixelRatio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.floor(rect.width * pixelRatio));
  canvas.height = Math.max(1, Math.floor(rect.height * pixelRatio));
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  context.clearRect(0, 0, rect.width, rect.height);

  const padding = { top: 18, right: 18, bottom: 30, left: 42 };
  const width = rect.width - padding.left - padding.right;
  const height = rect.height - padding.top - padding.bottom;
  const range = timeRange();
  const points = state.timeline
    .map((point) => ({ ...point, count: Number.isFinite(point.count) ? point.count : 0 }))
    .filter((point) => point.ms >= range.startMs && point.ms <= range.endMs);
  const visiblePoints = points.filter((point) => point.count > 0);
  const total = visiblePoints.reduce((sum, point) => sum + point.count, 0);
  const max = Math.max(1, ...visiblePoints.map((point) => point.count));
  els.timelineCountLabel.textContent = `Requests ${total}`;

  context.strokeStyle = "#e1e7f0";
  context.lineWidth = 1;
  context.beginPath();
  for (let i = 0; i <= 4; i += 1) {
    const y = padding.top + (height * i) / 4;
    context.moveTo(padding.left, y);
    context.lineTo(padding.left + width, y);
  }
  context.stroke();

  context.fillStyle = "#66758a";
  context.font = "11px Inter, system-ui, sans-serif";
  context.textAlign = "right";
  context.textBaseline = "middle";
  for (let i = 0; i <= 4; i += 1) {
    const value = Math.round(max - (max * i) / 4);
    const y = padding.top + (height * i) / 4;
    context.fillText(String(value), padding.left - 10, y);
  }

  if (visiblePoints.length > 0) {
    const barWidth = Math.min(18, Math.max(4, width / Math.max(1, points.length) - 1));
    const plotStart = padding.left;
    const plotEnd = padding.left + width;
    for (const point of visiblePoints) {
      const x = padding.left + ((point.ms - range.startMs) / Math.max(1, range.endMs - range.startMs)) * width;
      const safeX = Math.min(Math.max(x - barWidth / 2, plotStart), plotEnd - barWidth);
      const barHeight = Math.max(4, (point.count / max) * height);
      const gradient = context.createLinearGradient(0, padding.top, 0, padding.top + height);
      gradient.addColorStop(0, "#93c5fd");
      gradient.addColorStop(1, "#2563eb");
      context.fillStyle = gradient;
      context.fillRect(safeX, padding.top + height - barHeight, barWidth, barHeight);
    }
  } else {
    context.fillStyle = "#8b98aa";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText("No requests in this time range", padding.left + width / 2, padding.top + height / 2);
  }

  context.fillStyle = "#66758a";
  context.textAlign = "left";
  context.textBaseline = "top";
  context.fillText(formatKstAxis(range.startMs), padding.left, padding.top + height + 9);
  context.textAlign = "right";
  context.fillText(formatKstAxis(range.endMs), padding.left + width, padding.top + height + 9);

  if (state.drag) {
    updateSelectionOverlay();
  } else {
    els.timelineSelection.hidden = true;
  }
}

function beginTimelineDrag(clientX) {
  state.drag = { startX: clientX, currentX: clientX };
  updateSelectionOverlay();
}

function moveTimelineDrag(clientX) {
  if (!state.drag) return;
  state.drag.currentX = clientX;
  updateSelectionOverlay();
}

function endTimelineDrag(clientX) {
  if (!state.drag) return;
  state.drag.currentX = clientX;
  const startMs = timelineXToMs(state.drag.startX);
  const endMs = timelineXToMs(state.drag.currentX);
  state.drag = null;
  els.timelineSelection.hidden = true;
  if (Math.abs(endMs - startMs) < 10_000) {
    renderTimeline();
    return;
  }
  applyCustomRange(Math.min(startMs, endMs), Math.max(startMs, endMs), "graph");
}

function applyCustomRange(startMs, endMs, source) {
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || startMs >= endMs) {
    setStatus("Invalid time range", "error");
    return;
  }
  state.selectedRange = { startMs, endMs };
  els.rangeSelect.value = "custom";
  syncCustomRangeControls(state.selectedRange, { force: true });
  setStatus(source === "graph" ? "Time range selected" : "Custom time range applied", "ready");
  loadRequests().catch((error) => setStatus(error.message, "error"));
}

function resetToDefaultRange() {
  state.selectedRange = null;
  els.rangeSelect.value = String(DEFAULT_RANGE_MS);
  syncCustomRangeControls(timeRange(), { force: true });
}

function timelineXToMs(clientX) {
  const rect = els.timelineCanvas.getBoundingClientRect();
  const paddingLeft = 42;
  const paddingRight = 18;
  const x = Math.min(Math.max(clientX - rect.left, paddingLeft), rect.width - paddingRight);
  const ratio = (x - paddingLeft) / Math.max(1, rect.width - paddingLeft - paddingRight);
  const range = timeRange();
  return Math.round(range.startMs + ratio * (range.endMs - range.startMs));
}

function updateSelectionOverlay() {
  if (!state.drag) return;
  const rect = els.timelineCanvas.getBoundingClientRect();
  const left = Math.min(state.drag.startX, state.drag.currentX) - rect.left;
  const right = Math.max(state.drag.startX, state.drag.currentX) - rect.left;
  els.timelineSelection.hidden = false;
  els.timelineSelection.style.left = `${Math.max(42, left)}px`;
  els.timelineSelection.style.width = `${Math.max(0, Math.min(rect.width - 18, right) - Math.max(42, left))}px`;
}

function chooseTimelineStepMs(durationMs) {
  const target = durationMs / 120;
  const steps = [1_000, 5_000, 10_000, 30_000, 60_000, 300_000, 900_000, 1_800_000, 3_600_000];
  return steps.find((step) => step >= target) ?? steps[steps.length - 1];
}

function formatLogqlDuration(ms) {
  if (ms % 3_600_000 === 0) return `${ms / 3_600_000}h`;
  if (ms % 60_000 === 0) return `${ms / 60_000}m`;
  return `${Math.max(1, Math.round(ms / 1000))}s`;
}

function formatKstShort(ms) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(ms)).replaceAll(". ", "-").replace(".", "");
}

function formatKstAxis(ms) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(ms));
}

function formatLocalParts(ms) {
  const date = new Date(ms);
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  const iso = localDate.toISOString();
  return {
    date: iso.slice(0, 10),
    time: iso.slice(11, 19),
  };
}

function parseLocalDateTime(dateValue, timeValue) {
  if (!dateValue || !timeValue) return NaN;
  const time = new Date(`${dateValue}T${timeValue}`).getTime();
  return Number.isFinite(time) ? time : NaN;
}

function syncCustomRangeControls(range, { force = false } = {}) {
  const isCustom = els.rangeSelect.value === "custom" || Boolean(state.selectedRange);
  els.customRangeFields.classList.toggle("is-custom", isCustom);
  if (isCustom) {
    els.rangeSelect.value = "custom";
  }
  const timeInputs = [
    els.customStartDateInput,
    els.customStartTimeInput,
    els.customEndDateInput,
    els.customEndTimeInput,
  ];
  const isEditing = timeInputs.includes(document.activeElement);
  if (force || !isEditing) {
    const start = formatLocalParts(range.startMs);
    const end = formatLocalParts(range.endMs);
    els.customStartDateInput.value = start.date;
    els.customStartTimeInput.value = start.time;
    els.customEndDateInput.value = end.date;
    els.customEndTimeInput.value = end.time;
  }
}

function setStatus(message, tone) {
  els.statusMessage.textContent = message;
  els.statusMessage.dataset.tone = tone;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function bindEvents() {
  for (const element of [els.rangeSelect, els.methodSelect, els.statusSelect]) {
    element.addEventListener("change", () => {
      if (element === els.rangeSelect) {
        if (els.rangeSelect.value === "custom") {
          const range = timeRange();
          state.selectedRange = { startMs: range.startMs, endMs: range.endMs };
          syncCustomRangeControls(state.selectedRange, { force: true });
        } else {
          state.selectedRange = null;
          syncCustomRangeControls(timeRange(), { force: true });
        }
      }
      loadRequests().catch((error) => setStatus(error.message, "error"));
    });
  }
  for (const element of [els.pathInput, els.requestIdInput, els.logSearchInput]) {
    element.addEventListener("keydown", (event) => {
      if (event.key !== "Enter") return;
      event.preventDefault();
      loadRequests().catch((error) => setStatus(error.message, "error"));
    });
  }
  els.pageSizeSelect.addEventListener("change", () => {
    state.pageSize = Number(els.pageSizeSelect.value);
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  els.prevPageButton.addEventListener("click", () => {
    if (state.pageIndex === 0) return;
    state.pageIndex -= 1;
    loadRequestPage().catch((error) => setStatus(error.message, "error"));
  });
  els.nextPageButton.addEventListener("click", () => {
    if (!state.hasNextPage) return;
    state.pageIndex += 1;
    loadRequestPage().catch((error) => setStatus(error.message, "error"));
  });
  els.refreshButton.addEventListener("click", () => {
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  els.resetTimelineButton.addEventListener("click", () => {
    resetToDefaultRange();
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  els.applyCustomRangeButton.addEventListener("click", () => {
    applyCustomRange(
      parseLocalDateTime(els.customStartDateInput.value, els.customStartTimeInput.value),
      parseLocalDateTime(els.customEndDateInput.value, els.customEndTimeInput.value),
      "manual"
    );
  });
  for (const element of [
    els.customStartDateInput,
    els.customStartTimeInput,
    els.customEndDateInput,
    els.customEndTimeInput,
  ]) {
    element.addEventListener("keydown", (event) => {
      if (event.key !== "Enter") return;
      event.preventDefault();
      applyCustomRange(
        parseLocalDateTime(els.customStartDateInput.value, els.customStartTimeInput.value),
        parseLocalDateTime(els.customEndDateInput.value, els.customEndTimeInput.value),
        "manual"
      );
    });
  }
  els.timelineCanvas.addEventListener("pointerdown", (event) => {
    els.timelineCanvas.setPointerCapture(event.pointerId);
    beginTimelineDrag(event.clientX);
  });
  els.timelineCanvas.addEventListener("pointermove", (event) => {
    moveTimelineDrag(event.clientX);
  });
  els.timelineCanvas.addEventListener("pointerup", (event) => {
    endTimelineDrag(event.clientX);
  });
  els.timelineCanvas.addEventListener("mousedown", (event) => {
    beginTimelineDrag(event.clientX);
  });
  window.addEventListener("mousemove", (event) => {
    moveTimelineDrag(event.clientX);
  });
  window.addEventListener("mouseup", (event) => {
    endTimelineDrag(event.clientX);
  });
  window.addEventListener("resize", () => renderTimeline());
}

function applyInitialQueryParams() {
  const params = new URLSearchParams(window.location.search);
  const path = params.get("path");
  const requestId = params.get("requestId");
  const logSearch = params.get("q");
  if (path) {
    els.pathInput.value = path;
  }
  if (requestId) {
    els.requestIdInput.value = requestId;
  }
  if (logSearch) {
    els.logSearchInput.value = logSearch;
  }
}

applyInitialQueryParams();
bindEvents();
loadRequests().catch((error) => setStatus(error.message, "error"));
