import assert from "node:assert/strict";
import { test } from "node:test";
import { configureKvNamespace } from "../scripts/set-kv-namespace.js";

test("configureKvNamespace updates existing health monitor namespace", () => {
  const config = {
    kv_namespaces: [
      { binding: "OTHER", id: "other-id" },
      { binding: "HEALTH_MONITOR_STATE", id: "old-id", preview_id: "old-preview" },
    ],
  };

  const updated = configureKvNamespace(config, "new-id");

  assert.deepEqual(updated.kv_namespaces, [
    { binding: "OTHER", id: "other-id" },
    { binding: "HEALTH_MONITOR_STATE", id: "new-id", preview_id: "old-preview" },
  ]);
});

test("configureKvNamespace adds missing health monitor namespace", () => {
  const updated = configureKvNamespace({}, "new-id");

  assert.deepEqual(updated.kv_namespaces, [{ binding: "HEALTH_MONITOR_STATE", id: "new-id" }]);
});

test("configureKvNamespace rejects blank namespace ids", () => {
  assert.throws(() => configureKvNamespace({}, "  "), /namespace id/);
});
