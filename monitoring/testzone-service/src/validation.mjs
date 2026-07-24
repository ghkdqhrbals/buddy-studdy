const DURATION_PATTERN = /^(\d+)(ms|s|m|h)$/;
const K6_IMPORT_PATTERN = /from\s+["']([^"']+)["']/g;
const URL_PATTERN = /https?:\/\/[^\s"'`)}\]]+/g;
const VU_OPTION_PATTERN = /\b(?:vus|preAllocatedVUs|maxVUs)\s*:\s*(\d+)/g;
const DURATION_OPTION_PATTERN = /\b(?:duration|maxDuration)\s*:\s*["'](\d+(?:ms|s|m|h))["']/g;
const RATE_OPTION_PATTERN = /\brate\s*:\s*(\d+)/g;
const OPTIONS_EXPORT_PATTERN = /export\s+const\s+options\s*=/;

export class ValidationError extends Error {
  constructor(message, details = []) {
    super(message);
    this.name = "ValidationError";
    this.details = details;
  }
}

function diagnostic(source, message, index = 0) {
  const before = source.slice(0, Math.max(0, index));
  const lines = before.split("\n");
  return {
    severity: "error",
    message,
    line: lines.length,
    column: (lines.at(-1)?.length || 0) + 1,
  };
}

export function durationToSeconds(value) {
  const match = String(value ?? "").trim().match(DURATION_PATTERN);
  if (!match) return null;
  const amount = Number(match[1]);
  const multiplier = { ms: 0.001, s: 1, m: 60, h: 3600 }[match[2]];
  return amount * multiplier;
}

export function normalizeBaseUrl(value) {
  let url;
  try {
    url = new URL(String(value ?? ""));
  } catch {
    throw new ValidationError("Target URL must be a valid HTTP or HTTPS URL.");
  }
  if (!["http:", "https:"].includes(url.protocol)) {
    throw new ValidationError("Target URL must use HTTP or HTTPS.");
  }
  url.pathname = url.pathname.replace(/\/+$/, "");
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}

export function validateTargetHost(baseUrl, allowedHosts = []) {
  const normalized = normalizeBaseUrl(baseUrl);
  if (!allowedHosts.length) return normalized;
  const hostname = new URL(normalized).hostname.toLowerCase();
  const allowed = allowedHosts.some((entry) => hostname === entry || hostname.endsWith(`.${entry}`));
  if (!allowed) {
    throw new ValidationError(`Target host ${hostname} is not in the TestZone allowlist.`);
  }
  return normalized;
}

export function validateScript(code, options = {}) {
  const source = String(code ?? "");
  const errors = [];
  const add = (message, index = 0) => errors.push(diagnostic(source, message, index));
  const maxVus = Number(options.maxVus ?? 1000);
  const maxDurationSeconds = Number(options.maxDurationSeconds ?? 3600);
  const targetBaseUrl = options.targetBaseUrl ? normalizeBaseUrl(options.targetBaseUrl) : null;

  if (!source.trim()) add("Script is empty.");
  if (source.length > 250_000) add("Script exceeds the 250 KB limit.");
  if (!/export\s+default\s+function/.test(source)) {
    add("Script must export a default k6 function.");
  }
  if (!OPTIONS_EXPORT_PATTERN.test(source)) {
    add("Script must export k6 load settings with `export const options = ...`.");
  }

  for (const match of source.matchAll(K6_IMPORT_PATTERN)) {
    const specifier = match[1];
    if (!specifier.startsWith("k6") && !specifier.startsWith("./")) {
      add(`Import ${specifier} is not allowed. Use k6 modules or project-local files.`, match.index);
    }
    if (/^https?:/i.test(specifier)) {
      add("Remote JavaScript imports are not allowed.", match.index);
    }
  }

  const vuMatches = [...source.matchAll(VU_OPTION_PATTERN)];
  for (const match of vuMatches) {
    if (Number(match[1]) > maxVus) {
      add(`Script requests ${match[1]} VUs; the TestZone maximum is ${maxVus}.`, match.index);
    }
  }

  const durationMatches = [...source.matchAll(DURATION_OPTION_PATTERN)];
  const durationSeconds = durationMatches.reduce(
    (total, match) => total + (durationToSeconds(match[1]) || 0),
    0,
  );
  if (OPTIONS_EXPORT_PATTERN.test(source) && !durationMatches.length) {
    add("Script options must include a bounded duration or maxDuration.");
  }
  if (durationMatches.length && durationSeconds <= 0) {
    add("Script duration must be greater than zero.");
  }
  if (durationSeconds > maxDurationSeconds) {
    add(`Script duration exceeds the ${maxDurationSeconds} second limit.`);
  }

  const maxTargetRps = Number(options.maxTargetRps ?? 3000);
  const rateMatches = [...source.matchAll(RATE_OPTION_PATTERN)];
  for (const match of rateMatches) {
    if (Number(match[1]) > maxTargetRps) {
      add(`Script requests ${match[1]} RPS; the TestZone maximum is ${maxTargetRps}.`, match.index);
    }
  }

  if (targetBaseUrl) {
    const targetHost = new URL(targetBaseUrl).hostname;
    for (const match of source.matchAll(new RegExp(URL_PATTERN.source, "g"))) {
      const literal = match[0];
      const hostname = new URL(literal).hostname;
      if (hostname !== targetHost && !literal.includes("127.0.0.1") && !literal.includes("localhost")) {
        add(`Script contains a URL outside the selected target: ${hostname}. Use __ENV.BASE_URL for requests.`, match.index);
      }
    }
  }

  if (errors.length) throw new ValidationError("Fix the script diagnostics before running the test.", errors);
  const requestedVus = vuMatches.map((match) => Number(match[1]));
  const requestedRates = rateMatches.map((match) => Number(match[1]));
  const duration = durationMatches.length === 1
    ? durationMatches[0][1]
    : `${durationSeconds}s`;
  return {
    valid: true,
    bytes: Buffer.byteLength(source),
    maxVus,
    diagnostics: [],
    execution: {
      duration,
      durationSeconds,
      vus: requestedVus[0] || 1,
      maxVus: Math.max(...requestedVus, 1),
      targetRps: Math.max(...requestedRates, 0),
    },
  };
}

export function validateScriptReport(code, options = {}) {
  try {
    return validateScript(code, options);
  } catch (error) {
    if (!(error instanceof ValidationError)) throw error;
    return {
      valid: false,
      bytes: Buffer.byteLength(String(code ?? "")),
      maxVus: Number(options.maxVus ?? 1000),
      diagnostics: error.details,
    };
  }
}
