import {
  durationLabel,
  parseApiError,
  parseApiExchange,
  parseRelatedLog,
  percentile,
  safeJson,
  statusTone,
} from "./logs.js";

const state = {
  requests: [],
  filtered: [],
  expandedRequestId: "",
  detailCache: new Map(),
  loadingDetails: new Set(),
  selectedRange: null,
  timeline: [],
  drag: null,
  visibleLimit: 100,
};

const els = {
  rangeSelect: document.querySelector("#rangeSelect"),
  methodSelect: document.querySelector("#methodSelect"),
  statusSelect: document.querySelector("#statusSelect"),
  pathInput: document.querySelector("#pathInput"),
  requestIdInput: document.querySelector("#requestIdInput"),
  refreshButton: document.querySelector("#refreshButton"),
  requestRows: document.querySelector("#requestRows"),
  statusMessage: document.querySelector("#statusMessage"),
  rangeLabel: document.querySelector("#rangeLabel"),
  totalCount: document.querySelector("#totalCount"),
  errorCount: document.querySelector("#errorCount"),
  p95Latency: document.querySelector("#p95Latency"),
  slowestLatency: document.querySelector("#slowestLatency"),
  emptyTemplate: document.querySelector("#emptyTemplate"),
  timelineCanvas: document.querySelector("#timelineCanvas"),
  timelineSelection: document.querySelector("#timelineSelection"),
  timelineRangeLabel: document.querySelector("#timelineRangeLabel"),
  resetTimelineButton: document.querySelector("#resetTimelineButton"),
};

const DEFAULT_QUERY = '{container=~".+"} |= "api_exchange"';
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
  const startMs = endMs - Number(els.rangeSelect.value);
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
  setStatus("Loading API requests...", "loading");
  const range = timeRange();
  const [timelineValues, values] = await Promise.all([
    loadTimeline(range),
    lokiQueryRange(DEFAULT_QUERY, { ...range, limit: 500 }),
  ]);
  state.timeline = timelineValues;
  state.requests = values
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
  state.detailCache.clear();
  state.expandedRequestId = "";
  state.visibleLimit = 100;
  applyFilters();
  render();
  setStatus("Ready", "ready");
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
    ms: Number(BigInt(nanoseconds) / 1_000_000n),
    count: Number(value),
  }));
}

function applyFilters() {
  const method = els.methodSelect.value;
  const statusPrefix = els.statusSelect.value;
  const path = els.pathInput.value.trim().toLowerCase();
  const requestId = els.requestIdInput.value.trim().toLowerCase();
  state.filtered = state.requests.filter((request) => {
    if (method && request.method !== method) return false;
    if (statusPrefix && !String(request.status).startsWith(statusPrefix)) return false;
    if (path && !request.path.toLowerCase().includes(path)) return false;
    if (requestId && !request.requestId.toLowerCase().includes(requestId)) return false;
    return true;
  });
}

function render() {
  renderSummary();
  renderRangeLabel();
  renderTimeline();
  renderRows();
}

function renderSummary() {
  const durations = state.filtered.map((request) => request.durationMs);
  const slowest = durations.length ? Math.max(...durations) : null;
  els.totalCount.textContent = String(state.filtered.length);
  els.errorCount.textContent = String(state.filtered.filter((request) => request.status >= 500).length);
  els.p95Latency.textContent = percentile(durations, 95) == null ? "-" : durationLabel(percentile(durations, 95));
  els.slowestLatency.textContent = slowest == null ? "-" : durationLabel(slowest);
}

function renderRangeLabel() {
  const count = state.filtered.length;
  const range = timeRange();
  const suffix = state.requests.length >= 500 ? "latest 500 loaded" : "newest first";
  els.rangeLabel.textContent = `${count} requests, ${suffix}`;
  els.timelineRangeLabel.textContent = `${formatKstShort(range.startMs)} - ${formatKstShort(range.endMs)} · drag to zoom`;
  els.resetTimelineButton.disabled = !state.selectedRange;
}

function renderRows() {
  els.requestRows.innerHTML = "";
  if (state.filtered.length === 0) {
    els.requestRows.append(els.emptyTemplate.content.cloneNode(true));
    return;
  }
  for (const request of state.filtered.slice(0, state.visibleLimit)) {
    els.requestRows.append(renderRequestRow(request));
    if (state.expandedRequestId === request.requestId) {
      els.requestRows.append(renderDetailRow(request));
    }
  }
  if (state.filtered.length > state.visibleLimit) {
    els.requestRows.append(renderLoadMoreRow());
  }
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

function renderLoadMoreRow() {
  const wrapper = document.createElement("div");
  wrapper.className = "load-more-row";
  const remaining = state.filtered.length - state.visibleLimit;
  wrapper.innerHTML = `<button type="button">Load 100 more (${remaining} remaining)</button>`;
  wrapper.querySelector("button")?.addEventListener("click", () => {
    state.visibleLimit += 100;
    renderRows();
  });
  return wrapper;
}

function renderDetailRow(request) {
  const wrapper = document.createElement("section");
  wrapper.className = "detail-row";
  wrapper.setAttribute("aria-label", `Request details for ${request.requestId}`);
  const details = state.detailCache.get(request.requestId);
  if (!details) {
    wrapper.innerHTML = `<div class="detail-loading">Loading connected logs...</div>`;
    return wrapper;
  }

  const error = details.errors[0];
  wrapper.innerHTML = `
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
  wrapper.querySelector("[data-copy]")?.addEventListener("click", (event) => {
    event.stopPropagation();
    navigator.clipboard?.writeText(request.requestId);
  });
  return wrapper;
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
  renderRows();
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
    renderRows();
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
  const points = state.timeline;
  const max = Math.max(1, ...points.map((point) => point.count));
  const range = timeRange();

  context.strokeStyle = "#263244";
  context.lineWidth = 1;
  context.beginPath();
  for (let i = 0; i <= 4; i += 1) {
    const y = padding.top + (height * i) / 4;
    context.moveTo(padding.left, y);
    context.lineTo(padding.left + width, y);
  }
  context.stroke();

  context.fillStyle = "#98a6b8";
  context.font = "11px Inter, system-ui, sans-serif";
  context.textAlign = "right";
  context.textBaseline = "middle";
  for (let i = 0; i <= 4; i += 1) {
    const value = Math.round(max - (max * i) / 4);
    const y = padding.top + (height * i) / 4;
    context.fillText(String(value), padding.left - 10, y);
  }

  if (points.length > 0) {
    const barWidth = Math.max(2, width / points.length - 1);
    for (const point of points) {
      const x = padding.left + ((point.ms - range.startMs) / Math.max(1, range.endMs - range.startMs)) * width;
      const barHeight = Math.max(1, (point.count / max) * height);
      context.fillStyle = point.count > 0 ? "#60a5fa" : "#1d2735";
      context.fillRect(x, padding.top + height - barHeight, barWidth, barHeight);
    }
  }

  context.fillStyle = "#98a6b8";
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
        state.selectedRange = null;
        loadRequests().catch((error) => setStatus(error.message, "error"));
      } else {
        state.visibleLimit = 100;
        applyFilters();
        render();
      }
    });
  }
  for (const element of [els.pathInput, els.requestIdInput]) {
    element.addEventListener("input", () => {
      state.visibleLimit = 100;
      applyFilters();
      render();
    });
  }
  els.refreshButton.addEventListener("click", () => {
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  els.resetTimelineButton.addEventListener("click", () => {
    state.selectedRange = null;
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  els.timelineCanvas.addEventListener("pointerdown", (event) => {
    els.timelineCanvas.setPointerCapture(event.pointerId);
    state.drag = { startX: event.clientX, currentX: event.clientX };
    updateSelectionOverlay();
  });
  els.timelineCanvas.addEventListener("pointermove", (event) => {
    if (!state.drag) return;
    state.drag.currentX = event.clientX;
    updateSelectionOverlay();
  });
  els.timelineCanvas.addEventListener("pointerup", (event) => {
    if (!state.drag) return;
    state.drag.currentX = event.clientX;
    const startMs = timelineXToMs(state.drag.startX);
    const endMs = timelineXToMs(state.drag.currentX);
    state.drag = null;
    els.timelineSelection.hidden = true;
    if (Math.abs(endMs - startMs) < 10_000) {
      renderTimeline();
      return;
    }
    state.selectedRange = {
      startMs: Math.min(startMs, endMs),
      endMs: Math.max(startMs, endMs),
    };
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
  window.addEventListener("resize", () => renderTimeline());
}

function applyInitialQueryParams() {
  const params = new URLSearchParams(window.location.search);
  const path = params.get("path");
  const requestId = params.get("requestId");
  if (path) {
    els.pathInput.value = path;
  }
  if (requestId) {
    els.requestIdInput.value = requestId;
  }
}

applyInitialQueryParams();
bindEvents();
loadRequests().catch((error) => setStatus(error.message, "error"));
