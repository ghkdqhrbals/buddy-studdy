import {
  durationLabel,
  parseApiExchange,
  percentile,
  statusTone,
} from "./logs.js?v=2026070606";

const state = {
  requests: [],
  groups: [],
};

const els = {
  rangeSelect: document.querySelector("#rangeSelect"),
  methodSelect: document.querySelector("#methodSelect"),
  pathInput: document.querySelector("#pathInput"),
  refreshButton: document.querySelector("#refreshButton"),
  performanceRows: document.querySelector("#performanceRows"),
  statusMessage: document.querySelector("#statusMessage"),
  rangeLabel: document.querySelector("#rangeLabel"),
  apiGroupCount: document.querySelector("#apiGroupCount"),
  totalCount: document.querySelector("#totalCount"),
  errorRate: document.querySelector("#errorRate"),
  worstP99: document.querySelector("#worstP99"),
  emptyTemplate: document.querySelector("#emptyTemplate"),
};

const DEFAULT_QUERY = '{container=~".+"} |= "api_exchange"';

function ns(ms) {
  return (BigInt(ms) * 1_000_000n).toString();
}

function timeRange() {
  const endMs = Date.now();
  const startMs = endMs - Number(els.rangeSelect.value);
  return { startNs: ns(startMs), endNs: ns(endMs) };
}

async function lokiQueryRange(query, { startNs, endNs, limit = 1000 }) {
  const params = new URLSearchParams({
    query,
    start: startNs,
    end: endNs,
    limit: String(limit),
    direction: "backward",
  });
  const response = await fetch(`/loki/api/v1/query_range?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Loki query failed: ${response.status}`);
  }
  const payload = await response.json();
  return (payload.data?.result ?? []).flatMap((stream) => stream.values ?? []);
}

async function loadPerformance() {
  setStatus("Loading API performance...", "loading");
  const values = await lokiQueryRange(DEFAULT_QUERY, { ...timeRange(), limit: 1200 });
  state.requests = values
    .map((value) => {
      try {
        return parseApiExchange(value);
      } catch (error) {
        console.warn("Failed to parse api_exchange", error, value);
        return null;
      }
    })
    .filter(Boolean);
  applyFilters();
  render();
  setStatus("Ready", "ready");
}

function applyFilters() {
  const method = els.methodSelect.value;
  const pathQuery = els.pathInput.value.trim().toLowerCase();
  const filtered = state.requests.filter((request) => {
    if (method && request.method !== method) return false;
    if (pathQuery && !request.path.toLowerCase().includes(pathQuery)) return false;
    return true;
  });
  const byApi = new Map();
  for (const request of filtered) {
    const key = `${request.method} ${request.path}`;
    const group = byApi.get(key) ?? {
      method: request.method,
      path: request.path,
      count: 0,
      errors: 0,
      durations: [],
      latestNs: request.nanoseconds,
    };
    group.count += 1;
    group.errors += request.status >= 500 ? 1 : 0;
    group.durations.push(request.durationMs);
    if (BigInt(request.nanoseconds) > BigInt(group.latestNs)) {
      group.latestNs = request.nanoseconds;
    }
    byApi.set(key, group);
  }
  state.groups = [...byApi.values()]
    .map((group) => ({
      ...group,
      p50: percentile(group.durations, 50),
      p90: percentile(group.durations, 90),
      p95: percentile(group.durations, 95),
      p99: percentile(group.durations, 99),
      max: Math.max(...group.durations),
    }))
    .sort((a, b) => (b.p99 ?? 0) - (a.p99 ?? 0));
}

function render() {
  renderSummary();
  renderRows();
}

function renderSummary() {
  const total = state.groups.reduce((sum, group) => sum + group.count, 0);
  const errors = state.groups.reduce((sum, group) => sum + group.errors, 0);
  const worst = state.groups.length ? Math.max(...state.groups.map((group) => group.p99 ?? 0)) : null;
  els.apiGroupCount.textContent = String(state.groups.length);
  els.totalCount.textContent = String(total);
  els.errorRate.textContent = total === 0 ? "-" : `${((errors / total) * 100).toFixed(1)}%`;
  els.worstP99.textContent = worst == null ? "-" : durationLabel(worst);
  els.rangeLabel.textContent = `${state.groups.length} API groups, sorted by p99`;
}

function renderRows() {
  els.performanceRows.innerHTML = "";
  if (state.groups.length === 0) {
    els.performanceRows.append(els.emptyTemplate.content.cloneNode(true));
    return;
  }
  for (const group of state.groups) {
    const row = document.createElement("a");
    row.className = "performance-row data-row performance-link";
    row.href = `/?path=${encodeURIComponent(group.path)}`;
    row.setAttribute("role", "row");
    row.innerHTML = `
      <div role="cell"><span class="method-badge method-${escapeHtml(group.method.toLowerCase())}">${escapeHtml(group.method)}</span></div>
      <div class="path-cell" role="cell" title="${escapeHtml(group.path)}">${escapeHtml(group.path)}</div>
      <div role="cell">${group.count}</div>
      <div role="cell"><span class="status-badge ${statusTone(group.errors > 0 ? 500 : 200)}">${group.errors}</span></div>
      <div role="cell">${durationLabel(group.p50)}</div>
      <div role="cell">${durationLabel(group.p90)}</div>
      <div role="cell">${durationLabel(group.p95)}</div>
      <div role="cell">${durationLabel(group.p99)}</div>
      <div role="cell">${durationLabel(group.max)}</div>
    `;
    els.performanceRows.append(row);
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
  for (const element of [els.rangeSelect, els.methodSelect]) {
    element.addEventListener("change", () => {
      if (element === els.rangeSelect) {
        loadPerformance().catch((error) => setStatus(error.message, "error"));
      } else {
        applyFilters();
        render();
      }
    });
  }
  els.pathInput.addEventListener("input", () => {
    applyFilters();
    render();
  });
  els.refreshButton.addEventListener("click", () => {
    loadPerformance().catch((error) => setStatus(error.message, "error"));
  });
}

bindEvents();
loadPerformance().catch((error) => setStatus(error.message, "error"));
