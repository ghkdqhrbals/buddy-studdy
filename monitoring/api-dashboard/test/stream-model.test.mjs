import assert from "node:assert/strict";
import test from "node:test";

import {
  streamEntriesPath,
  streamEntryPath,
  streamPendingPath,
  isRedisStreamId,
} from "../src/lib/streamPaths.js";

test("Redis Stream IDs require millisecond and sequence components", () => {
  assert.equal(isRedisStreamId("1785000998000-0"), true);
  assert.equal(isRedisStreamId(" 1785000998000-17 "), true);
  assert.equal(isRedisStreamId("1785000998000"), false);
  assert.equal(isRedisStreamId("latest"), false);
});

test("entry paths preserve cursor pagination and filters", () => {
  assert.equal(
    streamEntriesPath("question events", {
      cursor: "1785000998000-0",
      limit: 50,
      eventType: "QUESTION_CREATED",
    }),
    "/event-streams/topics/question%20events/entries?limit=50&cursor=1785000998000-0&eventType=QUESTION_CREATED",
  );
  assert.equal(
    streamEntryPath("question events", "1785000998000-0"),
    "/event-streams/topics/question%20events/entries/1785000998000-0",
  );
  assert.equal(
    streamPendingPath("question events", "grading workers", {
      cursor: "1785000998000-4",
      limit: 20,
    }),
    "/event-streams/topics/question%20events/groups/grading%20workers/pending?limit=20&cursor=1785000998000-4",
  );
});
