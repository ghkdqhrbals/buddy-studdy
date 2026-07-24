export const RUN_PROFILES = Object.freeze({
  smoke: { label: "Smoke", duration: "10s", vus: 1, maxVus: 10, targetRps: 5 },
  standard: { label: "Standard", duration: "60s", vus: 100, maxVus: 1000, targetRps: 1000 },
  diagnostic: { label: "Diagnostic", duration: "120s", vus: 500, maxVus: 1000, targetRps: 2000 },
  soak: { label: "Soak", duration: "15m", vus: 300, maxVus: 1000, targetRps: 0 },
  custom: { label: "Custom", duration: "60s", vus: 100, maxVus: 1000, targetRps: 0 },
});

export function formatDate(value) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

export function formatChartLabel(value) {
  if (!value) return "-";
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Seoul",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    }).formatToParts(new Date(value)).map((part) => [part.type, part.value]),
  );
  return `${parts.month}/${parts.day} ${parts.hour}:${parts.minute}`;
}

export function formatRate(value) {
  if (value === null || value === undefined || value === "") return "-";
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toFixed(number >= 100 ? 0 : 1)} RPS` : "-";
}

export function formatMilliseconds(value) {
  if (value === null || value === undefined || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return number >= 1000 ? `${(number / 1000).toFixed(2)} s` : `${number.toFixed(number >= 100 ? 0 : 1)} ms`;
}

export function formatPercent(value) {
  if (value === null || value === undefined || value === "") return "-";
  const number = Number(value);
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : "-";
}

export function parseObjectJson(value, label) {
  const text = String(value || "").trim();
  if (!text) return {};
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error(`${label} must be valid JSON.`);
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error(`${label} must be a JSON object.`);
  }
  return parsed;
}

export function selectLatestRun(runs = []) {
  return [...runs].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0] || null;
}

export function runScriptName(run, scripts = []) {
  return run.scriptName
    || scripts.find((entry) => entry.id === run.scriptId)?.name
    || "Deleted script";
}

export function buildChartPoints(runs = []) {
  return [...runs]
    .filter((run) => run.status === "completed" && run.summary)
    .sort((left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt))
    .slice(-12)
    .map((run) => ({
      id: run.id,
      label: formatChartLabel(run.startedAt || run.createdAt),
      rps: Number(run.summary.requestRate) || 0,
      p95: Number(run.summary.p95Ms) || 0,
    }));
}

export function chartSampleIndex(pointerX, sampleCount, plotStart, plotEnd) {
  if (sampleCount <= 0) return -1;
  if (sampleCount === 1 || plotEnd <= plotStart) return 0;
  const normalized = Math.min(1, Math.max(0, (pointerX - plotStart) / (plotEnd - plotStart)));
  return Math.round(normalized * (sampleCount - 1));
}

export function lineNumbersFor(code) {
  const count = Math.max(1, String(code ?? "").split("\n").length);
  return Array.from({ length: count }, (_, index) => index + 1).join("\n");
}

export function editorPosition(code, selectionStart) {
  const before = String(code ?? "").slice(0, selectionStart);
  const lines = before.split("\n");
  return { line: lines.length, column: lines.at(-1).length + 1 };
}

const JAVASCRIPT_KEYWORDS = new Set([
  "as", "async", "await", "break", "case", "catch", "const", "continue", "default",
  "else", "export", "false", "finally", "for", "from", "function", "if", "import",
  "in", "let", "new", "null", "of", "return", "switch", "throw", "true", "try",
  "typeof", "undefined", "var", "while",
]);
const K6_BUILTINS = new Set(["check", "fail", "group", "http", "sleep", "__ENV"]);

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function highlightJavaScript(code) {
  const source = String(code ?? "");
  let index = 0;
  let output = "";
  while (index < source.length) {
    const rest = source.slice(index);
    const lineComment = rest.match(/^\/\/[^\n]*/);
    const blockComment = rest.match(/^\/\*[\s\S]*?\*\//);
    const string = rest.match(/^(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)/);
    const number = rest.match(/^(?:\d+(?:\.\d+)?)/);
    const identifier = rest.match(/^[A-Za-z_$][\w$]*/);
    const match = lineComment || blockComment || string || number || identifier;
    if (!match) {
      output += escapeHtml(source[index]);
      index += 1;
      continue;
    }
    const token = match[0];
    let className = "";
    if (lineComment || blockComment) className = "syntax-comment";
    else if (string) className = "syntax-string";
    else if (number) className = "syntax-number";
    else if (JAVASCRIPT_KEYWORDS.has(token)) className = "syntax-keyword";
    else if (K6_BUILTINS.has(token)) className = "syntax-builtin";
    output += className
      ? `<span class="${className}">${escapeHtml(token)}</span>`
      : escapeHtml(token);
    index += token.length;
  }
  return `${output}\n`;
}

export function diagnosticMessage(diagnostic) {
  if (typeof diagnostic === "string") return diagnostic;
  const location = diagnostic?.line
    ? `Ln ${diagnostic.line}${diagnostic.column ? `:${diagnostic.column}` : ""} `
    : "";
  return `${location}${diagnostic?.message || "Unknown script error"}`;
}
