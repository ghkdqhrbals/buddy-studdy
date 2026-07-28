import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { createServiceStatusServer } from "../src/server.mjs";

async function fixture() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "buddystudy-status-"));
  const server = await createServiceStatusServer({ dataDirectory: directory });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  return {
    baseURL: `http://127.0.0.1:${address.port}`,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      await fs.rm(directory, { recursive: true, force: true });
    },
  };
}

const maintenance = {
  titleKo: "한국어 제목",
  titleEn: "English title",
  titleJa: "日本語のタイトル",
  messageKo: "한국어 안내",
  messageEn: "English message",
  messageJa: "日本語の案内",
  startsAt: new Date(Date.now() - 1_000).toISOString(),
  endsAt: new Date(Date.now() + 60_000).toISOString(),
};

test("public status localizes an active maintenance window", async () => {
  const app = await fixture();
  try {
    const created = await fetch(`${app.baseURL}/api/v1/admin/service-maintenance`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Monitoring-User": "tester" },
      body: JSON.stringify(maintenance),
    });
    assert.equal(created.status, 201);

    const korean = await fetch(`${app.baseURL}/api/v1/service-status`, {
      headers: { "Accept-Language": "ko" },
    }).then((response) => response.json());
    const english = await fetch(`${app.baseURL}/api/v1/service-status`, {
      headers: { "Accept-Language": "en-US,en;q=0.9" },
    }).then((response) => response.json());

    assert.equal(korean.status, "MAINTENANCE");
    assert.equal(korean.title, maintenance.titleKo);
    assert.equal(english.title, maintenance.titleEn);
  } finally {
    await app.close();
  }
});

test("terminated maintenance becomes operational and remains in history", async () => {
  const app = await fixture();
  try {
    const created = await fetch(`${app.baseURL}/api/v1/admin/service-maintenance`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(maintenance),
    }).then((response) => response.json());

    const terminated = await fetch(
      `${app.baseURL}/api/v1/admin/service-maintenance/${created.id}/terminate`,
      { method: "POST" },
    );
    assert.equal(terminated.status, 200);

    const status = await fetch(`${app.baseURL}/api/v1/service-status`)
      .then((response) => response.json());
    const history = await fetch(
      `${app.baseURL}/api/v1/admin/service-maintenance/history?limit=20&offset=0`,
    ).then((response) => response.json());

    assert.equal(status.status, "OPERATIONAL");
    assert.equal(history.totalCount, 1);
    assert.ok(history.items[0].terminatedAt);
  } finally {
    await app.close();
  }
});
