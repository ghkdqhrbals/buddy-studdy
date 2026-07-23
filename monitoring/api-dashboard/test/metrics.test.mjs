import test from "node:test";
import assert from "node:assert/strict";
import {
  buildErrorRateQuery,
  buildRequestRateQuery,
  counterDeltaPoints,
  counterRatePoints,
  formatBytes,
  parseLokiMetricValues,
  parseRuntimeMetrics,
} from "../public/metrics.js";

test("parseRuntimeMetrics extracts the flat runtime payload", () => {
  const value = [
    "1784790000000000000",
    '2026-07-23T03:00:00Z INFO runtime_metrics {"capturedAtEpochMs":1784790000000,"processCpuPercent":12.5,"heapUsedBytes":1048576,"threadsLive":18}',
  ];

  const parsed = parseRuntimeMetrics(value);

  assert.equal(parsed.ms, 1784790000000);
  assert.equal(parsed.processCpuPercent, 12.5);
  assert.equal(parsed.heapUsedBytes, 1048576);
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

test("request and error rate queries aggregate in Loki", () => {
  assert.equal(
    buildRequestRateQuery("1m"),
    'sum(rate(({container=~"buddystudy-backend.*"} |= "api_exchange ")[1m]))',
  );
  assert.equal(
    buildErrorRateQuery("1m"),
    'sum(rate(({container=~"buddystudy-backend.*"} |= "api_exchange " |= "\\"status\\":5")[1m]))',
  );
});
