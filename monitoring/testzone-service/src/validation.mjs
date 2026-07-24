const DURATION_PATTERN = /^(\d+)(ms|s|m|h)$/;
const K6_IMPORT_PATTERN = /from\s+["']([^"']+)["']/g;
const URL_PATTERN = /https?:\/\/[^\s"'`)}\]]+/g;
const VU_OPTION_PATTERN = /\b(?:vus|preAllocatedVUs|maxVUs)\s*:\s*(\d+)/g;

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

  for (const match of source.matchAll(K6_IMPORT_PATTERN)) {
    const specifier = match[1];
    if (!specifier.startsWith("k6") && !specifier.startsWith("./")) {
      add(`Import ${specifier} is not allowed. Use k6 modules or project-local files.`, match.index);
    }
    if (/^https?:/i.test(specifier)) {
      add("Remote JavaScript imports are not allowed.", match.index);
    }
  }

  for (const match of source.matchAll(VU_OPTION_PATTERN)) {
    if (Number(match[1]) > maxVus) {
      add(`Script requests ${match[1]} VUs; the TestZone maximum is ${maxVus}.`, match.index);
    }
  }

  const duration = durationToSeconds(options.duration);
  if (options.duration && duration === null) add("Duration must look like 30s, 5m, or 1h.");
  if (duration !== null && duration > maxDurationSeconds) {
    add(`Duration exceeds the ${maxDurationSeconds} second limit.`);
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
  return { valid: true, bytes: Buffer.byteLength(source), maxVus, diagnostics: [] };
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

export function normalizeRunOptions(input, config) {
  const duration = String(input.duration || "30s").trim();
  const durationSeconds = durationToSeconds(duration);
  const vus = Number.parseInt(input.vus ?? 10, 10);
  const maxVus = Number.parseInt(input.maxVus ?? Math.max(vus, 100), 10);
  const targetRps = Number.parseInt(input.targetRps ?? 0, 10);

  if (durationSeconds === null || durationSeconds <= 0 || durationSeconds > config.maxDurationSeconds) {
    throw new ValidationError(`Duration must be between 1ms and ${config.maxDurationSeconds} seconds.`);
  }
  if (!Number.isFinite(vus) || vus < 1 || vus > config.maxVus) {
    throw new ValidationError(`VUs must be between 1 and ${config.maxVus}.`);
  }
  if (!Number.isFinite(maxVus) || maxVus < vus || maxVus > config.maxVus) {
    throw new ValidationError(`Max VUs must be between ${vus} and ${config.maxVus}.`);
  }
  const maxTargetRps = Number(config.maxTargetRps ?? 3000);
  if (!Number.isFinite(targetRps) || targetRps < 0 || targetRps > maxTargetRps) {
    throw new ValidationError(`Target RPS must be between 0 and ${maxTargetRps}.`);
  }
  return { duration, durationSeconds, vus, maxVus, targetRps };
}
