import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyAuditEvent,
  filterAuditEntries,
  paginateAuditEntries,
  summarizeAuditEntries,
} from "../public/audit-model.js";

const entries = [
  { method: "GET", path: "/api/v1/studies", status: 200, clientIp: "10.0.0.1", requestId: "a" },
  { method: "POST", path: "/api/v1/studies", status: 201, clientIp: "10.0.0.1", requestId: "b" },
  { method: "POST", path: "/api/v1/auth/google", status: 200, clientIp: "10.0.0.2", requestId: "c" },
  { method: "GET", path: "/api/v1/profile", status: 500, clientIp: "10.0.0.3", requestId: "d" },
];

test("audit events prioritize failures, then auth and mutations", () => {
  assert.deepEqual(entries.map(classifyAuditEvent), [
    "access",
    "mutation",
    "authentication",
    "failure",
  ]);
});

test("audit filters match event, IP, path, and request ID", () => {
  assert.deepEqual(
    filterAuditEntries(entries, { eventType: "mutation" }).map((entry) => entry.requestId),
    ["b"],
  );
  assert.deepEqual(
    filterAuditEntries(entries, { ip: "10.0.0.1" }).map((entry) => entry.requestId),
    ["a", "b"],
  );
  assert.equal(filterAuditEntries(entries, { search: "auth/google" }).length, 1);
  assert.equal(filterAuditEntries(entries, { search: "/profile" }).length, 1);
});

test("audit summary and pagination remain deterministic", () => {
  assert.deepEqual(summarizeAuditEntries(entries), {
    total: 4,
    uniqueIps: 3,
    mutations: 1,
    failures: 1,
  });
  assert.deepEqual(paginateAuditEntries(entries, 2, 2), {
    items: entries.slice(2),
    page: 2,
    totalPages: 2,
  });
});
