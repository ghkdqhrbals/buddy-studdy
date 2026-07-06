#!/usr/bin/env node

import {
  durationLabel,
  parseApiError,
  parseApiExchange,
  parseRelatedLog,
  safeJson,
  statusTone,
} from "../public/logs.js";

const DEFAULT_LIMIT = 20;
const DEFAULT_RANGE_MS = 60 * 60 * 1000;
const DEFAULT_DASHBOARD_URL = "https://grafana.lowfidev.cloud";
const API_EXCHANGE_QUERY = '{container=~".+"} |= "api_exchange"';

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }

  const range = parseRange(options);
  const lokiBaseUrl = normalizeBaseUrl(options.lokiUrl ?? process.env.LOKI_BASE_URL ?? "http://127.0.0.1:3100");
  const dashboardUrl = normalizeBaseUrl(options.dashboardUrl ?? process.env.MONITORING_DASHBOARD_URL ?? DEFAULT_DASHBOARD_URL);
  const limit = Math.max(1, Number(options.limit ?? DEFAULT_LIMIT));
  const sort = options.sort === "asc" ? "asc" : "desc";
  const query = buildApiExchangeQuery(options);
  const values = await lokiQueryRange(lokiBaseUrl, query, {
    startNs: ns(range.startMs),
    endNs: ns(range.endMs),
    limit,
    direction: sort === "desc" ? "backward" : "forward",
  });
  const requests = values
    .map((value) => {
      try {
        return parseApiExchange(value);
      } catch {
        return null;
      }
    })
    .filter(Boolean)
    .sort((a, b) => compareByTime(a, b, sort))
    .slice(0, limit);

  const selected = requests.find((request) => request.requestId === options.requestId) ?? requests[0] ?? null;
  const detail = selected ? await loadDetails(lokiBaseUrl, selected) : null;
  console.log(renderSlackResponse({ options, range, sort, dashboardUrl, requests, selected, detail }));
}

function parseArgs(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === "--help" || arg === "-h") {
      options.help = true;
      continue;
    }
    if (!arg.startsWith("--")) {
      throw new Error(`Unknown positional argument: ${arg}`);
    }
    const key = arg.slice(2);
    const next = args[i + 1];
    if (!next || next.startsWith("--")) {
      options[key] = "true";
      continue;
    }
    options[key] = next;
    i += 1;
  }
  return options;
}

function parseRange(options) {
  const now = Date.now();
  const from = parseTime(options.from);
  const to = parseTime(options.to);
  if (Number.isFinite(from) && Number.isFinite(to) && from < to) {
    return { startMs: from, endMs: to };
  }
  const rangeMs = Number(options.rangeMs ?? DEFAULT_RANGE_MS);
  const duration = Number.isFinite(rangeMs) && rangeMs > 0 ? rangeMs : DEFAULT_RANGE_MS;
  return { startMs: now - duration, endMs: now };
}

function parseTime(value) {
  if (!value) return NaN;
  const number = Number(value);
  if (Number.isFinite(number)) return number;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : NaN;
}

function ns(ms) {
  return (BigInt(ms) * 1_000_000n).toString();
}

function normalizeBaseUrl(value) {
  return String(value).replace(/\/+$/, "");
}

async function lokiQueryRange(baseUrl, query, { startNs, endNs, limit, direction }) {
  const params = new URLSearchParams({
    query,
    start: startNs,
    end: endNs,
    limit: String(limit),
    direction,
  });
  const headers = {};
  if (process.env.MONITORING_BASIC_AUTH) {
    headers.Authorization = `Basic ${process.env.MONITORING_BASIC_AUTH}`;
  }
  const response = await fetch(`${baseUrl}/loki/api/v1/query_range?${params.toString()}`, { headers });
  if (!response.ok) {
    throw new Error(`Loki query failed: ${response.status} ${await response.text()}`);
  }
  const payload = await response.json();
  return (payload.data?.result ?? []).flatMap((stream) => stream.values ?? []);
}

function buildApiExchangeQuery(options) {
  const parts = [API_EXCHANGE_QUERY];
  if (options.method) parts.push(`|= ${quoteLogql(`"method":"${String(options.method).toUpperCase()}"`)}`);
  if (options.status) parts.push(`|~ ${quoteLogql(`"status":${options.status}[0-9][0-9]`)}`);
  if (options.path) parts.push(`|= ${quoteLogql(options.path)}`);
  if (options.requestId) parts.push(`|= ${quoteLogql(options.requestId)}`);
  if (options.q) parts.push(`|= ${quoteLogql(options.q)}`);
  return parts.join(" ");
}

function quoteLogql(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function compareByTime(a, b, sort) {
  const delta = Number(BigInt(a.nanoseconds) - BigInt(b.nanoseconds));
  return sort === "desc" ? -delta : delta;
}

async function loadDetails(lokiBaseUrl, request) {
  const requestMs = Number(BigInt(request.nanoseconds) / 1_000_000n);
  const values = await lokiQueryRange(lokiBaseUrl, `{container=~".+"} |= "${request.requestId}"`, {
    startNs: ns(requestMs - 10 * 60 * 1000),
    endNs: ns(requestMs + 10 * 60 * 1000),
    limit: 200,
    direction: "forward",
  });
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

function renderSlackResponse({ options, range, sort, dashboardUrl, requests, selected, detail }) {
  const url = dashboardLink({ dashboardUrl, options, range, sort, selected });
  const rows = requests.slice(0, 5).map((request) => {
    const tone = statusTone(request.status);
    return `- ${request.time} ${request.method} ${request.path} status=${request.status || "-"} duration=${durationLabel(request.durationMs)} ${tone} requestId=${request.requestId}`;
  });
  const error = detail?.errors?.[0];
  const connectedLogs = detail?.logs?.filter((log) => log.message !== selected?.rawLine).slice(0, 6) ?? [];
  return [
    "*BuddyStudy API log search*",
    `Range: ${formatKst(range.startMs)} - ${formatKst(range.endMs)}`,
    `Sort: ${sort === "desc" ? "newest first" : "oldest first"}`,
    `Filters: ${filterSummary(options)}`,
    `Grafana: ${url}`,
    "",
    selected ? `*Selected*: ${selected.method} ${selected.path} status=${selected.status || "-"} duration=${durationLabel(selected.durationMs)} requestId=${selected.requestId}` : "*Selected*: none",
    error ? `*Error*: ${error.code || "-"} ${error.message || ""}` : "*Error*: none",
    error?.stack ? `\`\`\`\n${truncate(error.stack, 1800)}\n\`\`\`` : "",
    "*Recent matches*",
    rows.length ? rows.join("\n") : "- no matching api_exchange logs",
    connectedLogs.length ? "\n*Connected logs*\n" + connectedLogs.map((log) => `- ${log.time} ${log.level} ${truncate(log.message, 220)}`).join("\n") : "",
    selected ? `\n*Request*\n\`\`\`json\n${truncate(safeJson(selected.request), 1800)}\n\`\`\`` : "",
    selected ? `*Response*\n\`\`\`json\n${truncate(safeJson(selected.response), 1800)}\n\`\`\`` : "",
  ].filter(Boolean).join("\n");
}

function dashboardLink({ dashboardUrl, options, range, sort, selected }) {
  const url = new URL(dashboardUrl);
  url.pathname = "/";
  url.searchParams.set("from", String(range.startMs));
  url.searchParams.set("to", String(range.endMs));
  if (options.path) url.searchParams.set("path", options.path);
  if (options.requestId ?? selected?.requestId) url.searchParams.set("requestId", options.requestId ?? selected.requestId);
  if (options.q) url.searchParams.set("q", options.q);
  if (options.method) url.searchParams.set("method", options.method);
  if (options.status) url.searchParams.set("status", options.status);
  url.searchParams.set("sort", sort);
  return url.toString();
}

function filterSummary(options) {
  return [
    `method=${options.method ?? "*"}`,
    `status=${options.status ? `${options.status}xx` : "*"}`,
    `path=${options.path ?? "*"}`,
    `requestId=${options.requestId ?? "*"}`,
    `q=${options.q ?? "*"}`,
  ].join(" ");
}

function formatKst(ms) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date(ms));
}

function truncate(value, maxLength) {
  const text = String(value ?? "");
  if (text.length <= maxLength) return text;
  return `${text.slice(0, maxLength - 1)}…`;
}

function printHelp() {
  console.log(`Usage:
  npm run codex:log-search -- --requestId <uuid>
  npm run codex:log-search -- --path /api/v1/devices/register --status 5 --rangeMs 900000
  npm run codex:log-search -- --q "NoClassDefFoundError" --from 2026-07-05T12:00:00Z --to 2026-07-05T13:00:00Z

Environment:
  LOKI_BASE_URL              Loki base URL. Default: http://127.0.0.1:3100
  MONITORING_DASHBOARD_URL   API Logs dashboard URL. Default: https://grafana.lowfidev.cloud
  MONITORING_BASIC_AUTH      Optional base64 "user:password" for Basic Auth.
`);
}
