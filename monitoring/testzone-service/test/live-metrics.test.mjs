import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { K6LiveMetricsReader, consumeK6Lines, summarizeLiveBucket } from "../src/live-metrics.mjs";

function point(metric, value, time) {
  return JSON.stringify({
    type: "Point",
    data: { metric, value, time },
  });
}

test("k6 point stream is aggregated into one live sample per second", () => {
  const lines = [
    point("http_reqs", 1, "2026-07-24T03:11:11.100Z"),
    point("http_reqs", 1, "2026-07-24T03:11:11.600Z"),
    point("http_req_duration", 100, "2026-07-24T03:11:11.200Z"),
    point("http_req_duration", 400, "2026-07-24T03:11:11.800Z"),
    point("http_req_failed", 0, "2026-07-24T03:11:11.200Z"),
    point("http_req_failed", 1, "2026-07-24T03:11:11.800Z"),
    point("http_req_waiting", 80, "2026-07-24T03:11:11.200Z"),
    point("http_req_waiting", 320, "2026-07-24T03:11:11.800Z"),
    point("vus", 1000, "2026-07-24T03:11:11.800Z"),
  ];

  const bucket = [...consumeK6Lines(lines).values()][0];
  const sample = summarizeLiveBucket(bucket);

  assert.equal(sample.requestRate, 2);
  assert.equal(sample.tps, 2);
  assert.equal(sample.successCount, 1);
  assert.equal(sample.errorCount, 1);
  assert.equal(sample.averageMs, 250);
  assert.equal(sample.minimumMs, 100);
  assert.equal(sample.medianMs, 100);
  assert.equal(sample.maximumMs, 400);
  assert.equal(sample.p90Ms, 400);
  assert.equal(sample.p95Ms, 400);
  assert.equal(sample.errorRate, 0.5);
  assert.equal(sample.mttMs, 250);
  assert.equal(sample.mttfbMs, 200);
  assert.equal(sample.vus, 1000);
});

test("live reader keeps partial JSONL lines and emits the final second on close", async (context) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-live-"));
  context.after(() => fs.rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, "metrics.jsonl");
  const first = point("http_reqs", 1, "2026-07-24T03:11:11.100Z");
  const second = point("http_reqs", 1, "2026-07-24T03:11:12.100Z");
  await fs.writeFile(file, `${first}\n${second.slice(0, 20)}`);
  const reader = new K6LiveMetricsReader(file);

  assert.deepEqual(await reader.read(false), []);
  await fs.appendFile(file, `${second.slice(20)}\n`);
  const firstSamples = await reader.read(false);
  const finalSamples = await reader.read(true);

  assert.equal(firstSamples.length, 1);
  assert.equal(firstSamples[0].requestRate, 1);
  assert.equal(finalSamples.length, 1);
  assert.equal(finalSamples[0].requestRate, 1);
});
