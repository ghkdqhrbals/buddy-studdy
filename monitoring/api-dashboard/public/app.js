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
};

const DEFAULT_QUERY = '{container=~".+"} |= "api_exchange"';

function nowMs() {
  return Date.now();
}

function ns(ms) {
  return (BigInt(ms) * 1_000_000n).toString();
}

function timeRange() {
  const endMs = nowMs();
  const startMs = endMs - Number(els.rangeSelect.value);
  return { startMs, endMs, startNs: ns(startMs), endNs: ns(endMs) };
}

async function lokiQueryRange(query, { startNs, endNs, limit = 500, direction = "backward" }) {
  const params = new URLSearchParams({
    query,
    start: startNs,
    end: endNs,
    limit: String(limit),
    direction,
  });
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
  const values = await lokiQueryRange(DEFAULT_QUERY, { ...range, limit: 800 });
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
  applyFilters();
  render();
  setStatus("Ready", "ready");
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
  els.rangeLabel.textContent = `${count} requests, newest first`;
}

function renderRows() {
  els.requestRows.innerHTML = "";
  if (state.filtered.length === 0) {
    els.requestRows.append(els.emptyTemplate.content.cloneNode(true));
    return;
  }
  for (const request of state.filtered) {
    els.requestRows.append(renderRequestRow(request));
    if (state.expandedRequestId === request.requestId) {
      els.requestRows.append(renderDetailRow(request));
    }
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
        loadRequests().catch((error) => setStatus(error.message, "error"));
      } else {
        applyFilters();
        render();
      }
    });
  }
  for (const element of [els.pathInput, els.requestIdInput]) {
    element.addEventListener("input", () => {
      applyFilters();
      render();
    });
  }
  els.refreshButton.addEventListener("click", () => {
    loadRequests().catch((error) => setStatus(error.message, "error"));
  });
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
