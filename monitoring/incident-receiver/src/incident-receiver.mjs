import crypto from "node:crypto";
import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";

const DEFAULT_SIGNATURE_HEADER = "x-grafana-alerting-signature";
const DEFAULT_TIMESTAMP_HEADER = "x-grafana-alerting-timestamp";
const MAX_BODY_BYTES = 256 * 1024;
const MAX_CONTEXT_BYTES = 30_000;
const MAX_SIGNATURE_AGE_SECONDS = 5 * 60;
const ERROR_QUERY = '{app="buddystudy", level="ERROR"}';

export class ConfigurationError extends Error {}

export function loadConfig(env = process.env) {
  return {
    port: positiveInteger(env.INCIDENT_RECEIVER_PORT, 3030),
    dataDir: path.resolve(env.INCIDENT_DATA_DIR || "./data"),
    hmacSecret: String(env.GRAFANA_INCIDENT_HMAC_SECRET || ""),
    githubToken: String(env.CODEX_AUTOFIX_GITHUB_TOKEN || ""),
    githubRepository: boundedText(
      env.GITHUB_AUTOFIX_REPOSITORY || "ghkdqhrbals/buddy-studdy",
      200,
    ),
    githubEventType: boundedText(
      env.GITHUB_AUTOFIX_EVENT_TYPE || "codex-incident-autofix",
      100,
    ),
    lokiBaseUrl: normalizeBaseUrl(
      env.LOKI_BASE_URL || "http://buddystudy-loki:3100",
    ),
    deploymentHistoryUrl: normalizeBaseUrl(
      env.DEPLOYMENT_HISTORY_URL || "http://buddystudy-testzone-service:3020",
    ),
    signatureHeader: String(
      env.GRAFANA_SIGNATURE_HEADER || DEFAULT_SIGNATURE_HEADER,
    ).toLowerCase(),
    timestampHeader: String(
      env.GRAFANA_TIMESTAMP_HEADER || DEFAULT_TIMESTAMP_HEADER,
    ).toLowerCase(),
  };
}

function positiveInteger(value, fallback) {
  const number = Number.parseInt(value ?? "", 10);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function normalizeBaseUrl(value) {
  const parsed = new URL(String(value));
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new ConfigurationError("Service URL must use HTTP or HTTPS.");
  }
  parsed.pathname = parsed.pathname.replace(/\/+$/, "");
  parsed.search = "";
  parsed.hash = "";
  return parsed.toString().replace(/\/$/, "");
}

function boundedText(value, maximum = 500) {
  return String(value ?? "").trim().slice(0, maximum);
}

function requireConfiguration(config) {
  const missing = [];
  if (!config.hmacSecret) missing.push("GRAFANA_INCIDENT_HMAC_SECRET");
  if (!config.githubToken) missing.push("CODEX_AUTOFIX_GITHUB_TOKEN");
  if (!/^[\w.-]+\/[\w.-]+$/.test(config.githubRepository)) {
    throw new ConfigurationError("GITHUB_AUTOFIX_REPOSITORY must be owner/repository.");
  }
  if (missing.length) {
    throw new ConfigurationError(`Missing required configuration: ${missing.join(", ")}`);
  }
}

export function verifyGrafanaSignature({
  rawBody,
  signature,
  timestamp,
  secret,
  nowSeconds = Math.floor(Date.now() / 1000),
}) {
  const parsedTimestamp = Number.parseInt(timestamp ?? "", 10);
  if (!Number.isFinite(parsedTimestamp)) return false;
  if (Math.abs(nowSeconds - parsedTimestamp) > MAX_SIGNATURE_AGE_SECONDS) return false;

  const suppliedHex = String(signature ?? "").replace(/^sha256=/i, "").trim();
  if (!/^[a-f0-9]{64}$/i.test(suppliedHex)) return false;
  const expectedHex = crypto
    .createHmac("sha256", secret)
    .update(`${parsedTimestamp}:`)
    .update(rawBody)
    .digest("hex");
  const supplied = Buffer.from(suppliedHex, "hex");
  const expected = Buffer.from(expectedHex, "hex");
  return supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
}

export function sanitizeDiagnostic(value) {
  return String(value ?? "")
    .replace(/\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{12,}\b/g, "[REDACTED_OPENAI_KEY]")
    .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]{12,}={0,2}/gi, "Bearer [REDACTED]")
    .replace(
      /((?:authorization|password|passwd|token|secret|api[_-]?key|cookie)["']?\s*[:=]\s*["']?)([^\s"',}\]]+)/gi,
      "$1[REDACTED]",
    )
    .replace(/\b[0-9a-f]{64}\b/gi, "[REDACTED_64_HEX]");
}

function incidentIdentity(payload) {
  const firingAlerts = Array.isArray(payload?.alerts)
    ? payload.alerts.filter((alert) => alert?.status === "firing")
    : [];
  const alert = firingAlerts[0];
  if (payload?.status !== "firing" || !alert) return null;
  const fingerprint = boundedText(
    alert.fingerprint
      || crypto.createHash("sha256").update(JSON.stringify(alert.labels || {})).digest("hex"),
    128,
  );
  const startsAt = validDate(alert.startsAt) || new Date().toISOString();
  const incidentId = crypto
    .createHash("sha256")
    .update(`${fingerprint}:${startsAt}`)
    .digest("hex")
    .slice(0, 24);
  return { incidentId, fingerprint, startsAt, alert };
}

function hasConcreteLogIdentity(alert) {
  const labels = alert?.labels || {};
  const occurredAt = boundedText(labels.occurred_at);
  const requestId = boundedText(labels.request_id);
  const logger = boundedText(labels.logger);
  return Boolean(occurredAt && (requestId || logger));
}

function validDate(value) {
  const timestamp = Date.parse(String(value ?? ""));
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null;
}

function ns(milliseconds) {
  return (BigInt(Math.trunc(milliseconds)) * 1_000_000n).toString();
}

async function queryLoki(config, identity, fetchImpl) {
  const startMs = Date.parse(identity.startsAt) - 5 * 60 * 1000;
  const endMs = Date.now() + 30 * 1000;
  const parameters = new URLSearchParams({
    query: ERROR_QUERY,
    start: ns(startMs),
    end: ns(endMs),
    limit: "20",
    direction: "backward",
  });
  const response = await fetchImpl(
    `${config.lokiBaseUrl}/loki/api/v1/query_range?${parameters}`,
    { signal: AbortSignal.timeout(8_000) },
  );
  if (!response.ok) {
    throw new Error(`Loki query failed with status ${response.status}.`);
  }
  const body = await response.json();
  const rows = (body.data?.result ?? [])
    .flatMap((stream) => (stream.values ?? []).map(([timestamp, line]) => ({
      timestamp,
      line,
      labels: stream.stream || {},
    })))
    .sort((left, right) => Number(BigInt(right.timestamp) - BigInt(left.timestamp)));
  const rendered = rows.map((row) => {
    const timestamp = new Date(Number(BigInt(row.timestamp) / 1_000_000n)).toISOString();
    const labels = Object.fromEntries(
      ["app", "container", "level", "service"]
        .filter((key) => row.labels[key])
        .map((key) => [key, boundedText(row.labels[key], 160)]),
    );
    return `[${timestamp}] ${JSON.stringify(labels)}\n${sanitizeDiagnostic(row.line)}`;
  });
  return Buffer.from(rendered.join("\n\n---\n\n"), "utf8")
    .subarray(0, MAX_CONTEXT_BYTES)
    .toString("utf8");
}

async function latestBackendDeployment(config, fetchImpl) {
  try {
    const parameters = new URLSearchParams({
      service: "backend",
      status: "SUCCEEDED",
      limit: "1",
      offset: "0",
    });
    const response = await fetchImpl(
      `${config.deploymentHistoryUrl}/api/deployments?${parameters}`,
      { signal: AbortSignal.timeout(5_000) },
    );
    if (!response.ok) return null;
    const deployment = (await response.json()).items?.[0];
    if (!deployment) return null;
    return {
      sourceRepository: boundedText(deployment.sourceRepository, 200) || null,
      sourceSha: /^[a-f0-9]{7,64}$/i.test(deployment.sourceSha || "")
        ? deployment.sourceSha
        : null,
      image: boundedText(deployment.image, 500) || null,
      deployedAt: validDate(deployment.finishedAt || deployment.startedAt),
    };
  } catch {
    return null;
  }
}

function incidentPayload(payload, identity, logContext, deployment) {
  const labels = identity.alert.labels || {};
  const annotations = identity.alert.annotations || {};
  return {
    incident_id: identity.incidentId,
    alert_fingerprint: identity.fingerprint,
    alert_name: boundedText(labels.alertname || payload.title || "Backend ERROR", 200),
    service: boundedText(labels.service || "buddystudy-backend", 120),
    severity: boundedText(labels.severity || "error", 40),
    summary: boundedText(annotations.summary || payload.title, 500),
    description: boundedText(annotations.description || payload.message, 1_500),
    starts_at: identity.startsAt,
    generator_url: boundedText(identity.alert.generatorURL, 1_000) || null,
    logs_url: boundedText(annotations.logs_url, 2_000) || null,
    source_repository: deployment?.sourceRepository || null,
    source_sha: deployment?.sourceSha || null,
    deployed_image: deployment?.image || null,
    deployed_at: deployment?.deployedAt || null,
    log_context: logContext,
  };
}

async function dispatchGithub(config, incident, fetchImpl) {
  const response = await fetchImpl(
    `https://api.github.com/repos/${config.githubRepository}/dispatches`,
    {
      method: "POST",
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${config.githubToken}`,
        "Content-Type": "application/json",
        "User-Agent": "BuddyStudy-Incident-Receiver/1.0",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body: JSON.stringify({
        event_type: config.githubEventType,
        client_payload: { incident },
      }),
      signal: AbortSignal.timeout(10_000),
    },
  );
  if (response.status !== 204) {
    throw new Error(`GitHub repository dispatch failed with status ${response.status}.`);
  }
}

export class IncidentStore {
  constructor(dataDir, {
    staleAfterMs = 15 * 60 * 1000,
    retentionMs = 90 * 24 * 60 * 60 * 1000,
  } = {}) {
    this.dataDir = dataDir;
    this.staleAfterMs = staleAfterMs;
    this.retentionMs = retentionMs;
    this.operations = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.dataDir, { recursive: true, mode: 0o700 });
    const cutoff = Date.now() - this.retentionMs;
    const entries = await fs.readdir(this.dataDir, { withFileTypes: true });
    await Promise.all(entries
      .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
      .map(async (entry) => {
        const file = path.join(this.dataDir, entry.name);
        const stat = await fs.stat(file);
        if (stat.mtimeMs < cutoff) await fs.rm(file, { force: true });
      }));
    return this;
  }

  file(incidentId) {
    return path.join(this.dataDir, `${incidentId}.json`);
  }

  async reserve(incidentId, value) {
    return this.exclusive(async () => {
      try {
        await fs.writeFile(this.file(incidentId), JSON.stringify(value, null, 2), {
          flag: "wx",
          mode: 0o600,
        });
        return true;
      } catch (error) {
        if (error?.code !== "EEXIST") throw error;
        let existing;
        try {
          existing = JSON.parse(await fs.readFile(this.file(incidentId), "utf8"));
        } catch {
          return false;
        }
        const reservedAt = Date.parse(existing.reservedAt || "");
        const stale = existing.status === "DISPATCHING"
          && Number.isFinite(reservedAt)
          && Date.now() - reservedAt >= this.staleAfterMs;
        if (!stale) return false;
        await this.writeAtomic(incidentId, value);
        return true;
      }
    });
  }

  async exclusive(operation) {
    const result = this.operations.then(operation);
    this.operations = result.catch(() => {});
    return result;
  }

  async writeAtomic(incidentId, value) {
    const temporary = `${this.file(incidentId)}.${crypto.randomUUID()}.tmp`;
    await fs.writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
    await fs.rename(temporary, this.file(incidentId));
  }

  async update(incidentId, value) {
    return this.exclusive(() => this.writeAtomic(incidentId, value));
  }

  async release(incidentId) {
    return this.exclusive(() => fs.rm(this.file(incidentId), { force: true }));
  }
}

export async function createIncidentProcessor({
  config = loadConfig(),
  fetchImpl = fetch,
  store = new IncidentStore(config.dataDir),
  now = () => new Date(),
} = {}) {
  requireConfiguration(config);
  await store.init();
  return async function process({ rawBody, headers }) {
    const signature = headers[config.signatureHeader];
    const timestamp = headers[config.timestampHeader];
    if (!verifyGrafanaSignature({ rawBody, signature, timestamp, secret: config.hmacSecret })) {
      return { status: 401, body: { error: "Invalid or expired Grafana signature." } };
    }

    let payload;
    try {
      payload = JSON.parse(rawBody.toString("utf8"));
    } catch {
      return { status: 400, body: { error: "Request body must be valid JSON." } };
    }
    const identity = incidentIdentity(payload);
    if (!identity) {
      return { status: 202, body: { accepted: false, reason: "NOT_FIRING" } };
    }
    if (!hasConcreteLogIdentity(identity.alert)) {
      return { status: 202, body: { accepted: false, reason: "UNIDENTIFIED_ALERT" } };
    }
    const reservedAt = now().toISOString();
    const reserved = await store.reserve(identity.incidentId, {
      incidentId: identity.incidentId,
      status: "DISPATCHING",
      reservedAt,
    });
    if (!reserved) {
      return {
        status: 200,
        body: { accepted: false, duplicate: true, incidentId: identity.incidentId },
      };
    }

    try {
      const logContext = await queryLoki(config, identity, fetchImpl);
      if (!logContext.trim()) {
        await store.release(identity.incidentId);
        return {
          status: 202,
          body: { accepted: false, reason: "NO_ERROR_CONTEXT", incidentId: identity.incidentId },
        };
      }
      const deployment = await latestBackendDeployment(config, fetchImpl);
      const incident = incidentPayload(payload, identity, logContext, deployment);
      await dispatchGithub(config, incident, fetchImpl);
      await store.update(identity.incidentId, {
        incidentId: identity.incidentId,
        fingerprint: identity.fingerprint,
        status: "DISPATCHED",
        reservedAt,
        dispatchedAt: now().toISOString(),
        sourceSha: deployment?.sourceSha || null,
      });
      return { status: 202, body: { accepted: true, incidentId: identity.incidentId } };
    } catch (error) {
      await store.release(identity.incidentId);
      throw error;
    }
  };
}

async function readRawBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new Error("PAYLOAD_TOO_LARGE");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function sendJson(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

export async function createIncidentServer(dependencies = {}) {
  const config = dependencies.config || loadConfig();
  const processIncident = dependencies.processIncident
    || await createIncidentProcessor({ ...dependencies, config });
  return http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://incident-receiver.local");
    if (request.method === "GET" && url.pathname === "/health") {
      return sendJson(response, 200, { ok: true, service: "BuddyStudy Incident Receiver" });
    }
    if (request.method !== "POST" || url.pathname !== "/internal/incidents/grafana") {
      return sendJson(response, 404, { error: "Not found." });
    }
    try {
      const rawBody = await readRawBody(request);
      const result = await processIncident({ rawBody, headers: request.headers });
      return sendJson(response, result.status, result.body);
    } catch (error) {
      if (error?.message === "PAYLOAD_TOO_LARGE") {
        return sendJson(response, 413, { error: "Request body exceeds 256 KB." });
      }
      console.error(JSON.stringify({
        event: "incident_receiver_failed",
        errorType: error?.name || "Error",
        error: boundedText(error?.message, 1_000),
      }));
      return sendJson(response, 502, { error: "Incident dispatch failed." });
    }
  });
}
