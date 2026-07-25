const DURATION_PATTERN = /^(\d+)(ms|s|m|h)$/;
const K6_IMPORT_PATTERN = /from\s+["']([^"']+)["']/g;
const URL_PATTERN = /https?:\/\/[^\s"'`)}\]]+/g;
const VU_OPTION_PATTERN = /\b(?:vus|preAllocatedVUs|maxVUs)\s*:\s*(\d+)/g;
const DURATION_OPTION_PATTERN = /\b(?:duration|maxDuration)\s*:\s*["'](\d+(?:ms|s|m|h))["']/g;
const RATE_OPTION_PATTERN = /\brate\s*:\s*(\d+)/g;
const OPTIONS_EXPORT_PATTERN = /export\s+const\s+options\s*=/;
const TEST_CONFIG_PATTERN = /export\s+const\s+testConfig\s*=\s*\{([\s\S]*?)\};/;
const TARGET_URL_PATTERN = /\btargetUrl\s*:\s*["']([^"']+)["']/;
const TEST_NAME_PATTERN = /\bname\s*:\s*["']([^"']+)["']/;
const IDENTIFIER_PATTERN = /^[A-Za-z_$][\w$-]*/;

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

function matchingBrace(source, openIndex) {
  let depth = 0;
  let quote = null;
  let escaped = false;
  let lineComment = false;
  let blockComment = false;
  for (let index = openIndex; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (character === "\n") lineComment = false;
      continue;
    }
    if (blockComment) {
      if (character === "*" && next === "/") {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === "/" && next === "/") {
      lineComment = true;
      index += 1;
      continue;
    }
    if (character === "/" && next === "*") {
      blockComment = true;
      index += 1;
      continue;
    }
    if (character === "'" || character === '"' || character === "`") {
      quote = character;
      continue;
    }
    if (character === "{") depth += 1;
    if (character === "}") {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

function objectLiteralAfter(source, pattern, fromIndex = 0) {
  const match = pattern.exec(source.slice(fromIndex));
  if (!match) return null;
  const start = fromIndex + match.index + match[0].lastIndexOf("{");
  const end = matchingBrace(source, start);
  return end < 0 ? null : { start, end, source: source.slice(start, end + 1) };
}

function readScenarioEntries(source, scenariosObject) {
  const entries = [];
  let index = scenariosObject.start + 1;
  while (index < scenariosObject.end) {
    while (/[\s,]/.test(source[index] || "")) index += 1;
    if (index >= scenariosObject.end) break;
    let name = null;
    if (source[index] === "'" || source[index] === '"') {
      const quote = source[index];
      const start = index + 1;
      index += 1;
      while (index < scenariosObject.end && source[index] !== quote) {
        index += source[index] === "\\" ? 2 : 1;
      }
      name = source.slice(start, index);
      index += 1;
    } else {
      const match = source.slice(index).match(IDENTIFIER_PATTERN);
      if (!match) {
        index += 1;
        continue;
      }
      name = match[0];
      index += name.length;
    }
    while (/\s/.test(source[index] || "")) index += 1;
    if (source[index] !== ":") continue;
    index += 1;
    while (/\s/.test(source[index] || "")) index += 1;
    if (source[index] !== "{") {
      while (index < scenariosObject.end && source[index] !== ",") index += 1;
      continue;
    }
    const end = matchingBrace(source, index);
    if (end < 0 || end > scenariosObject.end) break;
    entries.push({ name, source: source.slice(index, end + 1) });
    index = end + 1;
  }
  return entries;
}

function stringOption(source, key, fallback = null) {
  const match = source.match(new RegExp(`\\b${key}\\s*:\\s*["']([^"']+)["']`));
  return match?.[1] ?? fallback;
}

function numberOption(source, key, fallback = null) {
  const match = source.match(new RegExp(`\\b${key}\\s*:\\s*(\\d+(?:\\.\\d+)?)`));
  return match ? Number(match[1]) : fallback;
}

function scenarioDefinition(name, source) {
  const durationMatches = [...source.matchAll(DURATION_OPTION_PATTERN)];
  const durationSeconds = durationMatches.reduce(
    (total, match) => total + (durationToSeconds(match[1]) || 0),
    0,
  );
  const duration = durationMatches.length === 1
    ? durationMatches[0][1]
    : durationSeconds > 0 ? `${durationSeconds}s` : null;
  const timeUnit = stringOption(source, "timeUnit", "1s");
  const timeUnitSeconds = durationToSeconds(timeUnit) || 1;
  const rate = numberOption(source, "rate", 0);
  const vus = numberOption(source, "vus", 0);
  const startVus = numberOption(source, "startVUs", 0);
  const preAllocatedVUs = numberOption(source, "preAllocatedVUs", vus || startVus || 1);
  const maxVus = numberOption(source, "maxVUs", vus || preAllocatedVUs || startVus || 1);
  const startTime = stringOption(source, "startTime", "0s");
  return {
    name,
    executor: stringOption(
      source,
      "executor",
      rate > 0 ? "constant-arrival-rate" : "constant-vus",
    ),
    exec: stringOption(source, "exec", "default"),
    rate,
    timeUnit,
    targetRps: rate > 0 ? rate / timeUnitSeconds : 0,
    duration,
    durationSeconds,
    startTime,
    startTimeSeconds: durationToSeconds(startTime) || 0,
    preAllocatedVUs,
    maxVus,
    vus: vus || startVus || preAllocatedVUs,
  };
}

export function extractScenarioDefinitions(source) {
  const optionsMatch = source.match(OPTIONS_EXPORT_PATTERN);
  if (!optionsMatch) return [];
  const optionsIndex = optionsMatch.index + optionsMatch[0].length;
  const optionsObject = objectLiteralAfter(source, /\{/, optionsIndex);
  if (!optionsObject) return [];
  const scenariosObject = objectLiteralAfter(optionsObject.source, /\bscenarios\s*:\s*\{/);
  if (scenariosObject) {
    const absoluteScenariosObject = {
      start: optionsObject.start + scenariosObject.start,
      end: optionsObject.start + scenariosObject.end,
    };
    return readScenarioEntries(source, absoluteScenariosObject)
      .map((entry) => scenarioDefinition(entry.name, entry.source));
  }
  return [scenarioDefinition("default", optionsObject.source)];
}

function peakConcurrentValue(scenarios, selector) {
  const startTimes = [...new Set(scenarios.map((scenario) => scenario.startTimeSeconds || 0))];
  return Math.max(...startTimes.map((time) => scenarios.reduce((total, scenario) => {
    const start = scenario.startTimeSeconds || 0;
    const end = start + scenario.durationSeconds;
    const active = start <= time && (scenario.durationSeconds <= 0 || time < end);
    return total + (active ? Number(selector(scenario)) || 0 : 0);
  }, 0)), 0);
}

export function validateScript(code, options = {}) {
  const source = String(code ?? "");
  const errors = [];
  const add = (message, index = 0) => errors.push(diagnostic(source, message, index));
  const maxVus = Number(options.maxVus ?? 1000);
  const maxDurationSeconds = Number(options.maxDurationSeconds ?? 3600);

  if (!source.trim()) add("Script is empty.");
  if (source.length > 250_000) add("Script exceeds the 250 KB limit.");
  if (!/export\s+default\s+function/.test(source)) {
    add("Script must export a default k6 function.");
  }
  if (!OPTIONS_EXPORT_PATTERN.test(source)) {
    add("Script must export k6 load settings with `export const options = ...`.");
  }
  const testConfigMatch = source.match(TEST_CONFIG_PATTERN);
  if (!testConfigMatch) {
    add("Script must export execution metadata with `export const testConfig = ...`.");
  }
  const targetUrlMatch = testConfigMatch?.[1].match(TARGET_URL_PATTERN);
  if (testConfigMatch && !targetUrlMatch) {
    add("Script testConfig must include an absolute targetUrl.", testConfigMatch.index);
  }
  let targetUrl = null;
  if (targetUrlMatch) {
    try {
      targetUrl = normalizeBaseUrl(targetUrlMatch[1]);
    } catch (error) {
      add(error.message, testConfigMatch.index);
    }
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
  const scenarios = extractScenarioDefinitions(source);
  const durationSeconds = scenarios.length
    ? Math.max(...scenarios.map((scenario) =>
      scenario.startTimeSeconds + scenario.durationSeconds), 0)
    : durationMatches.reduce(
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
  const peakTargetRps = peakConcurrentValue(scenarios, (scenario) => scenario.targetRps);
  if (peakTargetRps > maxTargetRps) {
    add(`Script scenarios request ${peakTargetRps.toLocaleString()} peak RPS; the TestZone maximum is ${maxTargetRps}.`);
  }
  const peakMaxVus = peakConcurrentValue(scenarios, (scenario) => scenario.maxVus);
  if (scenarios.length > 1 && peakMaxVus > maxVus) {
    add(`Script scenarios request ${peakMaxVus.toLocaleString()} peak max VUs; the TestZone maximum is ${maxVus}.`);
  }

  for (const match of source.matchAll(new RegExp(URL_PATTERN.source, "g"))) {
    const literal = match[0];
    try {
      normalizeBaseUrl(literal);
    } catch (error) {
      add(error.message, match.index);
    }
  }

  if (errors.length) throw new ValidationError("Fix the script diagnostics before running the test.", errors);
  const requestedVus = vuMatches.map((match) => Number(match[1]));
  const requestedRates = rateMatches.map((match) => Number(match[1]));
  const duration = scenarios.length === 1 && scenarios[0].duration
    ? scenarios[0].duration
    : `${durationSeconds}s`;
  const testName = testConfigMatch?.[1].match(TEST_NAME_PATTERN)?.[1]?.trim();
  return {
    valid: true,
    bytes: Buffer.byteLength(source),
    maxVus,
    diagnostics: [],
    execution: {
      duration,
      durationSeconds,
      vus: scenarios.length
        ? peakConcurrentValue(scenarios, (scenario) => scenario.preAllocatedVUs)
        : requestedVus[0] || 1,
      maxVus: scenarios.length ? peakMaxVus : Math.max(...requestedVus, 1),
      targetRps: scenarios.length ? peakTargetRps : Math.max(...requestedRates, 0),
      scenarios,
      targetUrl,
      name: testName || null,
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
