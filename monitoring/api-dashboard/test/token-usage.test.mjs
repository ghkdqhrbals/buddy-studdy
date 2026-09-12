import test from "node:test";
import assert from "node:assert/strict";
import { parseUsage, parseUsageStreams, summarizeUsage, groupUsage, usageTrend, totalField, USAGE_LIMIT, loadUsage } from "../src/lib/tokenUsage.js";
const event = (values = {}, timestamp = "1000000000") => [timestamp, '2026-09-13 INFO openai_usage ' + JSON.stringify({ event: "openai_usage", schemaVersion: 1, model: "gpt-realtime", operation: "realtime", stage: "response", granularity: "provider_response", eventRef: "a".repeat(32), ...values })];
test("parses provider counts without coercing missing or invalid fields to zero", () => {
  const r = parseUsage(event({ inputTokens: 100, outputTokens: 0, cachedInputTokens: "2", inputAudioTokens: -1, reasoningTokens: 0.5, outputAudioTokens: Number.MAX_SAFE_INTEGER + 1, prompt: "private" }));
  assert.equal(r.inputTokens, 100); assert.equal(r.outputTokens, 0); assert.equal(r.cachedInputTokens, null);
  assert.equal(r.inputAudioTokens, null); assert.equal(r.reasoningTokens, null); assert.equal(r.outputAudioTokens, null); assert.equal(r.prompt, undefined);
});
test("rejects malformed logs and unsupported schemas", () => {
  assert.equal(parseUsage(["1", "not usage"]), null); assert.equal(parseUsage(["1", "openai_usage {bad}"]), null);
  assert.equal(parseUsage(event({ schemaVersion: 2 })), null); assert.equal(parseUsage(event({}, "bad")), null);
});
test("deduplicates usage events across streams using eventRef", () => {
  const data = parseUsageStreams([{ values: [event()] }, { values: [event({}, "2000000000")] }]);
  assert.equal(data.rows.length, 1); assert.equal(data.rows[0].timestampMs, 2000);
});
test("does not double count cached input and retains partial coverage", () => {
  const rows = [parseUsage(event({ inputTokens: 100, outputTokens: 20, cachedInputTokens: 80 })), parseUsage(event({ inputTokens: 50, outputTokens: null }))];
  const s = summarizeUsage(rows);
  assert.equal(s.inputTokens.value, 150); assert.equal(s.outputTokens.value, 20); assert.equal(s.outputTokens.missing, 1);
  assert.equal(s.cachedInputTokens.value, 80); assert.equal(s.cacheRate, .8); assert.equal(s.cacheCoverage, 1); assert.equal(s.missing, 1);
});
test("unknown usage stays unknown; explicit zero remains zero", () => {
  assert.equal(totalField([parseUsage(event())], "inputTokens").value, null);
  assert.equal(totalField([parseUsage(event({ inputTokens: 0 }))], "inputTokens").value, 0);
  assert.equal(summarizeUsage([]).cacheRate, null);
});
test("grouping keeps models, stages and accounting granularities separate", () => {
  const rows = [parseUsage(event({ inputTokens: 10 })), parseUsage(event({ inputTokens: 20, granularity: "physical_attempt" })), parseUsage(event({ inputTokens: 30, stage: "question-readback" }))];
  const groups = groupUsage(rows); assert.equal(groups.length, 3); assert.equal(groups[0].inputTokens.value, 30);
});
test("cancelled and failed responses keep their reported token consumption", () => {
  const s = summarizeUsage([parseUsage(event({ outcome: "cancelled", outputTokens: 12 })), parseUsage(event({ outcome: "failed", outputTokens: 4 }))]);
  assert.equal(s.outputTokens.value, 16);
});
test("timeline honors time boundaries, ignores outside events and preserves unknowns", () => {
  const rows = [parseUsage(event({ inputTokens: 10 }, "1000000000")), parseUsage(event({}, "2000000000")), parseUsage(event({ inputTokens: 99 }, "3000000000"))];
  const buckets = usageTrend(rows, 1000, 2000, 2);
  assert.equal(buckets[0].input, 10); assert.equal(buckets[1].input, null); assert.equal(buckets[1].count, 1);
});
test("raw retrieval cap and invalid logs are surfaced even after deduplication", () => {
  const data = parseUsageStreams([{ values: [...Array.from({ length: USAGE_LIMIT - 1 }, () => event()), ["1", "openai_usage invalid"]] }]);
  assert.equal(data.truncated, true); assert.equal(data.invalid, 1); assert.equal(data.rows.length, 1);
});
test("does not include inconsistent cached counts in hit rate", () => {
  assert.equal(summarizeUsage([parseUsage(event({ inputTokens: 1, cachedInputTokens: 100 }))]).cacheRate, null);
});
test("queries usage telemetry only and propagates abort signal", async () => {
  const original = globalThis.fetch;
  const controller = new AbortController();
  try {
    globalThis.fetch = async (url, options) => {
      const params = new URL(url, "http://localhost").searchParams;
      assert.match(params.get("query"), /openai_usage/); assert.equal(params.get("limit"), "2000"); assert.equal(options.signal, controller.signal);
      return { ok: true, json: async () => ({ status: "success", data: { result: [] } }) };
    };
    assert.equal((await loadUsage("3600000", controller.signal)).rows.length, 0);
    globalThis.fetch = async () => ({ ok: false, status: 401 });
    await assert.rejects(loadUsage("3600000"), (e) => e.status === 401);
    globalThis.fetch = async () => ({ ok: true, json: async () => ({ status: "error" }) });
    await assert.rejects(loadUsage("3600000"));
  } finally { globalThis.fetch = original; }
});
