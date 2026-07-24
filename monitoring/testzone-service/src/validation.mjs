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
  const maxVus = Number(options.maxVus ?? 1000);
  const maxDurationSeconds = Number(options.maxDurationSeconds ?? 3600);
  const targetBaseUrl = options.targetBaseUrl ? normalizeBaseUrl(options.targetBaseUrl) : null;

  if (!source.trim()) errors.push("Script is empty.");
  if (source.length > 250_000) errors.push("Script exceeds the 250 KB limit.");
  if (!/export\s+default\s+function/.test(source)) {
    errors.push("Script must export a default k6 function.");
  }

  for (const match of source.matchAll(K6_IMPORT_PATTERN)) {
    const specifier = match[1];
    if (!specifier.startsWith("k6") && !specifier.startsWith("./")) {
      errors.push(`Import ${specifier} is not allowed. Use k6 modules or project-local files.`);
    }
    if (/^https?:/i.test(specifier)) {
      errors.push("Remote JavaScript imports are not allowed.");
    }
  }

  for (const match of source.matchAll(VU_OPTION_PATTERN)) {
    if (Number(match[1]) > maxVus) {
      errors.push(`Script requests ${match[1]} VUs; the TestZone maximum is ${maxVus}.`);
    }
  }

  const duration = durationToSeconds(options.duration);
  if (options.duration && duration === null) errors.push("Duration must look like 30s, 5m, or 1h.");
  if (duration !== null && duration > maxDurationSeconds) {
    errors.push(`Duration exceeds the ${maxDurationSeconds} second limit.`);
  }

  if (targetBaseUrl) {
    const targetHost = new URL(targetBaseUrl).hostname;
    for (const literal of source.match(URL_PATTERN) ?? []) {
      const hostname = new URL(literal).hostname;
      if (hostname !== targetHost && !literal.includes("127.0.0.1") && !literal.includes("localhost")) {
        errors.push(`Script contains a URL outside the selected target: ${hostname}.`);
      }
    }
  }

  if (errors.length) throw new ValidationError("The k6 script is not safe to run.", errors);
  return { valid: true, bytes: Buffer.byteLength(source), maxVus };
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
