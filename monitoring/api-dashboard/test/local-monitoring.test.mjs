import test from "node:test";
import assert from "node:assert/strict";
import { allowsLocalMonitoring } from "../src/admin/localMonitoring.js";

test("local monitoring requires explicit opt-in and a loopback hostname", () => {
  for (const host of ["localhost", "127.0.0.1", "[::1]", "::1"]) {
    assert.equal(allowsLocalMonitoring("true", host), true);
    for (const flag of [undefined, "false", "", true]) assert.equal(allowsLocalMonitoring(flag, host), false);
  }
  for (const host of [undefined, "grafana.lowfidev.cloud", "localhost.example.com", "192.168.1.2", ""]) {
    assert.equal(allowsLocalMonitoring("true", host), false);
  }
});
