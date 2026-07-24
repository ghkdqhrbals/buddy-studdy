import test from "node:test";
import assert from "node:assert/strict";
import {
  maximumMetric,
  p95CollectionStatus,
  resultsFor,
  runtimeSeries,
  sustainableCapacity,
  unique,
} from "../public/testzone-model.js";

const results = [
  {
    tool: "k6",
    runtime: "mvc",
    scenario: "studies",
    load: { type: "rps", value: 1000 },
    summary: {
      successRps: 990,
      failureRate: 0,
      dropped: 0,
      allRequestP95Ms: 25,
      successfulRequestP95Ms: 24,
    },
    resources: { appCpuP95: 120 },
    validity: { valid: true },
  },
  {
    tool: "k6",
    runtime: "webflux",
    scenario: "studies",
    load: { type: "rps", value: 1000 },
    summary: {
      successRps: 330,
      failureRate: 0.37,
      dropped: 4700,
      allRequestP95Ms: 5000,
      successfulRequestP95Ms: null,
    },
    resources: { appCpuP95: 410 },
    validity: { valid: true },
  },
];

test("filters execution results by API scenario and tool", () => {
  assert.equal(
    resultsFor({ results }, { scenario: "studies", tool: "k6" }).length,
    2,
  );
  assert.equal(
    resultsFor({ results }, { scenario: "public-questions", tool: "k6" }).length,
    0,
  );
});

test("sustainable capacity excludes timeout-saturated stages", () => {
  assert.equal(sustainableCapacity(results, "mvc"), 1000);
  assert.equal(sustainableCapacity(results, "webflux"), null);
});

test("runtime series keeps missing successful-only percentiles explicit", () => {
  assert.deepEqual(
    runtimeSeries(results, "successfulRequestP95Ms").series,
    [
      { runtime: "mvc", values: [24] },
      { runtime: "webflux", values: [null] },
    ],
  );
});

test("metric and collection helpers do not invent missing values", () => {
  assert.equal(maximumMetric(results, "summary", "allRequestP95Ms"), 5000);
  assert.deepEqual(p95CollectionStatus(results), {
    measured: 1,
    total: 2,
    complete: false,
  });
  assert.deepEqual(unique([1000, 1000, 2000]), [1000, 2000]);
});
