import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

test("log-search CLI finds MCP failures and follows the parent HTTP trace", async (t) => {
  const queries = [];
  const payload = {
    requestId: "mcp-test-call", parentRequestId: "http-test-parent", protocol: "mcp", transport: "http",
    method: "MCP", path: "/mcp/tools/call/list_studies", toolName: "list_studies", status: 403,
    durationMs: "17.25", requestBody: { name: "list_studies", arguments: {} },
    responseBody: { error: { code: "PERMISSION_DENIED", status: 403, message: "Permission is denied." } },
  };
  const timestamp = (BigInt(Date.now()) * 1_000_000n).toString();
  const values = [[timestamp, `INFO McpExchangeLogger : mcp_exchange ${JSON.stringify(payload)}`]];
  const server = createServer((request, response) => {
    queries.push(new URL(request.url, "http://localhost").searchParams.get("query"));
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ data: { result: [{ values }] } }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const script = fileURLToPath(new URL("../scripts/codex-log-search.mjs", import.meta.url));
  const { stdout } = await promisify(execFile)(process.execPath, [
    script, "--lokiUrl", `http://127.0.0.1:${server.address().port}`, "--method", "MCP", "--path", "list_studies",
  ], { timeout: 10_000, env: { ...process.env, MONITORING_BASIC_AUTH: "" } });
  assert.match(queries[0], /\(api\|mcp\)_exchange/);
  assert.ok(queries[0].includes('\\"method\\":\\"MCP\\"'));
  assert.match(queries[1], /mcp-test-call\|http-test-parent/);
  assert.match(stdout, /MCP \/mcp\/tools\/call\/list_studies status=403 duration=17.3ms/);
  assert.match(stdout, /\*Error\*: PERMISSION_DENIED Permission is denied\./);
  assert.doesNotMatch(stdout, /\*Error\*: none/);
});
