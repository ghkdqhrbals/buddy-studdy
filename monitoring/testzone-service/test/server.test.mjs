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
  const calls = {
    assistant: [],
    components: [],
    influxDeletes: [],
  };
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
    async generate(input) {
      calls.assistant.push(input);
      return {
        message: "Generated.",
        code: "import http from \"k6/http\"; export default function () { http.get(`${__ENV.BASE_URL}/health`); }",
      };
    },
  };
  const influx = {
    enabled: true,
    async deleteRun(id) {
      calls.influxDeletes.push(id);
      return { deleted: true };
    },
  };
  const components = {
    async list() {
      return [{ id: "redis", name: "Redis", status: "running" }];
    },
    async deploy(id) {
      calls.components.push(["deploy", id]);
      return { id, status: "running" };
    },
    async restart(id) {
      calls.components.push(["restart", id]);
      return { id, status: "running" };
    },
    async delete(id) {
      calls.components.push(["delete", id]);
      return { id, status: "not-deployed" };
    },
  };
  const created = await createTestZoneServer({ config, store, runs, assistant, influx, components });
  await new Promise((resolve) => created.server.listen(0, "127.0.0.1", resolve));
  const address = created.server.address();
  return {
    ...created,
    calls,
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

test("script workspace supports create, edit, AI draft, and delete", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const template = await app.store.getScript(app.store.state.scripts[0].id);

  const createResponse = await fetch(`${app.baseUrl}/api/scripts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      name: "studies.js",
      description: "Authenticated studies read",
      code: template.code,
    }),
  });
  assert.equal(createResponse.status, 201);
  const created = await createResponse.json();

  const updateResponse = await fetch(`${app.baseUrl}/api/scripts/${created.script.id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "studies-read.js",
      code: `${template.code}\n`,
    }),
  });
  assert.equal(updateResponse.status, 200);
  const updated = await updateResponse.json();
  assert.equal(updated.script.name, "studies-read.js");
  assert.ok(updated.script.code.endsWith("\n\n"));

  const assistantResponse = await fetch(`${app.baseUrl}/api/scripts/${created.script.id}/ai`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      prompt: "Add an authenticated studies request.",
      currentCode: updated.script.code,
    }),
  });
  assert.equal(assistantResponse.status, 200);
  const draft = await assistantResponse.json();
  assert.equal(draft.result.message, "Generated.");
  assert.equal(app.calls.assistant[0].projectName, project.name);
  assert.equal(app.calls.assistant[0].baseUrl, project.baseUrl);

  const deleteResponse = await fetch(`${app.baseUrl}/api/scripts/${created.script.id}`, {
    method: "DELETE",
  });
  assert.equal(deleteResponse.status, 200);
  assert.equal(await app.store.getScript(created.script.id), null);
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

test("run deletion removes metadata, artifacts, and matching InfluxDB series", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = app.store.state.scripts[0];
  const run = await app.store.createRun({
    projectId: project.id,
    scriptId: script.id,
    scriptName: script.name,
    profile: "smoke",
    options: {},
  });
  await app.store.patchRun(run.id, { status: "completed" });
  await fs.mkdir(app.store.runPath(run.id), { recursive: true });
  await fs.writeFile(path.join(app.store.runPath(run.id), "summary.json"), "{}");

  const response = await fetch(`${app.baseUrl}/api/runs/${run.id}`, {
    method: "DELETE",
  });

  assert.equal(response.status, 200);
  assert.deepEqual(app.calls.influxDeletes, [run.id]);
  assert.equal(app.store.state.runs.some((entry) => entry.id === run.id), false);
  await assert.rejects(fs.stat(app.store.runPath(run.id)), { code: "ENOENT" });
});

test("component APIs expose deploy, restart, and delete lifecycle actions", async (context) => {
  const app = await fixture();
  context.after(() => app.close());

  const deploy = await fetch(`${app.baseUrl}/api/components/redis/deploy`, {
    method: "POST",
  }).then((response) => response.json());
  const restart = await fetch(`${app.baseUrl}/api/components/redis/restart`, {
    method: "POST",
  }).then((response) => response.json());
  const deleted = await fetch(`${app.baseUrl}/api/components/redis`, {
    method: "DELETE",
  }).then((response) => response.json());

  assert.equal(deploy.component.status, "running");
  assert.equal(restart.component.status, "running");
  assert.equal(deleted.component.status, "not-deployed");
  assert.deepEqual(app.calls.components, [
    ["deploy", "redis"],
    ["restart", "redis"],
    ["delete", "redis"],
  ]);
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
