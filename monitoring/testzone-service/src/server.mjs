import http from "node:http";
import { fileURLToPath } from "node:url";
import { loadConfig } from "./config.mjs";
import { ComponentManager } from "./components.mjs";
import { InfluxWriter } from "./influx.mjs";
import { RunManager } from "./runner.mjs";
import { TestZoneStore } from "./store.mjs";
import {
  validateScript,
  ValidationError,
} from "./validation.mjs";

const MAX_BODY_BYTES = 1_000_000;
const RUN_HISTORY_PAGE_SIZE = 10;

function sendJson(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new ValidationError("Request body exceeds 1 MB.");
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new ValidationError("Request body must be valid JSON.");
  }
}

function requireText(value, name, maximum = 200) {
  const text = String(value ?? "").trim();
  if (!text) throw new ValidationError(`${name} is required.`);
  if (text.length > maximum) throw new ValidationError(`${name} exceeds ${maximum} characters.`);
  return text;
}

function routeMatch(pathname, pattern) {
  const match = pathname.match(pattern);
  return match ? match.slice(1).map(decodeURIComponent) : null;
}

function publicRun(run, config) {
  const from = run.startedAt ? Date.parse(run.startedAt) : Date.now() - 3_600_000;
  const to = run.finishedAt ? Date.parse(run.finishedAt) : Date.now();
  const dashboard = new URL("/d/testzone-runs/testzone-runs", config.grafanaBaseUrl);
  dashboard.searchParams.set("from", String(from));
  dashboard.searchParams.set("to", String(to));
  dashboard.searchParams.set("var-run_id", run.id);
  return { ...run, grafanaUrl: dashboard.toString() };
}

export async function createTestZoneServer(dependencies = {}) {
  const config = dependencies.config || loadConfig();
  const store = dependencies.store || await new TestZoneStore(config.dataDir).init();
  const influx = dependencies.influx || new InfluxWriter(config.influx);
  const components = dependencies.components || await new ComponentManager({
    password: config.componentPassword,
    dataDir: config.dataDir,
  }).init();
  const runs = dependencies.runs || new RunManager({ store, influx, config });
  const componentSampleIntervalMs = Number(dependencies.componentSampleIntervalMs ?? 5_000);

  async function startScriptRun(project, script) {
    const validation = validateScript(script.code, {
      maxVus: config.maxVus,
      maxTargetRps: config.maxTargetRps,
      maxDurationSeconds: config.maxDurationSeconds,
    });
    const {
      targetUrl,
      name,
      ...executionOptions
    } = validation.execution;
    const run = await store.createRun({
      projectId: project.id,
      scriptId: script.id,
      scriptName: script.name,
      targetUrl,
      name: name || script.name.replace(/\.js$/i, ""),
      profile: "script",
      options: executionOptions,
    });
    await runs.start(run, script);
    return publicRun({ ...run, status: "running" }, config);
  }

  for (const run of store.state.runs) {
    if (["queued", "running", "cancelling"].includes(run.status)) {
      await store.patchRun(run.id, {
        status: "interrupted",
        finishedAt: new Date().toISOString(),
        error: "TestZone service restarted before this run completed.",
      });
    }
  }

  const server = http.createServer(async (request, response) => {
    const requestUrl = new URL(request.url, "http://testzone.local");
    const { pathname } = requestUrl;
    try {
      if (request.method === "GET" && pathname === "/health") {
        return sendJson(response, 200, { ok: true, service: "BuddyStudy TestZone" });
      }
      if (request.method === "GET" && pathname === "/api/status") {
        return sendJson(response, 200, {
          service: "ready",
          maxVus: config.maxVus,
          maxTargetRps: config.maxTargetRps,
          maxDurationSeconds: config.maxDurationSeconds,
          maxConcurrentRuns: config.maxConcurrentRuns,
          integrations: {
            influxDB: influx.enabled,
            components: true,
          },
          activeRuns: runs.active?.size ?? 0,
        });
      }

      if (request.method === "GET" && pathname === "/api/projects") {
        return sendJson(response, 200, { projects: store.snapshot().projects });
      }
      if (request.method === "POST" && pathname === "/api/projects") {
        const body = await readJson(request);
        const project = await store.createProject({
          name: requireText(body.name, "Project name"),
        });
        return sendJson(response, 201, { project });
      }
      let match = routeMatch(pathname, /^\/api\/projects\/([^/]+)$/);
      if (match && request.method === "PATCH") {
        const body = await readJson(request);
        const project = await store.updateProject(match[0], {
          name: body.name ? requireText(body.name, "Project name") : undefined,
        });
        return project ? sendJson(response, 200, { project }) : sendJson(response, 404, { error: "Project not found." });
      }
      if (match && request.method === "DELETE") {
        const project = store.state.projects.find((entry) => entry.id === match[0]);
        if (!project) return sendJson(response, 404, { error: "Project not found." });
        const projectRuns = store.state.runs.filter((run) => run.projectId === project.id);
        if (projectRuns.some((run) => ["queued", "running", "cancelling"].includes(run.status))) {
          return sendJson(response, 409, { error: "Cancel active project runs before deleting the project." });
        }
        await Promise.all(projectRuns.map((run) => influx.deleteRun(run.id)));
        const deleted = await store.deleteProject(project.id);
        return sendJson(response, 200, {
          deleted: true,
          removedScripts: deleted.scriptIds.length,
          removedRuns: deleted.runIds.length,
        });
      }

      if (request.method === "GET" && pathname === "/api/scripts") {
        const scripts = await store.listScripts(requestUrl.searchParams.get("projectId"));
        return sendJson(response, 200, { scripts });
      }
      if (request.method === "POST" && pathname === "/api/scripts") {
        const body = await readJson(request);
        if (!store.state.projects.some((entry) => entry.id === body.projectId)) {
          return sendJson(response, 404, { error: "Project not found." });
        }
        validateScript(body.code || "", {
          maxVus: config.maxVus,
          maxTargetRps: config.maxTargetRps,
          maxDurationSeconds: config.maxDurationSeconds,
        });
        const script = await store.createScript({
          projectId: body.projectId,
          name: requireText(body.name, "Script name"),
          description: String(body.description || "").slice(0, 500),
          code: body.code,
        });
        return sendJson(response, 201, { script });
      }
      match = routeMatch(pathname, /^\/api\/scripts\/([^/]+)$/);
      if (match && request.method === "GET") {
        const script = await store.getScript(match[0]);
        return script ? sendJson(response, 200, { script }) : sendJson(response, 404, { error: "Script not found." });
      }
      if (match && request.method === "PATCH") {
        const body = await readJson(request);
        if (typeof body.code === "string") {
          validateScript(body.code, {
            maxVus: config.maxVus,
            maxTargetRps: config.maxTargetRps,
            maxDurationSeconds: config.maxDurationSeconds,
          });
        }
        const script = await store.updateScript(match[0], {
          name: body.name ? requireText(body.name, "Script name") : undefined,
          description: body.description,
          code: body.code,
        });
        return script ? sendJson(response, 200, { script }) : sendJson(response, 404, { error: "Script not found." });
      }
      if (match && request.method === "DELETE") {
        const deleted = await store.deleteScript(match[0]);
        return deleted ? sendJson(response, 200, { deleted: true }) : sendJson(response, 404, { error: "Script not found." });
      }

      match = routeMatch(pathname, /^\/api\/scripts\/([^/]+)\/validate$/);
      if (match && request.method === "POST") {
        const body = await readJson(request);
        const script = await store.getScript(match[0]);
        const code = typeof body.code === "string" ? body.code : script?.code;
        if (!code) return sendJson(response, 404, { error: "Script not found." });
        const validation = validateScript(code, {
          maxVus: config.maxVus,
          maxTargetRps: config.maxTargetRps,
          maxDurationSeconds: config.maxDurationSeconds,
        });
        return sendJson(response, 200, { validation });
      }

      if (request.method === "GET" && pathname === "/api/runs") {
        const projectId = requestUrl.searchParams.get("projectId");
        const values = store.state.runs
          .filter((run) => !projectId || run.projectId === projectId)
          .map((run) => publicRun(run, config));
        const requestedPage = Math.max(1, Number.parseInt(requestUrl.searchParams.get("page") || "1", 10) || 1);
        const total = values.length;
        const totalPages = Math.max(1, Math.ceil(total / RUN_HISTORY_PAGE_SIZE));
        const page = Math.min(requestedPage, totalPages);
        const offset = (page - 1) * RUN_HISTORY_PAGE_SIZE;
        return sendJson(response, 200, {
          runs: values.slice(offset, offset + RUN_HISTORY_PAGE_SIZE),
          pagination: {
            page,
            pageSize: RUN_HISTORY_PAGE_SIZE,
            total,
            totalPages,
          },
        });
      }
      if (request.method === "POST" && pathname === "/api/runs") {
        const body = await readJson(request);
        const unexpectedFields = Object.keys(body).filter((key) => !["projectId", "scriptId"].includes(key));
        if (unexpectedFields.length) {
          throw new ValidationError(
            `Run settings belong in the script. Remove: ${unexpectedFields.sort().join(", ")}.`,
          );
        }
        const project = store.state.projects.find((entry) => entry.id === body.projectId);
        const script = await store.getScript(body.scriptId);
        if (!project || !script || script.projectId !== project.id) {
          return sendJson(response, 404, { error: "Project or script not found." });
        }
        return sendJson(response, 202, { run: await startScriptRun(project, script) });
      }
      match = routeMatch(pathname, /^\/api\/runs\/([^/]+)$/);
      if (match && request.method === "GET") {
        const run = store.state.runs.find((entry) => entry.id === match[0]);
        return run ? sendJson(response, 200, { run: publicRun(run, config) }) : sendJson(response, 404, { error: "Run not found." });
      }
      if (match && request.method === "DELETE") {
        const run = store.state.runs.find((entry) => entry.id === match[0]);
        if (!run) return sendJson(response, 404, { error: "Run not found." });
        if (["queued", "running", "cancelling"].includes(run.status)) {
          return sendJson(response, 409, { error: "Cancel the active run before deleting it." });
        }
        await influx.deleteRun(run.id);
        await store.deleteRun(run.id);
        return sendJson(response, 200, { deleted: true });
      }
      match = routeMatch(pathname, /^\/api\/runs\/([^/]+)\/rerun$/);
      if (match && request.method === "POST") {
        const sourceRun = store.state.runs.find((entry) => entry.id === match[0]);
        if (!sourceRun) return sendJson(response, 404, { error: "Run not found." });
        if (["queued", "running", "cancelling"].includes(sourceRun.status)) {
          return sendJson(response, 409, { error: "An active run cannot be rerun." });
        }
        const project = store.state.projects.find((entry) => entry.id === sourceRun.projectId);
        const code = await store.readRunScript(sourceRun.id);
        if (!project || code === null) {
          return sendJson(response, 404, { error: "Project or run script snapshot not found." });
        }
        const script = {
          id: sourceRun.scriptId,
          projectId: sourceRun.projectId,
          name: sourceRun.scriptName || `${sourceRun.name || "test"}.js`,
          code,
        };
        return sendJson(response, 202, { run: await startScriptRun(project, script) });
      }
      match = routeMatch(pathname, /^\/api\/runs\/([^/]+)\/cancel$/);
      if (match && request.method === "POST") {
        const cancelled = await runs.cancel(match[0]);
        return cancelled ? sendJson(response, 202, { cancelling: true }) : sendJson(response, 409, { error: "Run is not active." });
      }
      match = routeMatch(pathname, /^\/api\/runs\/([^/]+)\/series$/);
      if (match && request.method === "GET") {
        const run = store.state.runs.find((entry) => entry.id === match[0]);
        if (!run) return sendJson(response, 404, { error: "Run not found." });
        return sendJson(response, 200, { series: await store.readRunSeries(run.id) });
      }
      match = routeMatch(pathname, /^\/api\/runs\/([^/]+)\/script$/);
      if (match && request.method === "GET") {
        const run = store.state.runs.find((entry) => entry.id === match[0]);
        if (!run) return sendJson(response, 404, { error: "Run not found." });
        const code = await store.readRunScript(run.id);
        return code === null
          ? sendJson(response, 404, { error: "Run script snapshot not found." })
          : sendJson(response, 200, {
            script: {
              id: run.scriptId,
              name: run.scriptName,
              code,
              readonly: true,
            },
          });
      }

      if (request.method === "GET" && pathname === "/api/components") {
        return sendJson(response, 200, { components: await components.list() });
      }
      match = routeMatch(pathname, /^\/api\/components\/([^/]+)\/(deploy|restart|reset)$/);
      if (match && request.method === "POST") {
        const operation = {
          deploy: () => components.deploy(match[0]),
          restart: () => components.restart(match[0]),
          reset: () => components.reset(match[0]),
        }[match[1]];
        return sendJson(response, 200, { component: await operation() });
      }
      match = routeMatch(pathname, /^\/api\/components\/([^/]+)\/credentials$/);
      if (match && request.method === "GET") {
        return sendJson(response, 200, { credentials: await components.credentials(match[0]) });
      }
      match = routeMatch(pathname, /^\/api\/components\/([^/]+)\/config$/);
      if (match && request.method === "PUT") {
        const body = await readJson(request);
        return sendJson(response, 200, { component: await components.updateConfig(match[0], body) });
      }
      match = routeMatch(pathname, /^\/api\/components\/([^/]+)$/);
      if (match && request.method === "DELETE") {
        return sendJson(response, 200, { component: await components.delete(match[0]) });
      }

      return sendJson(response, 404, { error: "TestZone endpoint not found." });
    } catch (error) {
      const validation = error instanceof ValidationError;
      console.error(JSON.stringify({
        level: validation ? "warn" : "error",
        event: "testzone_request_failed",
        method: request.method,
        path: pathname,
        message: error.message,
      }));
      return sendJson(response, validation ? 422 : 500, {
        error: error.message,
        details: error.details || [],
      });
    }
  });

  let componentSampleInProgress = false;
  const sampleComponents = async () => {
    if (!influx.enabled || componentSampleInProgress || typeof influx.writeComponentSnapshots !== "function") return;
    componentSampleInProgress = true;
    try {
      await influx.writeComponentSnapshots(await components.list());
    } catch (error) {
      console.error(JSON.stringify({
        level: "warn",
        event: "testzone_component_metrics_failed",
        message: error.message,
      }));
    } finally {
      componentSampleInProgress = false;
    }
  };
  const componentSampleTimer = componentSampleIntervalMs > 0
    ? setInterval(() => void sampleComponents(), componentSampleIntervalMs)
    : null;
  componentSampleTimer?.unref();
  server.on("close", () => {
    if (componentSampleTimer) clearInterval(componentSampleTimer);
  });

  return { server, config, store, influx, components, runs };
}

const executedDirectly = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (executedDirectly) {
  const { server, config } = await createTestZoneServer();
  server.listen(config.port, "0.0.0.0", () => {
    console.log(JSON.stringify({ level: "info", event: "testzone_started", port: config.port }));
  });
}
