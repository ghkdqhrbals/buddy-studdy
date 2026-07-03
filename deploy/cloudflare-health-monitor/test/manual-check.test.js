import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { test } from "node:test";

const scriptPath = path.resolve(import.meta.dirname, "..", "scripts", "manual-check.js");

test("manual check rejects non-HTTPS public monitor URLs before sending the token", () => {
  const result = spawnSync(
    process.execPath,
    [scriptPath],
    {
      env: {
        ...process.env,
        HEALTH_MONITOR_URL: "http://monitor.example.com",
        MANUAL_CHECK_TOKEN: "secret-token",
      },
      encoding: "utf8",
    },
  );

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /HTTPS/i);
  assert.doesNotMatch(result.stderr, /secret-token/);
});
