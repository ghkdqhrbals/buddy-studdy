import test from "node:test";
import assert from "node:assert/strict";
import { percentile, summarizeLatencySamples } from "../src/statistics.mjs";

test("latency statistics preserve the k6 summary measures used by the dashboard", () => {
  const samples = [100, 110, 120, 140, 180];

  assert.equal(percentile(samples, 0.5), 120);
  assert.deepEqual(summarizeLatencySamples(samples), {
    averageMs: 130,
    minimumMs: 100,
    medianMs: 120,
    maximumMs: 180,
    p90Ms: 180,
    p95Ms: 180,
  });
});

test("latency statistics ignore invalid values and return nulls for empty samples", () => {
  assert.deepEqual(summarizeLatencySamples([null, undefined, "invalid"]), {
    averageMs: null,
    minimumMs: null,
    medianMs: null,
    maximumMs: null,
    p90Ms: null,
    p95Ms: null,
  });
});
