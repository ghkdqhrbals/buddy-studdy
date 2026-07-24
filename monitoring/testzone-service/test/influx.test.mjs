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
      http_req_duration: { values: { "p(50)": 10, "p(90)": 20, "p(95)": 30, "p(99)": 50 } },
      http_req_failed: { values: { rate: 0.01 } },
      vus_max: { values: { max: 100 } },
    },
  });
  assert.equal(summary.requestRate, 123.4);
  assert.equal(summary.p95Ms, 30);
  assert.equal(summary.errorRate, 0.01);
  assert.equal(summary.maxVus, 100);
});

test("summarizeK6 reads the flat k6 0.54 summary export", () => {
  const summary = summarizeK6({
    metrics: {
      http_reqs: { rate: 5.03, count: 51 },
      iterations: { rate: 5.03, count: 51 },
      http_req_duration: { med: 13.8, "p(90)": 17.1, "p(95)": 18.1 },
      http_req_failed: { value: 0 },
      checks: { value: 1 },
      vus_max: { value: 1, max: 1 },
    },
  });
  assert.equal(summary.requestRate, 5.03);
  assert.equal(summary.requests, 51);
  assert.equal(summary.p50Ms, 13.8);
  assert.equal(summary.p95Ms, 18.1);
  assert.equal(summary.errorRate, 0);
  assert.equal(summary.checkRate, 1);
  assert.equal(summary.maxVus, 1);
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
        tags: { api: "public-questions", method: "GET", status: "200" },
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
