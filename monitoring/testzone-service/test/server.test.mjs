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
    async reset(id) {
      calls.components.push(["reset", id]);
      return { id, status: "running" };
    },
    async credentials(id) {
      calls.components.push(["credentials", id]);
      return { password: "secret", internalUrl: `redis://:${id}@redis:6379/0` };
    },
    async updateConfig(id, input) {
      calls.components.push(["config", id, input]);
      return { id, status: "not-deployed", config: input };
    },
    async delete(id) {
      calls.components.push(["delete", id]);
      return { id, status: "not-deployed" };
    },
  };
  const created = await createTestZoneServer({ config, store, runs, influx, components });
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
  assert.equal(status.integrations.influxDB, true);
  assert.equal("openAI" in status.integrations, false);

  const projects = await fetch(`${app.baseUrl}/api/projects`).then((response) => response.json());
  assert.equal(projects.projects.length, 1);
  assert.equal("runtime" in projects.projects[0], false);
});

test("project APIs create and delete projects with their scripts, runs, and time-series", async (context) => {
  const app = await fixture();
  context.after(() => app.close());

  const createdResponse = await fetch(`${app.baseUrl}/api/projects`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "Catalog API",
    }),
  });
  assert.equal(createdResponse.status, 201);
  const created = (await createdResponse.json()).project;
  assert.equal("baseUrl" in created, false);
  const script = await app.store.createScript({
    projectId: created.id,
    name: "catalog.js",
    description: "",
    code: "export default function () {}",
  });
  const run = await app.store.createRun({
    projectId: created.id,
    scriptId: script.id,
    scriptName: script.name,
    name: "Catalog read",
    profile: "standard",
    options: {},
  });
  await app.store.patchRun(run.id, { status: "completed" });

  const deletedResponse = await fetch(`${app.baseUrl}/api/projects/${created.id}`, {
    method: "DELETE",
  });
  assert.equal(deletedResponse.status, 200);
  assert.deepEqual(await deletedResponse.json(), {
    deleted: true,
    removedScripts: 1,
    removedRuns: 1,
  });
  assert.equal(app.store.state.projects.some((entry) => entry.id === created.id), false);
  assert.equal(app.store.state.scripts.some((entry) => entry.projectId === created.id), false);
  assert.equal(app.store.state.runs.some((entry) => entry.projectId === created.id), false);
  assert.deepEqual(app.calls.influxDeletes, [run.id]);
  await assert.rejects(fs.stat(app.store.scriptPath(script.id)), { code: "ENOENT" });
  await assert.rejects(fs.stat(app.store.runPath(run.id)), { code: "ENOENT" });
});

test("project deletion rejects active runs", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = await app.store.getScript(app.store.state.scripts[0].id);
  const run = await app.store.createRun({
    projectId: project.id,
    scriptId: script.id,
    scriptName: script.name,
    name: "Active run",
    profile: "standard",
    options: {},
  });
  await app.store.patchRun(run.id, { status: "running" });

  const response = await fetch(`${app.baseUrl}/api/projects/${project.id}`, {
    method: "DELETE",
  });
  assert.equal(response.status, 409);
  assert.equal(app.store.state.projects.some((entry) => entry.id === project.id), true);
  assert.deepEqual(app.calls.influxDeletes, []);
});

test("run API starts a saved script instead of returning a copied command", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = await app.store.getScript(app.store.state.scripts[0].id);

  const response = await fetch(`${app.baseUrl}/api/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      scriptId: script.id,
      targetUrl: "https://api.example.test",
      name: "Public API peak",
      headers: { Authorization: "Bearer test" },
    }),
  });
  assert.equal(response.status, 202);
  const body = await response.json();
  assert.equal(body.run.status, "running");
  assert.equal(body.run.name, "Public API peak");
  assert.equal(body.run.scriptName, script.name);
  assert.equal(body.run.targetUrl, "https://api.example.test");
  assert.equal(body.run.profile, "script");
  assert.deepEqual(body.run.options, {
    duration: "30s",
    durationSeconds: 30,
    vus: 50,
    maxVus: 500,
    targetRps: 300,
  });
  assert.deepEqual(body.environmentKeys, ["HEADERS_JSON"]);

  const secondResponse = await fetch(`${app.baseUrl}/api/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      scriptId: script.id,
      targetUrl: "https://staging.example.test/base/",
      name: "Public API staging",
    }),
  });
  assert.equal(secondResponse.status, 202);
  const second = await secondResponse.json();
  assert.equal(second.run.targetUrl, "https://staging.example.test/base");
  assert.deepEqual(
    new Set(app.store.state.runs.map((run) => run.targetUrl)),
    new Set(["https://api.example.test", "https://staging.example.test/base"]),
  );
});

test("script workspace supports create, edit, and delete", async (context) => {
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
  const script = await app.store.getScript(app.store.state.scripts[0].id);

  const createResponse = await fetch(`${app.baseUrl}/api/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      projectId: project.id,
      scriptId: script.id,
      targetUrl: "https://api.example.test",
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

test("run detail exposes live series and its immutable script snapshot", async (context) => {
  const app = await fixture();
  context.after(() => app.close());
  const project = app.store.state.projects[0];
  const script = await app.store.getScript(app.store.state.scripts[0].id);
  const run = await app.store.createRun({
    projectId: project.id,
    scriptId: script.id,
    scriptName: script.name,
    name: "Live request profile",
    profile: "standard",
    options: {},
  });
  await fs.mkdir(app.store.runPath(run.id), { recursive: true });
  await fs.writeFile(path.join(app.store.runPath(run.id), "script.js"), script.code);
  await app.store.appendRunSeries(run.id, {
    timestamp: "2026-07-24T03:00:00.000Z",
    requestRate: 319,
    p95Ms: 4180,
    errorRate: 0.8026,
    vus: 1000,
  });

  const series = await fetch(`${app.baseUrl}/api/runs/${run.id}/series`)
    .then((response) => response.json());
  const snapshot = await fetch(`${app.baseUrl}/api/runs/${run.id}/script`)
    .then((response) => response.json());

  assert.equal(series.series[0].requestRate, 319);
  assert.equal(snapshot.script.name, script.name);
  assert.equal(snapshot.script.code, script.code);
  assert.equal(snapshot.script.readonly, true);
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

test("component APIs expose configuration, credentials, reset, and lifecycle actions", async (context) => {
  const app = await fixture();
  context.after(() => app.close());

  const deploy = await fetch(`${app.baseUrl}/api/components/redis/deploy`, {
    method: "POST",
  }).then((response) => response.json());
  const restart = await fetch(`${app.baseUrl}/api/components/redis/restart`, {
    method: "POST",
  }).then((response) => response.json());
  const reset = await fetch(`${app.baseUrl}/api/components/redis/reset`, {
    method: "POST",
  }).then((response) => response.json());
  const credentials = await fetch(`${app.baseUrl}/api/components/redis/credentials`)
    .then((response) => response.json());
  const configured = await fetch(`${app.baseUrl}/api/components/redis/config`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ memoryMb: 512 }),
  }).then((response) => response.json());
  const deleted = await fetch(`${app.baseUrl}/api/components/redis`, {
    method: "DELETE",
  }).then((response) => response.json());

  assert.equal(deploy.component.status, "running");
  assert.equal(restart.component.status, "running");
  assert.equal(reset.component.status, "running");
  assert.equal(credentials.credentials.password, "secret");
  assert.equal(configured.component.config.memoryMb, 512);
  assert.equal(deleted.component.status, "not-deployed");
  assert.deepEqual(app.calls.components, [
    ["deploy", "redis"],
    ["restart", "redis"],
    ["reset", "redis"],
    ["credentials", "redis"],
    ["config", "redis", { memoryMb: 512 }],
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
  assert.ok(body.details.some((entry) => entry.message.includes("Remote JavaScript imports")));
  assert.ok(body.details.every((entry) => Number.isInteger(entry.line)));
});

test("store migration removes execution settings from legacy user scripts", async (context) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-migration-"));
  context.after(() => fs.rm(dataDir, { recursive: true, force: true }));
  const initialStore = await new TestZoneStore(dataDir).init();
  const script = (await initialStore.listScripts())[0];
  const legacyOptions = `const targetRps = Number(__ENV.TARGET_RPS || 0);
const vus = Number(__ENV.VUS || 10);
const maxVus = Number(__ENV.MAX_VUS || Math.max(vus, 100));
const duration = __ENV.DURATION || "30s";

export const options = targetRps > 0
  ? {
      scenarios: {
        api: {
          executor: "constant-arrival-rate",
          rate: targetRps,
          timeUnit: "1s",
          duration,
          preAllocatedVUs: Math.min(vus, maxVus),
          maxVUs,
        },
      },
    }
  : { vus, duration };

`;
  await fs.writeFile(
    initialStore.scriptPath(script.id),
    script.code.replace('const baseUrl = __ENV.BASE_URL;\n', `${legacyOptions}const baseUrl = __ENV.BASE_URL;\n`),
  );

  const migratedStore = await new TestZoneStore(dataDir).init();
  const migratedScript = (await migratedStore.listScripts())[0];

  assert.doesNotMatch(migratedScript.code, /TARGET_RPS|MAX_VUS/);
  assert.match(migratedScript.code, /export const options/);
  assert.match(migratedScript.code, /export default function/);
});

test("store migration backfills names for legacy run metadata", async (context) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-run-migration-"));
  context.after(() => fs.rm(dataDir, { recursive: true, force: true }));
  const initialStore = await new TestZoneStore(dataDir).init();
  const script = initialStore.state.scripts[0];
  initialStore.state.projects[0].baseUrl = "https://legacy.example.test";
  await initialStore.persist();
  const run = await initialStore.createRun({
    projectId: script.projectId,
    scriptId: script.id,
    profile: "smoke",
    options: {},
  });
  assert.equal(run.scriptName, undefined);

  const migratedStore = await new TestZoneStore(dataDir).init();
  assert.equal(migratedStore.state.runs[0].scriptName, script.name);
  assert.equal(migratedStore.state.runs[0].targetUrl, "https://legacy.example.test");
});
