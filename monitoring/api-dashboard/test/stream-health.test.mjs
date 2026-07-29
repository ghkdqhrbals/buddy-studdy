import assert from "node:assert/strict";
import test from "node:test";

import {
  formatStreamActivity,
  formatStreamDuration,
  latestConsumerActivity,
  streamEntryAge,
  streamGroupState,
  streamLatestConsumerActivity,
  streamOperationalState,
  streamRetention,
  summarizeStreamHealth,
} from "../src/lib/streamHealth.js";

const topics = [
  {
    topic: "push-events",
    streamKey: "buddystudy-push-v1",
    groups: [
      { name: "push", lag: 3, pending: 2, maxRetryCount: 1 },
      { name: "notifications", lag: 0, pending: 0, maxRetryCount: 0 },
    ],
  },
];

test("consumer group summary separates lag pending and retrying groups", () => {
  assert.deepEqual(summarizeStreamHealth(topics), {
    streams: 1,
    groups: 2,
    lag: 3,
    pending: 2,
    retrying: 1,
    inspectionFailures: 0,
  });
});

test("stream retention compares XLEN with configured MAXLEN", () => {
  assert.deepEqual(streamRetention({ length: 250, maxLength: 1_000 }), {
    percent: 25,
    label: "25%",
  });
});

test("consumer group state prioritizes retries then pending then lag", () => {
  assert.equal(streamGroupState({ inspectionErrors: [{ operation: "XPENDING" }] }).label, "Partial data");
  assert.equal(streamGroupState({ maxRetryCount: 1, pending: 2, lag: 3 }).label, "Retrying");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 2, lag: 3 }).label, "Pending");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 0, lag: 3 }).label, "Lagging");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 0, lag: 0 }).label, "Healthy");
});

test("stream durations remain compact and readable", () => {
  assert.equal(formatStreamDuration(450), "450 ms");
  assert.equal(formatStreamDuration(2_500), "2.5 s");
  assert.equal(formatStreamDuration(90_000), "1.5 min");
  assert.equal(formatStreamDuration(-1), "-");
  assert.equal(formatStreamActivity(90_000), "1.5 min ago");
  assert.equal(formatStreamActivity(null), "Not recorded");
});

test("consumer activity uses the most recent successful interaction", () => {
  assert.deepEqual(latestConsumerActivity({
    consumerDetails: [
      { inactiveMs: 9_000, idleMs: 100 },
      { inactiveMs: 2_000, idleMs: 800 },
    ],
  }), { milliseconds: 2_000, source: "successful" });
  assert.deepEqual(latestConsumerActivity({
    consumerDetails: [{ inactiveMs: -1, idleMs: 100 }],
  }), { milliseconds: null, source: "successful" });
  assert.deepEqual(latestConsumerActivity({
    consumerDetails: [{ inactiveMs: null, idleMs: 750 }],
  }), { milliseconds: 750, source: "legacy-idle" });
});

test("stream activity is the newest consumer-group activity", () => {
  assert.deepEqual(streamLatestConsumerActivity({
    groups: [
      { consumerDetails: [{ inactiveMs: 8_000, idleMs: 100 }] },
      { consumerDetails: [{ inactiveMs: 1_500, idleMs: 100 }] },
    ],
  }), { milliseconds: 1_500, source: "successful" });
});

test("stream state summarizes group delivery risk", () => {
  assert.equal(streamOperationalState({ groups: [] }).label, "No groups");
  assert.equal(streamOperationalState({ groups: [{ lag: 2 }] }).label, "Lagging");
  assert.equal(streamOperationalState({ groups: [{ pending: 1 }] }).label, "Pending");
  assert.equal(streamOperationalState({ groups: [{ maxRetryCount: 2 }] }).label, "Retrying");
});

test("stream IDs expose entry age without treating it as consumer activity", () => {
  assert.equal(streamEntryAge("1785000998000-0", 1785001000000), 2_000);
  assert.equal(streamEntryAge("invalid", 1785001000000), null);
});
