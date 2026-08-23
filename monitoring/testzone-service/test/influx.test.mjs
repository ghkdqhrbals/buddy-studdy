import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { InfluxWriter, lineProtocol, summarizeK6 } from "../src/influx.mjs";

test("lineProtocol escapes tags and emits nanosecond timestamps", () => {
  const value = lineProtocol(
    "testzone run",
    { run_id: "a,b", script: "read test" },
    { p95: 12.4, ok: true },
    1000,
  );
  assert.equal(value, "testzone\\ run,run_id=a\\,b,script=read\\ test p95=12.4,ok=true 1000000000");
});

test("summarizeK6 extracts stable report fields", () => {
  const summary = summarizeK6({
    metrics: {
      http_reqs: { values: { rate: 123.4, count: 1000 } },
      http_req_duration: {
        values: {
          avg: 14,
          min: 4,
          med: 10,
          max: 80,
          "p(90)": 20,
          "p(95)": 30,
          "p(99)": 50,
        },
      },
      http_req_failed: { values: { rate: 0.01 } },
      http_req_waiting: { values: { avg: 11 } },
      vus_max: { values: { max: 100 } },
    },
  });
  assert.equal(summary.requestRate, 123.4);
  assert.equal(summary.averageMs, 14);
  assert.equal(summary.minimumMs, 4);
  assert.equal(summary.medianMs, 10);
  assert.equal(summary.maximumMs, 80);
  assert.equal(summary.p90Ms, 20);
  assert.equal(summary.p95Ms, 30);
  assert.equal(summary.errorRate, 0.01);
  assert.equal(summary.tps, 123.4);
  assert.equal(summary.mttMs, 14);
  assert.equal(summary.mttfbMs, 11);
  assert.equal(summary.successCount, 990);
  assert.equal(summary.errorCount, 10);
  assert.equal(summary.maxVus, 100);
});

test("summarizeK6 reads the flat k6 0.54 summary export", () => {
  const summary = summarizeK6({
    metrics: {
      http_reqs: { rate: 5.03, count: 51 },
      iterations: { rate: 5.03, count: 51 },
      http_req_duration: {
        avg: 14.2,
        min: 8.1,
        med: 13.8,
        max: 31.4,
        "p(90)": 17.1,
        "p(95)": 18.1,
      },
      http_req_failed: { value: 0 },
      http_req_waiting: { avg: 12.4 },
      checks: { value: 1 },
      vus_max: { value: 1, max: 1 },
    },
  });
  assert.equal(summary.requestRate, 5.03);
  assert.equal(summary.requests, 51);
  assert.equal(summary.averageMs, 14.2);
  assert.equal(summary.minimumMs, 8.1);
  assert.equal(summary.medianMs, 13.8);
  assert.equal(summary.maximumMs, 31.4);
  assert.equal(summary.p50Ms, 13.8);
  assert.equal(summary.p90Ms, 17.1);
  assert.equal(summary.p95Ms, 18.1);
  assert.equal(summary.errorRate, 0);
  assert.equal(summary.successCount, 51);
  assert.equal(summary.errorCount, 0);
  assert.equal(summary.mttfbMs, 12.4);
  assert.equal(summary.checkRate, 1);
  assert.equal(summary.maxVus, 1);
});

test("run summaries avoid the legacy iterationRate field type", async () => {
  const writes = [];
  const writer = new InfluxWriter({
    url: "http://influx.test",
    token: "token",
    org: "test",
    bucket: "testzone",
  }, async (_url, request) => {
    writes.push(request.body);
    return new Response(null, { status: 204 });
  });

  await writer.writeRunSummary(
    {
      id: "run-1",
      profile: "script",
      status: "completed",
      finishedAt: "2026-07-27T05:00:00.000Z",
    },
    { id: "project-1", name: "API" },
    { id: "script-1", name: "read.js" },
    {
      requestRate: 30,
      iterationRate: 29.5,
    },
  );

  assert.equal(writes.length, 1);
  assert.doesNotMatch(writes[0], /iterationRate=/);
  assert.match(writes[0], /iterationsPerSecond=29\.5/);
});

test("InfluxWriter imports k6 0.54 points with a top-level metric name", async (context) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-influx-"));
  context.after(() => fs.rm(directory, { recursive: true, force: true }));
  const metricsPath = path.join(directory, "metrics.jsonl");
  await fs.writeFile(metricsPath, [
    JSON.stringify({
      metric: "http_req_duration",
      type: "Point",
      data: {
        time: "2026-07-24T02:06:03.730Z",
        value: 21.75,
        tags: {
          scenario: "publicQuestions",
          api: "public-questions",
          method: "GET",
          status: "200",
        },
      },
    }),
    "",
  ].join("\n"));
  const writes = [];
  const writer = new InfluxWriter(
    { url: "http://influx.test", token: "token", org: "org", bucket: "bucket" },
    async (_url, request) => {
      writes.push(request.body);
      return { ok: true };
    },
  );

  const result = await writer.importK6Json(metricsPath, {
    runId: "run-1",
    projectId: "project-1",
    scriptId: "script-1",
  });

  assert.equal(result.written, 1);
  assert.match(writes[0], /metric=http_req_duration/);
  assert.match(writes[0], /scenario=publicQuestions/);
  assert.match(writes[0], /api=public-questions/);
});

test("InfluxWriter reduces request samples to one-second API aggregates", async (context) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-influx-aggregate-"));
  context.after(() => fs.rm(directory, { recursive: true, force: true }));
  const metricsPath = path.join(directory, "metrics.jsonl");
  const point = (metric, value, milliseconds) => JSON.stringify({
    metric,
    type: "Point",
    data: {
      time: `2026-07-24T02:06:03.${milliseconds}Z`,
      value,
      tags: { api: "studies", method: "GET", status: "200" },
    },
  });
  await fs.writeFile(metricsPath, [
    point("http_reqs", 1, "100"),
    point("http_reqs", 1, "200"),
    point("http_req_duration", 10, "100"),
    point("http_req_duration", 40, "200"),
    point("http_req_failed", 0, "100"),
    point("http_req_failed", 1, "200"),
    "",
  ].join("\n"));
  const writes = [];
  const writer = new InfluxWriter(
    { url: "http://influx.test", token: "token", org: "org", bucket: "bucket" },
    async (_url, request) => {
      writes.push(request.body);
      return { ok: true };
    },
  );

  const result = await writer.importK6Json(metricsPath, {
    runId: "run-2",
    projectId: "project-1",
    scriptId: "script-1",
  });

  assert.equal(result.written, 3);
  assert.match(writes[0], /metric=http_reqs[^ ]* value=2 /);
  assert.match(writes[0], /metric=http_req_duration[^ ]* value=40 /);
  assert.match(writes[0], /metric=http_req_failed[^ ]* value=0.5 /);
});

test("InfluxWriter deletes every series belonging to one run id", async () => {
  const requests = [];
  const writer = new InfluxWriter(
    { url: "http://influx.test", token: "token", org: "org", bucket: "bucket" },
    async (url, request) => {
      requests.push({ url: String(url), request });
      return { ok: true };
    },
  );

  const result = await writer.deleteRun("run-with-quotes");

  assert.deepEqual(result, { deleted: true, skipped: false });
  assert.match(requests[0].url, /api\/v2\/delete/);
  assert.match(requests[0].url, /org=org/);
  assert.match(requests[0].url, /bucket=bucket/);
  assert.equal(requests[0].request.headers.Authorization, "Token token");
  const body = JSON.parse(requests[0].request.body);
  assert.equal(body.start, "1970-01-01T00:00:00Z");
  assert.equal(body.predicate, "run_id=\"run-with-quotes\"");
  assert.ok(Date.parse(body.stop) > Date.now());
});

test("InfluxWriter stores disposable component resource samples", async () => {
  const writes = [];
  const writer = new InfluxWriter(
    { url: "http://influx.test", token: "token", org: "org", bucket: "bucket" },
    async (_url, request) => {
      writes.push(request.body);
      return { ok: true };
    },
  );

  await writer.writeComponentSnapshots([{
    id: "mysql",
    image: "mysql:8.4",
    status: "running",
    metrics: {
      cpuPercent: 13.5,
      memoryUsedMb: 128,
      memoryLimitMb: 512,
      memoryPercent: 25,
      processes: 9,
      networkIO: "4MB / 2MB",
      blockIO: "1MB / 3MB",
      connections: 12,
      maxConnections: 100,
      activeConnections: 3,
      databaseSizeBytes: 1048576,
      cacheHitRatio: 0.99,
    },
  }], 1_000);

  assert.match(writes[0], /^testzone_component_runtime,component=mysql/);
  assert.match(writes[0], /cpuPercent=13.5/);
  assert.match(writes[0], /memoryUsedMb=128/);
  assert.match(writes[0], /processes=9/);
  assert.match(writes[0], /connections=12/);
  assert.match(writes[0], /maxConnections=100/);
  assert.match(writes[0], /cacheHitRatio=0.99/);
});
