import { durationLabel, parseApiExchange, statusTone } from "./logs.js?v=2026070711";
import {
  classifyAuditEvent,
  filterAuditEntries,
  paginateAuditEntries,
  summarizeAuditEntries,
} from "./audit-model.js?v=2026072501";

const PAGE_SIZE = 50;
const API_EXCHANGE_QUERY = '{container=~".+"} |= "api_exchange "';

const state = {
  entries: [],
  filtered: [],
  page: 1,
};

const els = {
  range: document.querySelector("#auditRangeSelect"),
  event: document.querySelector("#auditEventSelect"),
  ip: document.querySelector("#auditIpInput"),
  search: document.querySelector("#auditSearchInput"),
  refresh: document.querySelector("#auditRefreshButton"),
  status: document.querySelector("#auditStatus"),
  total: document.querySelector("#auditTotal"),
  uniqueIps: document.querySelector("#auditUniqueIps"),
  mutations: document.querySelector("#auditMutations"),
  failures: document.querySelector("#auditFailures"),
  resultCount: document.querySelector("#auditResultCount"),
  rows: document.querySelector("#auditRows"),
  empty: document.querySelector("#auditEmpty"),
  previous: document.querySelector("#auditPreviousButton"),
  next: document.querySelector("#auditNextButton"),
  pageLabel: document.querySelector("#auditPageLabel"),
};

function nanoseconds(milliseconds) {
  return (BigInt(milliseconds) * 1_000_000n).toString();
}

function setStatus(message, tone) {
  els.status.textContent = message;
  els.status.dataset.tone = tone;
}

async function loadAuditEntries() {
  els.refresh.disabled = true;
  setStatus("Loading...", "loading");
  const end = Date.now();
  const start = end - Number(els.range.value);
  const params = new URLSearchParams({
    query: API_EXCHANGE_QUERY,
    start: nanoseconds(start),
    end: nanoseconds(end),
    limit: "2000",
    direction: "backward",
  });
  try {
    const response = await fetch(`/loki/api/v1/query_range?${params.toString()}`);
    if (!response.ok) throw new Error(`Loki query failed: ${response.status}`);
    const payload = await response.json();
    state.entries = (payload.data?.result ?? [])
      .flatMap((stream) => stream.values ?? [])
      .map((value) => {
        try {
          const entry = parseApiExchange(value);
          return entry ? { ...entry, eventType: classifyAuditEvent(entry) } : null;
        } catch (error) {
          console.warn("Failed to parse audit event", error);
          return null;
        }
      })
      .filter(Boolean)
      .sort((left, right) => BigInt(right.nanoseconds) > BigInt(left.nanoseconds) ? 1 : -1);
    state.page = 1;
    applyFilters();
    setStatus("Ready", "ready");
  } catch (error) {
    state.entries = [];
    applyFilters();
    setStatus(error.message || "Failed to load audit events", "error");
  } finally {
    els.refresh.disabled = false;
  }
}

function applyFilters() {
  state.filtered = filterAuditEntries(state.entries, {
    eventType: els.event.value,
    ip: els.ip.value,
    search: els.search.value,
  });
  const summary = summarizeAuditEntries(state.filtered);
  els.total.textContent = summary.total.toLocaleString();
  els.uniqueIps.textContent = summary.uniqueIps.toLocaleString();
  els.mutations.textContent = summary.mutations.toLocaleString();
  els.failures.textContent = summary.failures.toLocaleString();
  renderRows();
}

function eventLabel(type) {
  return {
    access: "Access",
    authentication: "Auth",
    mutation: "Mutation",
    failure: "Failure",
  }[type] || "Access";
}

function createCell(text, className = "") {
  const cell = document.createElement("td");
  if (className) cell.className = className;
  cell.textContent = text;
  cell.title = text;
  return cell;
}

function renderRows() {
  const page = paginateAuditEntries(state.filtered, state.page, PAGE_SIZE);
  state.page = page.page;
  els.resultCount.textContent = `${state.filtered.length.toLocaleString()} matching events`;
  els.pageLabel.textContent = `Page ${page.page} of ${page.totalPages}`;
  els.previous.disabled = page.page <= 1;
  els.next.disabled = page.page >= page.totalPages;
  els.empty.hidden = page.items.length > 0;
  els.rows.replaceChildren(...page.items.map((entry) => {
    const row = document.createElement("tr");
    const eventCell = document.createElement("td");
    const event = document.createElement("span");
    event.className = "audit-event";
    event.dataset.event = entry.eventType;
    event.textContent = eventLabel(entry.eventType);
    eventCell.append(event);
    const statusCell = document.createElement("td");
    const status = document.createElement("span");
    status.className = "status-pill";
    status.dataset.tone = statusTone(entry.status);
    status.textContent = entry.status || "-";
    statusCell.append(status);
    row.append(
      createCell(entry.time, "audit-time"),
      eventCell,
      createCell(entry.method, "audit-method"),
      createCell(entry.path, "audit-path"),
      statusCell,
      createCell(durationLabel(entry.durationMs), "audit-duration"),
      createCell(entry.clientIp || "-", "audit-ip"),
      createCell(entry.requestId || "-", "audit-request-id"),
    );
    return row;
  }));
}

els.refresh.addEventListener("click", loadAuditEntries);
els.range.addEventListener("change", loadAuditEntries);
for (const element of [els.event, els.ip, els.search]) {
  element.addEventListener(element.tagName === "SELECT" ? "change" : "input", () => {
    state.page = 1;
    applyFilters();
  });
}
els.previous.addEventListener("click", () => {
  state.page -= 1;
  renderRows();
});
els.next.addEventListener("click", () => {
  state.page += 1;
  renderRows();
});

void loadAuditEntries();
