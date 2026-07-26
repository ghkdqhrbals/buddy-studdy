import assert from "node:assert/strict";
import test from "node:test";

import { parseNestedValue } from "../src/lib/objectInspector.js";

test("nested object inspector expands JSON strings without flattening child objects", () => {
  assert.deepEqual(
    parseNestedValue({
      payloadJson: "{\"question\":{\"id\":17,\"tags\":[\"redis\",\"stream\"]}}",
      lastError: "",
    }),
    {
      payloadJson: { question: { id: 17, tags: ["redis", "stream"] } },
      lastError: "",
    },
  );
});

test("nested object inspector preserves ordinary and malformed strings", () => {
  assert.deepEqual(
    parseNestedValue({ eventType: "QUESTION_CREATED", payload: "{not-json}" }),
    { eventType: "QUESTION_CREATED", payload: "{not-json}" },
  );
});
