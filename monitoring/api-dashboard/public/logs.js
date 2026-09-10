const EXCHANGE_MARKER_PATTERN = /\b(?:api|mcp)_exchange /;
export const EXCHANGE_QUERY = '{app="buddystudy"} |~ "(api|mcp)_exchange "';
const API_ERROR_MARKER = "api_error ";
const LEVEL_PATTERN = /\s(TRACE|DEBUG|INFO|WARN|ERROR)\s(?:\[[^\]]*])?/;

export function formatKstFromNs(nanoseconds) {
  const date = new Date(Number(BigInt(nanoseconds) / 1_000_000n));
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(date).reduce((acc, part) => {
    acc[part.type] = part.value;
    return acc;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}.${String(date.getMilliseconds()).padStart(3, "0")}`;
}

export function lokiMetricTimestampToMs(timestamp) {
  return Math.round(Number(timestamp) * 1000);
}

export function durationLabel(value) {
  const duration = Number(value);
  if (!Number.isFinite(duration)) return "-";
  if (duration >= 1000) return `${(duration / 1000).toFixed(2)}s`;
  return `${duration.toFixed(duration >= 10 ? 1 : 2)}ms`;
}

export function parseApiExchange(value) {
  const [nanoseconds, line] = value;
  const marker = line.match(EXCHANGE_MARKER_PATTERN);
  if (!marker) return null;
  const rawJson = line.slice(marker.index + marker[0].length).trim();
  const payload = JSON.parse(rawJson);
  const request = normalizeRequest(payload);
  const response = normalizeResponse(payload);
  const protocol = payload.protocol ?? (marker[0] === "mcp_exchange " || request.method === "MCP" ? "mcp" : "rest");
  const error = response.body?.error ?? response.body?.structuredContent?.error;
  return {
    nanoseconds,
    time: formatKstFromNs(nanoseconds),
    level: extractLevel(line),
    requestId: payload.requestId,
    parentRequestId: payload.parentRequestId ?? "",
    sessionId: payload.sessionId ?? "",
    callId: payload.callId ?? "",
    protocol,
    transport: payload.transport ?? "http",
    operation: payload.operation ?? "",
    toolName: payload.toolName ?? "",
    resourceName: payload.resourceName ?? "",
    startedAt: payload.startedAt ?? "",
    completedAt: payload.completedAt ?? "",
    clientIp: payload.clientIp,
    userId: payload.userId ?? "-",
    method: request.method ?? "-",
    path: request.path ?? "-",
    query: request.query ?? "",
    status: Number(response.status ?? 0),
    durationMs: Number(response.durationMs ?? 0),
    isError: response.body?.isError === true || (protocol === "mcp" && Boolean(error)),
    errorCode: payload.errorCode ?? error?.code ?? "",
    errorReason: error?.reason ?? error?.message ?? "",
    request,
    response,
    raw: payload,
    rawLine: line,
  };
}

export function quoteLogql(value) {
  return JSON.stringify(String(value));
}

export function buildApiExchangeQuery({ method = "", statusPrefix = "", path = "", requestId = "", logSearch = "" } = {}) {
  const parts = [EXCHANGE_QUERY];
  if (method) parts.push(`|= ${quoteLogql(`"method":"${method}"`)}`);
  if (statusPrefix) parts.push(`|~ ${quoteLogql(`"status":${statusPrefix}[0-9][0-9]`)}`);
  for (const value of [path, requestId, logSearch]) {
    if (value) parts.push(`|= ${quoteLogql(value)}`);
  }
  return parts.join(" ");
}

export function buildRelatedLogQuery(request) {
  const ids = [...new Set([request.requestId, request.parentRequestId].filter(Boolean))];
  if (ids.length < 2) return `{app="buddystudy"} |= ${quoteLogql(ids[0] ?? "")}`;
  const pattern = ids.map((id) => String(id).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");
  return `{app="buddystudy"} |~ ${quoteLogql(pattern)}`;
}

export function exchangeMetadata(request) {
  const timeLabel = (value) => {
    const ms = Date.parse(value);
    return Number.isFinite(ms) ? `${formatKstFromNs((BigInt(ms) * 1_000_000n).toString())} KST` : value;
  };
  return [
    ["Protocol", request.protocol === "mcp" ? "MCP" : "REST"],
    ["Transport", request.transport === "voice" ? "Voice" : request.transport.toUpperCase()],
    ["Operation", request.operation],
    ["Tool", request.toolName],
    ["Resource", request.resourceName],
    ["Started", request.startedAt && timeLabel(request.startedAt)],
    ["Completed", request.completedAt && timeLabel(request.completedAt)],
    ["Duration (ms)", String(request.durationMs)],
    ["Parent request ID", request.parentRequestId],
    ["Session ID", request.sessionId],
    ["Call ID", request.callId],
    ["Error", [request.errorCode, request.errorReason].filter(Boolean).join(" · ")],
  ].filter(([, value]) => value !== "" && value != null);
}

export function groupApiExchanges(requests, { method = "", pathQuery = "" } = {}) {
  const byApi = new Map();
  const query = pathQuery.toLowerCase();
  for (const request of requests) {
    if (method && request.method !== method) continue;
    if (query && !request.path.toLowerCase().includes(query)) continue;
    const key = `${request.method} ${request.path}`;
    const group = byApi.get(key) ?? {
      method: request.method, path: request.path, count: 0, errors: 0, durations: [], latestNs: request.nanoseconds,
    };
    group.count += 1;
    const failed = request.protocol === "mcp" ? request.isError || request.status >= 400 : request.status >= 500;
    group.errors += failed ? 1 : 0;
    group.durations.push(request.durationMs);
    if (BigInt(request.nanoseconds) > BigInt(group.latestNs)) group.latestNs = request.nanoseconds;
    byApi.set(key, group);
  }
  return [...byApi.values()].map((group) => ({
    ...group,
    p50: percentile(group.durations, 50),
    p90: percentile(group.durations, 90),
    p95: percentile(group.durations, 95),
    p99: percentile(group.durations, 99),
    max: Math.max(...group.durations),
  })).sort((a, b) => (b.p99 ?? 0) - (a.p99 ?? 0));
}

export function parseApiError(value) {
  const [nanoseconds, line] = value;
  const markerIndex = line.indexOf(API_ERROR_MARKER);
  if (markerIndex < 0) return null;
  const beforeStack = line.includes("\n\n") ? line.slice(0, line.indexOf("\n\n")) : line;
  const stack = line.includes("\n\n") ? line.slice(line.indexOf("\n\n") + 2).trim() : "";
  const summary = beforeStack.slice(markerIndex + API_ERROR_MARKER.length);
  const fields = Object.fromEntries(
    [...summary.matchAll(/(\w+)=((?:(?!\s\w+=).)+)/g)].map((match) => [match[1], match[2].trim()]),
  );
  return {
    nanoseconds,
    time: formatKstFromNs(nanoseconds),
    level: extractLevel(line),
    requestId: fields.requestId ?? "",
    clientIp: fields.clientIp ?? "",
    method: fields.method ?? "",
    path: fields.path ?? "",
    status: Number(fields.status ?? 0),
    code: fields.code ?? "",
    message: fields.message ?? "",
    exceptionType: fields.exceptionType ?? "",
    exceptionMessage: fields.exceptionMessage ?? "",
    rootCauseType: fields.rootCauseType ?? "",
    rootCauseMessage: fields.rootCauseMessage ?? "",
    origin: fields.origin ?? "",
    stack,
    rawLine: line,
  };
}

export function parseRelatedLog(value) {
  const [nanoseconds, line] = value;
  return {
    nanoseconds,
    time: formatKstFromNs(nanoseconds),
    level: extractLevel(line),
    summary: summarizeRelatedLog(line),
    rawLine: line,
  };
}

export function percentile(values, p) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (sorted.length === 0) return null;
  const index = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
}

export function statusTone(status) {
  if (status >= 500) return "danger";
  if (status >= 400) return "warn";
  if (status >= 300) return "redirect";
  if (status >= 200) return "ok";
  return "muted";
}

export function safeJson(value) {
  if (value === "" || value == null) return "";
  return JSON.stringify(value, null, 2);
}

function extractLevel(line) {
  return line.match(LEVEL_PATTERN)?.[1] ?? "UNKNOWN";
}

function compactLogLine(line) {
  const marker = line.match(EXCHANGE_MARKER_PATTERN);
  if (marker) {
    const prefix = line.slice(0, marker.index).trim();
    const rawJson = line.slice(marker.index + marker[0].length).trim();
    if (!rawJson) return `${prefix} ${marker[0].trim()}`;
    try {
      const payload = JSON.parse(rawJson);
      const request = normalizeRequest(payload);
      const response = normalizeResponse(payload);
      return [
        `${prefix} ${marker[0].trim()}`,
        request.method,
        request.path,
        `status=${response.status ?? "-"}`,
        `durationMs=${response.durationMs ?? "-"}`,
        `requestId=${payload.requestId ?? "-"}`,
      ].filter(Boolean).join(" ");
    } catch {
      return `${prefix} ${marker[0].trim()} ${rawJson}`;
    }
  }
  const firstLine = line.split("\n")[0];
  return firstLine.replace(/\s+/g, " ").trim();
}

function summarizeRelatedLog(line) {
  const marker = line.match(EXCHANGE_MARKER_PATTERN);
  if (marker) {
    try {
      const payload = JSON.parse(line.slice(marker.index + marker[0].length).trim());
      const request = normalizeRequest(payload);
      const response = normalizeResponse(payload);
      return [
        marker[0] === "mcp_exchange " || payload.protocol === "mcp" || request.method === "MCP" ? "MCP exchange" : "API exchange",
        request.method,
        request.path,
        `status ${response.status ?? "-"}`,
        durationLabel(response.durationMs),
      ].filter(Boolean).join(" · ");
    } catch {
      return marker[0] === "mcp_exchange " ? "MCP exchange" : "API exchange";
    }
  }

  const errorIndex = line.indexOf(API_ERROR_MARKER);
  if (errorIndex >= 0) {
    const summary = line.slice(errorIndex + API_ERROR_MARKER.length).split("\n")[0];
    const fields = Object.fromEntries(
      [...summary.matchAll(/(\w+)=((?:(?!\s\w+=).)+)/g)].map((match) => [match[1], match[2].trim()]),
    );
    return [
      "API error",
      fields.method,
      fields.path,
      fields.code,
      fields.status ? `status ${fields.status}` : "",
    ].filter(Boolean).join(" · ");
  }

  const logger = line.match(/\]\s+([\w.$]+)\s+:\s/)?.[1];
  return logger ? `Application log · ${logger}` : "Application log";
}

function normalizeRequest(payload) {
  if (payload.request && typeof payload.request === "object") {
    return payload.request;
  }
  return {
    method: payload.method,
    path: payload.path,
    query: payload.query ?? "",
    headers: payload.requestHeaders ?? {},
    body: payload.requestBody ?? "",
  };
}

function normalizeResponse(payload) {
  if (payload.response && typeof payload.response === "object") {
    return payload.response;
  }
  return {
    status: payload.status,
    durationMs: payload.durationMs,
    headers: payload.responseHeaders ?? {},
    body: payload.responseBody ?? "",
  };
}
