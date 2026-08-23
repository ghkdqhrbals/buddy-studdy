import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyAuditEvent,
  filterAuditEntries,
  paginateAuditEntries,
  parseMonitoringAccessLog,
  summarizeAuditEntries,
} from "../src/lib/auditModel.js";

const entries = [
  { method: "GET", path: "/system.html", status: 200, clientIp: "10.0.0.1", requestId: "a", user: "admin" },
  { method: "POST", path: "/testzone/api/runs", status: 201, clientIp: "10.0.0.1", requestId: "b", user: "admin" },
  { method: "GET", path: "/audit.html", status: 401, clientIp: "10.0.0.2", requestId: "c", user: "" },
  { method: "GET", path: "/settings.html", status: 403, clientIp: "10.0.0.3", requestId: "d", user: "viewer" },
].map((entry) => ({ ...entry, eventType: classifyAuditEvent(entry) }));

test("monitoring audit classifies pages, actions, and denied access", () => {
  assert.deepEqual(entries.map((entry) => entry.eventType), [
    "page",
    "action",
    "denied",
    "denied",
  ]);
});

test("monitoring access JSON is parsed without flattening sensitive data", () => {
  const value = [
    "1784952000123000000",
    JSON.stringify({
      event: "monitoring_access",
      requestId: "request-1",
      clientIp: "203.0.113.9",
      forwardedFor: "203.0.113.9",
      user: "operator",
      method: "GET",
      path: "/system.html",
      query: "from=now-1h",
      status: 200,
      durationSeconds: 0.125,
      userAgent: "Browser",
      referer: "https://monitoring.lowfidev.cloud/",
    }),
  ];

  assert.deepEqual(parseMonitoringAccessLog(value), {
    nanoseconds: value[0],
    timestampMs: 1784952000123,
    requestId: "request-1",
    clientIp: "203.0.113.9",
    forwardedFor: "203.0.113.9",
    user: "operator",
    method: "GET",
    path: "/system.html?from=now-1h",
    status: 200,
    durationMs: 125,
    userAgent: "Browser",
    referer: "https://monitoring.lowfidev.cloud/",
    eventType: "page",
  });
});

test("audit filters match event, IP, page, request ID, and user", () => {
  assert.deepEqual(
    filterAuditEntries(entries, { eventType: "action" }).map((entry) => entry.requestId),
    ["b"],
  );
  assert.deepEqual(
    filterAuditEntries(entries, { ip: "10.0.0.1" }).map((entry) => entry.requestId),
    ["a", "b"],
  );
  assert.equal(filterAuditEntries(entries, { search: "settings" }).length, 1);
  assert.equal(filterAuditEntries(entries, { search: "viewer" }).length, 1);
});

test("audit summary and pagination remain deterministic", () => {
  assert.deepEqual(summarizeAuditEntries(entries), {
    total: 4,
    uniqueIps: 3,
    pageViews: 1,
    denied: 2,
  });
  assert.deepEqual(paginateAuditEntries(entries, 2, 2), {
    items: entries.slice(2),
    page: 2,
    totalPages: 2,
  });
});
