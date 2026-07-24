import http from "node:http";
import { fileURLToPath } from "node:url";
import { loadConfig } from "./config.mjs";
import { ComponentManager } from "./components.mjs";
import { InfluxWriter } from "./influx.mjs";
import { K6Assistant } from "./openai.mjs";
import { RunManager } from "./runner.mjs";
import { TestZoneStore } from "./store.mjs";
import {
  normalizeRunOptions,
  validateScript,
  validateScriptReport,
  validateTargetHost,
  ValidationError,
} from "./validation.mjs";

const MAX_BODY_BYTES = 1_000_000;

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

function safeEnvironment(values = {}) {
  const entries = Object.entries(values);
  if (entries.length > 30) throw new ValidationError("At most 30 environment variables are allowed.");
  const result = {};
  for (const [rawKey, rawValue] of entries) {
    const key = String(rawKey).trim();
    if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(key)) {
      throw new ValidationError(`Invalid environment variable name: ${key}`);
    }
    const value = String(rawValue ?? "");
    if (value.length > 8_000) throw new ValidationError(`${key} exceeds 8,000 characters.`);
    result[key] = value;
  }
  return result;
}

export async function createTestZoneServer(dependencies = {}) {
  const config = dependencies.config || loadConfig();
  const store = dependencies.store || await new TestZoneStore(config.dataDir).init();
  const influx = dependencies.influx || new InfluxWriter(config.influx);
  const assistant = dependencies.assistant || new K6Assistant(config.openAI);
  const components = dependencies.components || await new ComponentManager({
    password: config.componentPassword,
    dataDir: config.dataDir,
  }).init();
  const runs = dependencies.runs || new RunManager({ store, influx, config });
  const componentSampleIntervalMs = Number(dependencies.componentSampleIntervalMs ?? 5_000);

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
            openAI: assistant.enabled,
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
          baseUrl: validateTargetHost(body.baseUrl, config.allowedTargetHosts),
        });
        return sendJson(response, 201, { project });
      }
      let match = routeMatch(pathname, /^\/api\/projects\/([^/]+)$/);
      if (match && request.method === "PATCH") {
        const body = await readJson(request);
        const project = await store.updateProject(match[0], {
          name: body.name ? requireText(body.name, "Project name") : undefined,
          baseUrl: body.baseUrl ? validateTargetHost(body.baseUrl, config.allowedTargetHosts) : undefined,
        });
        return project ? sendJson(response, 200, { project }) : sendJson(response, 404, { error: "Project not found." });
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
        validateScript(body.code || "", { maxVus: config.maxVus });
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
        if (typeof body.code === "string") validateScript(body.code, { maxVus: config.maxVus });
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
          maxDurationSeconds: config.maxDurationSeconds,
          duration: body.duration,
          targetBaseUrl: body.baseUrl,
        });
        return sendJson(response, 200, { validation });
      }

      match = routeMatch(pathname, /^\/api\/scripts\/([^/]+)\/ai$/);
      if (match && request.method === "POST") {
        const body = await readJson(request);
        const script = await store.getScript(match[0]);
        if (!script) return sendJson(response, 404, { error: "Script not found." });
        const project = store.state.projects.find((entry) => entry.id === script.projectId);
        const result = await assistant.generate({
          prompt: requireText(body.prompt, "AI request", 4_000),
          currentCode: typeof body.currentCode === "string" ? body.currentCode : script.code,
          projectName: project.name,
          baseUrl: project.baseUrl,
        });
        const validation = validateScriptReport(result.code, {
          maxVus: config.maxVus,
          targetBaseUrl: project.baseUrl,
        });
        return sendJson(response, 200, { result, validation });
      }

      if (request.method === "GET" && pathname === "/api/runs") {
        const projectId = requestUrl.searchParams.get("projectId");
        const values = store.state.runs
          .filter((run) => !projectId || run.projectId === projectId)
          .map((run) => publicRun(run, config));
        return sendJson(response, 200, { runs: values });
      }
      if (request.method === "POST" && pathname === "/api/runs") {
        const body = await readJson(request);
        const project = store.state.projects.find((entry) => entry.id === body.projectId);
        const script = await store.getScript(body.scriptId);
        if (!project || !script || script.projectId !== project.id) {
          return sendJson(response, 404, { error: "Project or script not found." });
        }
        project.baseUrl = validateTargetHost(project.baseUrl, config.allowedTargetHosts);
        const options = normalizeRunOptions(body.options || {}, config);
        const environment = safeEnvironment(body.environment || {});
        environment.HEADERS_JSON = JSON.stringify(body.headers || {});
        const run = await store.createRun({
          projectId: project.id,
          scriptId: script.id,
          scriptName: script.name,
          name: requireText(body.name || script.name, "Test name", 120),
          profile: String(body.profile || "custom").slice(0, 40),
          options,
        });
        await runs.start(run, project, script, environment);
        return sendJson(response, 202, {
          run: publicRun({ ...run, status: "running" }, config),
          environmentKeys: Object.keys(environment).sort(),
        });
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

  return { server, config, store, influx, assistant, components, runs };
}

const executedDirectly = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (executedDirectly) {
  const { server, config } = await createTestZoneServer();
  server.listen(config.port, "0.0.0.0", () => {
    console.log(JSON.stringify({ level: "info", event: "testzone_started", port: config.port }));
  });
}
