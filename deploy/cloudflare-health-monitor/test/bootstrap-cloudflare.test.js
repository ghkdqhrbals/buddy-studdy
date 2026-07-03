import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildSecretCommands,
  buildSecretPlan,
  parseKvNamespaceId,
  parseWranglerAccountId,
} from "../scripts/bootstrap-cloudflare.js";

test("parseWranglerAccountId reads table output", () => {
  const output = `
Account Name: BuddyStudy
Account ID: 0123456789abcdef0123456789abcdef
`;

  assert.equal(parseWranglerAccountId(output), "0123456789abcdef0123456789abcdef");
});

test("parseWranglerAccountId reads wrangler config style output", () => {
  const output = `account_id = "abcdef0123456789abcdef0123456789"`;

  assert.equal(parseWranglerAccountId(output), "abcdef0123456789abcdef0123456789");
});

test("parseWranglerAccountId returns null when account id is absent", () => {
  assert.equal(parseWranglerAccountId("You are not authenticated."), null);
});

test("parseKvNamespaceId reads JSON output", () => {
  const output = `{ "id": "kv-namespace-id" }`;

  assert.equal(parseKvNamespaceId(output), "kv-namespace-id");
});

test("parseKvNamespaceId reads wrangler binding snippet", () => {
  const output = `
[[kv_namespaces]]
binding = "HEALTH_MONITOR_STATE"
id = "abcdef0123456789abcdef0123456789"
`;

  assert.equal(parseKvNamespaceId(output), "abcdef0123456789abcdef0123456789");
});

test("buildSecretCommands prints repository-scoped commands", () => {
  const commands = buildSecretCommands({
    accountId: "account-id",
    namespaceId: "namespace-id",
    repository: "owner/repo",
  });

  assert.deepEqual(commands, [
    "gh secret set CLOUDFLARE_ACCOUNT_ID --repo owner/repo --body 'account-id'",
    "gh secret set HEALTH_MONITOR_KV_NAMESPACE_ID --repo owner/repo --body 'namespace-id'",
    "gh secret set CLOUDFLARE_API_TOKEN --repo owner/repo",
  ]);
});

test("buildSecretPlan includes non-secret values and skips absent api token value", () => {
  const plan = buildSecretPlan({
    accountId: "account-id",
    namespaceId: "namespace-id",
    hasCloudflareApiToken: false,
    repository: "owner/repo",
  });

  assert.deepEqual(plan, [
    { name: "CLOUDFLARE_ACCOUNT_ID", value: "account-id", requiredValue: true, repository: "owner/repo" },
    { name: "HEALTH_MONITOR_KV_NAMESPACE_ID", value: "namespace-id", requiredValue: true, repository: "owner/repo" },
    { name: "CLOUDFLARE_API_TOKEN", value: null, requiredValue: false, repository: "owner/repo" },
  ]);
});
