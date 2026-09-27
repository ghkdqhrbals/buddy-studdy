import assert from "node:assert/strict";
import test from "node:test";
import { campaignDeepLink, campaignRequest, formatCampaignCTR, MAX_SELECTED_USERS, validateCampaignDraft } from "../src/lib/pushCampaignModel.js";

const draft = { title: " Weekly highlights ", body: " Read this week's discussion. ", destination: "public", questionId: "", audience: "ALL_REGISTERED", userIds: [] };

test("push landing pages are allowlisted and reject arbitrary or malformed routes", () => {
  assert.equal(campaignDeepLink("public"), "buddystudy://public/questions");
  assert.equal(campaignDeepLink("studies"), "buddystudy://studies");
  assert.equal(campaignDeepLink("public-question", "42"), "buddystudy://public/questions/42");
  for (const id of ["0", "-1", "1/records", "1?redirect=evil", "1.5", "abc"]) {
    assert.equal(campaignDeepLink("public-question", id), "");
  }
  assert.equal(campaignDeepLink("https://example.com"), "");
  assert.equal(campaignDeepLink("buddystudy://private/123"), "");
});

test("requests clear stale selected IDs for all-member campaigns and deduplicate selected recipients", () => {
  assert.deepEqual(campaignRequest({ ...draft, userIds: [1, 2] }, "stable-key"), {
    idempotencyKey: "stable-key", title: "Weekly highlights", body: " Read this week's discussion. ", deepLink: "buddystudy://public/questions", audience: "ALL_REGISTERED", userIds: [],
  });
  assert.deepEqual(campaignRequest({ ...draft, audience: "SELECTED_USERS", userIds: [7, 2, 7] }, "retry-key").userIds, [7, 2]);
  assert.equal(campaignRequest(draft, "retry-key").idempotencyKey, "retry-key");
});

test("campaign requests preserve Markdown whitespace and count raw body length", () => {
  const body = "    const answer = 42;\n\nKeep the line break.\n";
  assert.equal(campaignRequest({ ...draft, body }, "markdown-key").body, body);
  assert.equal(validateCampaignDraft({ ...draft, body: ` ${"x".repeat(2000)}` }).length, 1);
  assert.equal(validateCampaignDraft({ ...draft, body: " \n\t " }).length, 1);
});

test("campaign review requires bounded message and explicit selected audience", () => {
  assert.deepEqual(validateCampaignDraft(draft), []);
  assert.equal(validateCampaignDraft({ ...draft, title: " ", body: " " }).length, 2);
  assert.equal(validateCampaignDraft({ ...draft, title: "x".repeat(161), body: "x".repeat(2001) }).length, 2);
  assert.equal(validateCampaignDraft({ ...draft, destination: "public-question", questionId: "" }).length, 1);
  assert.equal(validateCampaignDraft({ ...draft, audience: "SELECTED_USERS" }).length, 1);
  assert.equal(validateCampaignDraft({ ...draft, audience: "SELECTED_USERS", userIds: Array.from({ length: MAX_SELECTED_USERS + 1 }, (_, index) => index + 1) }).length, 1);
  assert.equal(validateCampaignDraft({ ...draft, audience: "SELECTED_USERS", userIds: [1.5] }).length, 1);
  assert.deepEqual(validateCampaignDraft({ ...draft, audience: "SELECTED_USERS", userIds: [1, 2] }), []);
});

test("CTR does not claim zero engagement when no push was accepted", () => {
  assert.equal(formatCampaignCTR({ acceptedCount: 0, clickThroughRate: 0 }), "—");
  assert.equal(formatCampaignCTR({ acceptedCount: 40, clickThroughRate: 0.125 }), "12.5%");
  assert.equal(formatCampaignCTR({ acceptedCount: 40, clickThroughRate: 0 }), "0.0%");
  assert.equal(formatCampaignCTR({ acceptedCount: 40 }), "—");
});
