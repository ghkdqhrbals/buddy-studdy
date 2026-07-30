import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const sourceURL = new URL("../src/components/AdminNotificationComposer.jsx", import.meta.url);

test("notification destinations omit the obsolete My Studies preset", async () => {
  const source = await readFile(sourceURL, "utf8");

  assert.doesNotMatch(source, /value:\s*"buddystudy:\/\/studies"/);
  assert.doesNotMatch(source, /label:\s*"My Studies"/);
  assert.match(source, /value:\s*"buddystudy:\/\/home\/message"/);
  assert.match(source, /value:\s*"buddystudy:\/\/records"/);
});
