const API_EXCHANGE_MARKER = "api_exchange ";
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
  const markerIndex = line.indexOf(API_EXCHANGE_MARKER);
  if (markerIndex < 0) return null;
  const rawJson = line.slice(markerIndex + API_EXCHANGE_MARKER.length).trim();
  const payload = JSON.parse(rawJson);
  return {
    nanoseconds,
    time: formatKstFromNs(nanoseconds),
    level: extractLevel(line),
    requestId: payload.requestId,
    clientIp: payload.clientIp,
    method: payload.request?.method ?? "-",
    path: payload.request?.path ?? "-",
    query: payload.request?.query ?? "",
    status: Number(payload.response?.status ?? 0),
    durationMs: Number(payload.response?.durationMs ?? 0),
    errorCode: payload.response?.body?.error?.code ?? "",
    errorReason: payload.response?.body?.error?.reason ?? "",
    request: payload.request ?? {},
    response: payload.response ?? {},
    raw: payload,
    rawLine: line,
  };
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
    message: compactLogLine(line),
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
  return line.match(LEVEL_PATTERN)?.[1] ?? "INFO";
}

function compactLogLine(line) {
  const apiExchangeIndex = line.indexOf(API_EXCHANGE_MARKER);
  if (apiExchangeIndex >= 0) {
    return line.slice(0, apiExchangeIndex).trim() + " api_exchange";
  }
  const firstLine = line.split("\n")[0];
  return firstLine.replace(/\s+/g, " ").trim();
}
