import test from "node:test";
import assert from "node:assert/strict";
import {
  buildClientErrorRateQuery,
  buildErrorRateQuery,
  buildLatencyQuantileQuery,
  buildRequestRateQuery,
  counterDeltaPoints,
  counterRatePoints,
  customMetricRange,
  formatBytes,
  formatMilliseconds,
  hasUnrecoveredRuntimeFailure,
  parseLokiMetricValues,
  parseRuntimeMetrics,
  percentagePoints,
  readLokiJson,
  relativeMetricRange,
  ratioPoints,
  toDateTimeLocalValue,
} from "../public/metrics.js";

test("parseRuntimeMetrics extracts the flat runtime payload", () => {
  const value = [
    "1784790000000000000",
    '2026-07-23T03:00:00Z INFO runtime_metrics {"capturedAtEpochMs":1784790000000,"runtimeKind":"native-image","runtimeMetricsDegraded":true,"runtimeMetricsUnavailable":"heapMemoryUsage","processCpuPercent":12.5,"heapUsedBytes":null,"threadsLive":18}',
  ];

  const parsed = parseRuntimeMetrics(value);

  assert.equal(parsed.ms, 1784790000000);
  assert.equal(parsed.processCpuPercent, 12.5);
  assert.equal(parsed.runtimeKind, "native-image");
  assert.equal(parsed.runtimeMetricsDegraded, true);
  assert.equal(parsed.runtimeMetricsUnavailable, "heapMemoryUsage");
  assert.equal(parsed.heapUsedBytes, null);
  assert.equal(parsed.threadsLive, 18);
});

test("parseLokiMetricValues converts Loki seconds and sorts points", () => {
  assert.deepEqual(
    parseLokiMetricValues([["1784790001", "2.5"], ["1784790000", "1.5"]]),
    [
      { ms: 1784790000000, value: 1.5 },
      { ms: 1784790001000, value: 2.5 },
    ],
  );
});

test("readLokiJson rejects HTML returned by a wrong dashboard origin", async () => {
  const response = new Response("<!DOCTYPE html><title>Grafana</title>", {
    status: 200,
    headers: { "content-type": "text/html; charset=utf-8" },
  });

  await assert.rejects(
    readLokiJson(response),
    /monitoring\.lowfidev\.cloud/,
  );
});

test("readLokiJson returns valid Loki JSON", async () => {
  const payload = { status: "success", data: { result: [] } };
  const response = new Response(JSON.stringify(payload), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

  assert.deepEqual(await readLokiJson(response), payload);
});

test("counterDeltaPoints ignores resets and reports interval changes", () => {
  const points = counterDeltaPoints([
    { ms: 1, gcCollectionTimeMsTotal: 10 },
    { ms: 2, gcCollectionTimeMsTotal: 16 },
    { ms: 3, gcCollectionTimeMsTotal: 2 },
    { ms: 4, gcCollectionTimeMsTotal: 5 },
  ], "gcCollectionTimeMsTotal");

  assert.deepEqual(points, [{ ms: 2, value: 6 }, { ms: 4, value: 3 }]);
});

test("counterRatePoints converts cumulative bytes to bytes per second", () => {
  const points = counterRatePoints([
    { ms: 1_000, networkReceiveBytesTotal: 100 },
    { ms: 3_000, networkReceiveBytesTotal: 500 },
  ], "networkReceiveBytesTotal");

  assert.deepEqual(points, [{ ms: 3_000, value: 200 }]);
});

test("formatBytes uses binary units", () => {
  assert.equal(formatBytes(1024), "1.00 KiB");
  assert.equal(formatBytes(10 * 1024 * 1024), "10.0 MiB");
});

test("formats latency without hiding sub-second detail", () => {
  assert.equal(formatMilliseconds(4.38), "4.38 ms");
  assert.equal(formatMilliseconds(125.4), "125 ms");
  assert.equal(formatMilliseconds(2500), "2.50 s");
});

test("request, error, and latency queries aggregate in Loki", () => {
  assert.equal(
    buildRequestRateQuery("1m"),
    'sum(rate(({container=~"buddystudy-backend.*"} |= "api_exchange ")[1m]))',
  );
  assert.equal(
    buildErrorRateQuery("1m"),
    'sum(rate(({container=~"buddystudy-backend.*"} |= "api_exchange " | json | __error__ = "" | status >= 500 and status < 600)[1m]))',
  );
  assert.equal(
    buildClientErrorRateQuery("1m"),
    'sum(rate(({container=~"buddystudy-backend.*"} |= "api_exchange " | json | __error__ = "" | status >= 400 and status < 500)[1m]))',
  );
  assert.equal(
    buildLatencyQuantileQuery(0.95, "1m"),
    'max(quantile_over_time(0.95, {container=~"buddystudy-backend.*"} |= "api_exchange " | json | __error__ = "" | unwrap durationMs [1m]))',
  );
});

test("ratioPoints aligns metric timestamps and treats missing errors as zero", () => {
  assert.deepEqual(
    ratioPoints(
      [{ ms: 2, value: 1 }],
      [{ ms: 1, value: 20 }, { ms: 2, value: 10 }],
    ),
    [{ ms: 1, value: 0 }, { ms: 2, value: 10 }],
  );
});

test("percentagePoints calculates resource saturation", () => {
  assert.deepEqual(
    percentagePoints(
      [
        { ms: 1, dbPoolAcquired: 8, dbPoolMaxAllocated: 10 },
        { ms: 2, dbPoolAcquired: 2, dbPoolMaxAllocated: 0 },
      ],
      "dbPoolAcquired",
      "dbPoolMaxAllocated",
    ),
    [{ ms: 1, value: 80 }],
  );
});

test("metric ranges support relative and explicit local date-time values", () => {
  assert.deepEqual(relativeMetricRange(60_000, 120_000), {
    startMs: 60_000,
    endMs: 120_000,
  });

  const start = new Date(2026, 6, 25, 10, 30, 0);
  const end = new Date(2026, 6, 25, 11, 45, 30);
  assert.deepEqual(
    customMetricRange(toDateTimeLocalValue(start), toDateTimeLocalValue(end)),
    { startMs: start.getTime(), endMs: end.getTime() },
  );
});

test("custom metric range rejects missing and reversed values", () => {
  assert.throws(() => customMetricRange("", ""), /Select both/);
  assert.throws(
    () => customMetricRange("2026-07-25T11:00:00", "2026-07-25T10:00:00"),
    /earlier/,
  );
});

test("runtime collection failure is recovered by a newer sample", () => {
  assert.equal(
    hasUnrecoveredRuntimeFailure([{ ms: 200 }], [{ ms: 100 }]),
    false,
  );
  assert.equal(
    hasUnrecoveredRuntimeFailure([{ ms: 100 }], [{ ms: 200 }]),
    true,
  );
  assert.equal(
    hasUnrecoveredRuntimeFailure([], [{ ms: 200 }]),
    true,
  );
});
