import assert from "node:assert/strict";
import test from "node:test";

import {
  formatStreamDuration,
  streamGroupState,
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
  });
});

test("consumer group state prioritizes retries then pending then lag", () => {
  assert.equal(streamGroupState({ maxRetryCount: 1, pending: 2, lag: 3 }).label, "Retrying");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 2, lag: 3 }).label, "Pending");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 0, lag: 3 }).label, "Lagging");
  assert.equal(streamGroupState({ maxRetryCount: 0, pending: 0, lag: 0 }).label, "Healthy");
});

test("stream durations remain compact and readable", () => {
  assert.equal(formatStreamDuration(450), "450 ms");
  assert.equal(formatStreamDuration(2_500), "2.5 s");
  assert.equal(formatStreamDuration(90_000), "1.5 min");
});
