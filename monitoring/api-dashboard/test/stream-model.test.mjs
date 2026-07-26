import assert from "node:assert/strict";
import test from "node:test";

import {
  buildStreamEntriesPath,
  buildStreamEntryPath,
  isRedisStreamId,
  summarizeGroups,
} from "../public/stream-model.js";

test("Redis Stream IDs require millisecond and sequence components", () => {
  assert.equal(isRedisStreamId("1785000998000-0"), true);
  assert.equal(isRedisStreamId(" 1785000998000-17 "), true);
  assert.equal(isRedisStreamId("1785000998000"), false);
  assert.equal(isRedisStreamId("latest"), false);
});

test("entry paths preserve cursor pagination and filters", () => {
  assert.equal(
    buildStreamEntriesPath("question events", {
      cursor: "1785000998000-0",
      limit: 50,
      eventType: "QUESTION_CREATED",
    }),
    "/event-streams/topics/question%20events/entries?limit=50&cursor=1785000998000-0&eventType=QUESTION_CREATED",
  );
  assert.equal(
    buildStreamEntryPath("question events", "1785000998000-0"),
    "/event-streams/topics/question%20events/entries/1785000998000-0",
  );
});

test("consumer groups are summarized without rendering raw HTML", () => {
  assert.equal(summarizeGroups([]), "None");
  assert.equal(
    summarizeGroups([{ name: "push-workers", pending: 3, consumers: 10 }]),
    "push-workers: 3 pending / 10 consumers",
  );
});
