import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  createIncidentProcessor,
  IncidentStore,
  sanitizeDiagnostic,
  verifyGrafanaSignature,
} from "../src/incident-receiver.mjs";

const secret = "grafana-test-secret";

function signed(rawBody, timestamp = Math.floor(Date.now() / 1000)) {
  return {
    "x-grafana-alerting-timestamp": String(timestamp),
    "x-grafana-alerting-signature": crypto
      .createHmac("sha256", secret)
      .update(`${timestamp}:`)
      .update(rawBody)
      .digest("hex"),
  };
}

function payload() {
  return {
    status: "firing",
    title: "Backend ERROR",
    alerts: [{
      status: "firing",
      startsAt: "2026-07-31T10:00:00.000Z",
      fingerprint: "grafana-fingerprint",
      labels: {
        alertname: "BuddyStudy backend ERROR log",
        service: "buddystudy-backend",
        severity: "error",
        occurred_at: "2026-07-31T10:00:00.000Z",
        request_id: "request-exact-error",
      },
      annotations: {
        summary: "Backend ERROR detected",
        description: "Inspect the Loki event.",
        logs_url: "https://grafana.lowfidev.cloud/explore",
      },
    }],
  };
}

test("Grafana HMAC includes timestamp and rejects replayed signatures", () => {
  const rawBody = Buffer.from(JSON.stringify(payload()));
  const nowSeconds = 1_785_494_400;
  const headers = signed(rawBody, nowSeconds);
  assert.equal(verifyGrafanaSignature({
    rawBody,
    signature: headers["x-grafana-alerting-signature"],
    timestamp: headers["x-grafana-alerting-timestamp"],
    secret,
    nowSeconds,
  }), true);
  assert.equal(verifyGrafanaSignature({
    rawBody,
    signature: headers["x-grafana-alerting-signature"],
    timestamp: headers["x-grafana-alerting-timestamp"],
    secret,
    nowSeconds: nowSeconds + 301,
  }), false);
});

test("diagnostic sanitization removes credentials before GitHub dispatch", () => {
  const sanitized = sanitizeDiagnostic(
    'authorization=Bearer abcdefghijklmnopqrstuvwxyz password="secret-value" key=sk-proj-abcdefghijklmnop',
  );
  assert.doesNotMatch(sanitized, /abcdefghijklmnopqrstuvwxyz/);
  assert.doesNotMatch(sanitized, /secret-value/);
  assert.doesNotMatch(sanitized, /sk-proj-/);
  assert.match(sanitized, /REDACTED/);
});

test("incident store reclaims only stale in-progress reservations", async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "incident-store-"));
  const store = await new IncidentStore(dataDir, { staleAfterMs: 1 }).init();
  assert.equal(await store.reserve("incident-a", {
    status: "DISPATCHING",
    reservedAt: "2026-01-01T00:00:00.000Z",
  }), true);
  assert.equal(await store.reserve("incident-a", {
    status: "DISPATCHING",
    reservedAt: new Date().toISOString(),
  }), true);
  await store.update("incident-a", {
    status: "DISPATCHED",
    reservedAt: "2026-01-01T00:00:00.000Z",
  });
  assert.equal(await store.reserve("incident-a", {
    status: "DISPATCHING",
    reservedAt: new Date().toISOString(),
  }), false);
});

test("processor enriches one firing alert and deduplicates its alert instance", async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "incident-receiver-"));
  const dispatches = [];
  const fetchImpl = async (url, options = {}) => {
    if (String(url).includes("/loki/api/v1/query_range")) {
      return Response.json({
        data: {
          result: [{
            stream: { app: "buddystudy", level: "ERROR" },
            values: [["1785492000000000000", "IllegalStateException: failed token=very-secret-token"]],
          }],
        },
      });
    }
    if (String(url).includes("/api/deployments?")) {
      return Response.json({
        items: [{
          sourceRepository: "ghkdqhrbals/buddy-studdy",
          sourceSha: "0123456789abcdef0123456789abcdef01234567",
          image: "ghcr.io/ghkdqhrbals/buddystudy-backend:sha-jvm",
          finishedAt: "2026-07-31T09:55:00.000Z",
        }],
      });
    }
    if (String(url).includes("api.github.com/repos/")) {
      dispatches.push(JSON.parse(options.body));
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected URL: ${url}`);
  };
  const processor = await createIncidentProcessor({
    config: {
      port: 3030,
      dataDir,
      hmacSecret: secret,
      githubToken: "github-token",
      githubRepository: "ghkdqhrbals/buddy-studdy",
      githubEventType: "codex-incident-autofix",
      lokiBaseUrl: "http://loki",
      deploymentHistoryUrl: "http://deployments",
      signatureHeader: "x-grafana-alerting-signature",
      timestampHeader: "x-grafana-alerting-timestamp",
    },
    fetchImpl,
  });
  const rawBody = Buffer.from(JSON.stringify(payload()));
  const headers = signed(rawBody);

  const accepted = await processor({ rawBody, headers });
  const duplicate = await processor({ rawBody, headers });

  assert.equal(accepted.status, 202);
  assert.equal(accepted.body.accepted, true);
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.body.duplicate, true);
  assert.equal(dispatches.length, 1);
  const incident = dispatches[0].client_payload.incident;
  assert.equal(incident.incident_id, accepted.body.incidentId);
  assert.equal(incident.source_sha, "0123456789abcdef0123456789abcdef01234567");
  assert.match(incident.log_context, /IllegalStateException/);
  assert.doesNotMatch(incident.log_context, /very-secret-token/);
});

test("resolved alerts and firing alerts without Loki context do not start Codex", async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "incident-receiver-"));
  let githubCalls = 0;
  const processor = await createIncidentProcessor({
    config: {
      port: 3030,
      dataDir,
      hmacSecret: secret,
      githubToken: "github-token",
      githubRepository: "ghkdqhrbals/buddy-studdy",
      githubEventType: "codex-incident-autofix",
      lokiBaseUrl: "http://loki",
      deploymentHistoryUrl: "http://deployments",
      signatureHeader: "x-grafana-alerting-signature",
      timestampHeader: "x-grafana-alerting-timestamp",
    },
    fetchImpl: async (url) => {
      if (String(url).includes("/loki/")) return Response.json({ data: { result: [] } });
      if (String(url).includes("api.github.com")) githubCalls += 1;
      return Response.json({ items: [] });
    },
  });

  const resolvedPayload = payload();
  resolvedPayload.status = "resolved";
  resolvedPayload.alerts[0].status = "resolved";
  const resolvedBody = Buffer.from(JSON.stringify(resolvedPayload));
  const resolved = await processor({ rawBody: resolvedBody, headers: signed(resolvedBody) });
  assert.equal(resolved.body.reason, "NOT_FIRING");

  const firingBody = Buffer.from(JSON.stringify(payload()));
  const noContext = await processor({ rawBody: firingBody, headers: signed(firingBody) });
  assert.equal(noContext.body.reason, "NO_ERROR_CONTEXT");
  assert.equal(githubCalls, 0);
});

test("firing alerts without an extracted log identity do not query Loki or start Codex", async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "incident-receiver-"));
  let fetchCalls = 0;
  const processor = await createIncidentProcessor({
    config: {
      port: 3030,
      dataDir,
      hmacSecret: secret,
      githubToken: "github-token",
      githubRepository: "ghkdqhrbals/buddy-studdy",
      githubEventType: "codex-incident-autofix",
      lokiBaseUrl: "http://loki",
      deploymentHistoryUrl: "http://deployments",
      signatureHeader: "x-grafana-alerting-signature",
      timestampHeader: "x-grafana-alerting-timestamp",
    },
    fetchImpl: async () => {
      fetchCalls += 1;
      return Response.json({ data: { result: [] } });
    },
  });
  const unidentified = payload();
  delete unidentified.alerts[0].labels.occurred_at;
  delete unidentified.alerts[0].labels.request_id;
  const rawBody = Buffer.from(JSON.stringify(unidentified));

  const result = await processor({ rawBody, headers: signed(rawBody) });

  assert.equal(result.status, 202);
  assert.equal(result.body.reason, "UNIDENTIFIED_ALERT");
  assert.equal(fetchCalls, 0);
});
