import test from "node:test";
import assert from "node:assert/strict";
import { parseAssistantResult } from "../src/openai.mjs";

test("parseAssistantResult reads structured Responses API output", () => {
  const result = parseAssistantResult({
    output: [{
      content: [{
        type: "output_text",
        text: JSON.stringify({ message: "Updated checks.", code: "export default function () {}" }),
      }],
    }],
  });
  assert.equal(result.message, "Updated checks.");
  assert.match(result.code, /export default/);
});
