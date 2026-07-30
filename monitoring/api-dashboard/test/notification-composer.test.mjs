import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const sourceURL = new URL("../src/components/AdminNotificationComposer.jsx", import.meta.url);
const usersPageURL = new URL("../src/pages/UsersPage.jsx", import.meta.url);
const feedbackPageURL = new URL("../src/pages/FeedbackPage.jsx", import.meta.url);

test("notification destinations omit the obsolete My Studies preset", async () => {
  const source = await readFile(sourceURL, "utf8");

  assert.doesNotMatch(source, /value:\s*"buddystudy:\/\/studies"/);
  assert.doesNotMatch(source, /label:\s*"My Studies"/);
  assert.match(source, /value:\s*"buddystudy:\/\/home\/message"/);
  assert.match(source, /value:\s*"buddystudy:\/\/records"/);
});

test("direct user pushes and feedback replies remain separate workflows", async () => {
  const [usersPage, feedbackPage] = await Promise.all([
    readFile(usersPageURL, "utf8"),
    readFile(feedbackPageURL, "utf8"),
  ]);

  assert.match(usersPage, /endpoint=\{`\/users\/\$\{selected\.id\}\/notifications`\}/);
  assert.match(usersPage, /title="Send push to this user"/);
  assert.doesNotMatch(usersPage, /\/feedback\/\$\{selected\.id\}\/notifications/);

  assert.match(feedbackPage, /endpoint=\{`\/feedback\/\$\{selected\.id\}\/notifications`\}/);
  assert.match(feedbackPage, /title="Reply to this feedback"/);
  assert.doesNotMatch(feedbackPage, /\/users\/\$\{selected\.id\}\/notifications/);
});
