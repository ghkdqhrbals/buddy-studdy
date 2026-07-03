import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import {
  buildSecretCommands,
  buildDispatchCommand,
  buildRunListCommand,
  buildRunWatchCommand,
  buildSecretPlan,
  parseLatestRunId,
  parseExistingKvNamespaceId,
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

test("buildDispatchCommand targets deploy-only health monitor workflow", () => {
  assert.deepEqual(buildDispatchCommand({ repository: "owner/repo" }), [
    "workflow",
    "run",
    "health-monitor.yml",
    "--repo",
    "owner/repo",
  ]);
});

test("buildRunListCommand reads latest deploy-only health monitor workflow run", () => {
  assert.deepEqual(buildRunListCommand({ repository: "owner/repo" }), [
    "run",
    "list",
    "--workflow",
    "health-monitor.yml",
    "--repo",
    "owner/repo",
    "--json",
    "databaseId,status,conclusion,createdAt",
    "--limit",
    "10",
  ]);
});

test("parseLatestRunId reads first workflow run id", () => {
  assert.equal(parseLatestRunId(JSON.stringify([{ databaseId: 1234, status: "queued" }])), 1234);
});

test("parseLatestRunId can ignore workflow runs created before dispatch", () => {
  const runs = [
    { databaseId: 1233, status: "completed", createdAt: "2026-07-03T00:00:00Z" },
    { databaseId: 1234, status: "queued", createdAt: "2026-07-03T00:00:10Z" },
  ];

  assert.equal(parseLatestRunId(JSON.stringify(runs), { createdAfter: "2026-07-03T00:00:05Z" }), 1234);
});

test("parseLatestRunId returns null when every run predates dispatch", () => {
  const runs = [{ databaseId: 1233, status: "completed", createdAt: "2026-07-03T00:00:00Z" }];

  assert.equal(parseLatestRunId(JSON.stringify(runs), { createdAfter: "2026-07-03T00:00:05Z" }), null);
});

test("parseLatestRunId returns null for invalid run output", () => {
  assert.equal(parseLatestRunId("not-json"), null);
  assert.equal(parseLatestRunId("[]"), null);
});

test("buildRunWatchCommand waits for workflow completion with exit status", () => {
  assert.deepEqual(buildRunWatchCommand(1234, { repository: "owner/repo" }), [
    "run",
    "watch",
    "1234",
    "--repo",
    "owner/repo",
    "--exit-status",
  ]);
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

test("parseExistingKvNamespaceId reads matching namespace from list output", () => {
  const output = JSON.stringify([
    { id: "other-id", title: "OTHER" },
    { id: "health-id", title: "HEALTH_MONITOR_STATE" },
  ]);

  assert.equal(parseExistingKvNamespaceId(output), "health-id");
});

test("parseExistingKvNamespaceId returns null when list output has no match", () => {
  assert.equal(parseExistingKvNamespaceId(JSON.stringify([{ id: "other-id", title: "OTHER" }])), null);
  assert.equal(parseExistingKvNamespaceId("not-json"), null);
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

test("bootstrap rejects workflow dispatch without writing GitHub secrets", () => {
  const result = spawnSync("node", ["scripts/bootstrap-cloudflare.js", "--dispatch-workflow"], {
    cwd: new URL("..", import.meta.url),
    encoding: "utf8",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /requires `--set-github-secrets`/);
});

test("bootstrap rejects workflow watch without dispatching workflow", () => {
  const result = spawnSync("node", ["scripts/bootstrap-cloudflare.js", "--watch-workflow"], {
    cwd: new URL("..", import.meta.url),
    encoding: "utf8",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /requires `--dispatch-workflow`/);
});
