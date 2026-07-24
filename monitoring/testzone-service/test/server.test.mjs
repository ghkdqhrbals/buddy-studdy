import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createTestZoneServer } from "../src/server.mjs";
import { TestZoneStore } from "../src/store.mjs";

async function fixture() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-"));
  const store = await new TestZoneStore(dataDir).init();
  const config = {
    port: 0,
    dataDir,
    maxVus: 1000,
    maxTargetRps: 3000,
    maxDurationSeconds: 3600,
    maxConcurrentRuns: 1,
    allowedTargetHosts: [],
    grafanaBaseUrl: "https://grafana.example.test/grafana",
    openAI: { apiKey: "", model: "gpt-5" },
    influx: { url: "", token: "", org: "", bucket: "" },
    componentPassword: "test",
  };
  const runs = {
    active: new Map(),
    async start(run) {
      await store.patchRun(run.id, { status: "running", startedAt: new Date().toISOString() });
    },
    async cancel() {
      return true;
    },
  };
  const assistant = {
    enabled: true,
    async generate() {
      return {
        message: "Generated.",
        code: "import http from \"k6/http\"; export default function () { http.get(`${__ENV.BASE_URL}/health`); }",
      };
    },
  };
  const influx = {
    enabled: true,
    async deleteRun() {
      return { deleted: true };
    },
  };
  const components = {
    async list() {
      return [{ id: "redis", name: "Redis", status: "running" }];
    },
  };
  const created = await createTestZoneServer({ config, store, runs, assistant, influx, components });
  await new Promise((resolve) => created.server.listen(0, "127.0.0.1", resolve));
  const address = created.server.address();
  return {
    ...created,
    baseUrl: `http://127.0.0.1:${address.port}`,
    async close() {
      await new Promise((resolve) => created.server.close(resolve));
      await fs.rm(dataDir, { recursive: true, force: true });
    },
  };
}

test("status and project APIs expose runtime-neutral TestZone state", async (context) => {
  const app = await fixture();
  context.after(() => app.close());

  const status = await fetch(`${app.baseUrl}/api/status`).then((response) => response.json());
  assert.equal(status.maxVus, 1000);
  assert.equal(status.maxTargetRps, 3000);
  assert.equal(status.integrations.openAI, true);

  const projects = await fetch(`${app.baseUrl}/api/projects`).then((response) => response.json());
  assert.equal(projects.projects.length, 1);
  assert.equal("runtime" in projects.projects[0], false);
});

test("run API starts a saved script instead of returning a copied command", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = app.store.state.scripts[0];

  const response = await fetch(`${app.baseUrl}/api/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      scriptId: script.id,
      profile: "custom",
      options: { duration: "30s", vus: 20, maxVus: 100, targetRps: 50 },
      headers: { Authorization: "Bearer test" },
    }),
  });
  assert.equal(response.status, 202);
  const body = await response.json();
  assert.equal(body.run.status, "running");
  assert.equal(body.run.scriptName, script.name);
  assert.deepEqual(body.environmentKeys, ["HEADERS_JSON"]);
});

test("run history preserves the script name after the script is deleted", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = app.store.state.scripts[0];

  const createResponse = await fetch(`${app.baseUrl}/api/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      scriptId: script.id,
      profile: "custom",
      options: { duration: "10s", vus: 1, maxVus: 1, targetRps: 1 },
    }),
  });
  assert.equal(createResponse.status, 202);
  const created = await createResponse.json();
  await app.store.patchRun(created.run.id, { status: "completed" });

  const deleteResponse = await fetch(`${app.baseUrl}/api/scripts/${script.id}`, {
    method: "DELETE",
  });
  assert.equal(deleteResponse.status, 200);

  const history = await fetch(`${app.baseUrl}/api/runs?projectId=${project.id}`)
    .then((response) => response.json());
  assert.equal(history.runs[0].scriptName, script.name);
});

test("script validation rejects unsafe requests with actionable details", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const script = app.store.state.scripts[0];
  const response = await fetch(`${app.baseUrl}/api/scripts/${script.id}/validate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: "export default function () {}; import x from \"https://bad.test/x.js\";" }),
  });
  assert.equal(response.status, 422);
  const body = await response.json();
  assert.ok(body.details.some((entry) => entry.includes("Remote JavaScript imports")));
});

test("store migration repairs the legacy maxVUs template typo", async (context) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-migration-"));
  context.after(() => fs.rm(dataDir, { recursive: true, force: true }));
  const initialStore = await new TestZoneStore(dataDir).init();
  const script = (await initialStore.listScripts())[0];
  await fs.writeFile(
    initialStore.scriptPath(script.id),
    script.code.replace("          maxVUs: maxVus,\n", "          maxVUs,\n"),
  );

  const migratedStore = await new TestZoneStore(dataDir).init();
  const migratedScript = (await migratedStore.listScripts())[0];

  assert.match(migratedScript.code, /maxVUs: maxVus/);
  assert.doesNotMatch(migratedScript.code, /^\s+maxVUs,$/m);
});

test("store migration backfills names for legacy run metadata", async (context) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-run-migration-"));
  context.after(() => fs.rm(dataDir, { recursive: true, force: true }));
  const initialStore = await new TestZoneStore(dataDir).init();
  const script = initialStore.state.scripts[0];
  const run = await initialStore.createRun({
    projectId: script.projectId,
    scriptId: script.id,
    profile: "smoke",
    options: {},
  });
  assert.equal(run.scriptName, undefined);

  const migratedStore = await new TestZoneStore(dataDir).init();
  assert.equal(migratedStore.state.runs[0].scriptName, script.name);
});
