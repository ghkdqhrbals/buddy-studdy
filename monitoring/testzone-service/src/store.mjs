import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";

const DEFAULT_SCRIPT = `import http from "k6/http";
import { check, sleep } from "k6";

const targetRps = Number(__ENV.TARGET_RPS || 0);
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
          maxVUs: maxVus,
        },
      },
    }
  : { vus, duration };

const baseUrl = __ENV.BASE_URL;
const headers = JSON.parse(__ENV.HEADERS_JSON || "{}");

export default function () {
  const response = http.get(\`\${baseUrl}/api/v1/public/questions?limit=20&offset=0\`, {
    headers,
    tags: { api: "public-questions" },
    timeout: "5s",
  });

  check(response, {
    "status is 200": (value) => value.status === 200,
    "questions exist": (value) => Array.isArray(value.json("questions")),
  });
  sleep(0.1);
}
`;

const LEGACY_MAX_VUS_OPTION = "          maxVUs,\n";
const FIXED_MAX_VUS_OPTION = "          maxVUs: maxVus,\n";

function now() {
  return new Date().toISOString();
}

function defaultState() {
  const projectId = crypto.randomUUID();
  const scriptId = crypto.randomUUID();
  return {
    version: 1,
    projects: [{
      id: projectId,
      name: "BuddyStudy API",
      baseUrl: "https://api.ghkdqhrbals.org",
      createdAt: now(),
      updatedAt: now(),
    }],
    scripts: [{
      id: scriptId,
      projectId,
      name: "public-questions.js",
      description: "Public question list read test",
      createdAt: now(),
      updatedAt: now(),
    }],
    runs: [],
  };
}

export class TestZoneStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.statePath = path.join(dataDir, "state.json");
    this.scriptDir = path.join(dataDir, "scripts");
    this.runDir = path.join(dataDir, "runs");
    this.state = null;
    this.writeQueue = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.scriptDir, { recursive: true });
    await fs.mkdir(this.runDir, { recursive: true });
    try {
      this.state = JSON.parse(await fs.readFile(this.statePath, "utf8"));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      this.state = defaultState();
      const script = this.state.scripts[0];
      await fs.writeFile(this.scriptPath(script.id), DEFAULT_SCRIPT, { mode: 0o600 });
      await this.persist();
    }
    await this.migrateScripts();
    await this.migrateRunMetadata();
    return this;
  }

  async migrateScripts() {
    let changed = false;
    await Promise.all(this.state.scripts.map(async (script) => {
      const scriptPath = this.scriptPath(script.id);
      const code = await fs.readFile(scriptPath, "utf8");
      if (!code.includes(LEGACY_MAX_VUS_OPTION)) return;
      await fs.writeFile(
        scriptPath,
        code.replace(LEGACY_MAX_VUS_OPTION, FIXED_MAX_VUS_OPTION),
        { mode: 0o600 },
      );
      script.updatedAt = now();
      changed = true;
    }));
    if (changed) await this.persist();
  }

  async migrateRunMetadata() {
    let changed = false;
    for (const run of this.state.runs) {
      if (!run.scriptName) {
        const script = this.state.scripts.find((entry) => entry.id === run.scriptId);
        if (script) {
          run.scriptName = script.name;
          changed = true;
        }
      }
      if (!run.name) {
        run.name = run.scriptName || `Test run ${run.id.slice(0, 8)}`;
        changed = true;
      }
      if (!Object.hasOwn(run, "live")) {
        run.live = null;
        changed = true;
      }
    }
    if (changed) await this.persist();
  }

  scriptPath(id) {
    return path.join(this.scriptDir, `${id}.js`);
  }

  runPath(id) {
    return path.join(this.runDir, id);
  }

  runSeriesPath(id) {
    return path.join(this.runPath(id), "run-series.jsonl");
  }

  snapshot() {
    return structuredClone(this.state);
  }

  async persist() {
    this.writeQueue = this.writeQueue.then(async () => {
      const temporary = `${this.statePath}.tmp`;
      await fs.writeFile(temporary, `${JSON.stringify(this.state, null, 2)}\n`, { mode: 0o600 });
      await fs.rename(temporary, this.statePath);
    });
    return this.writeQueue;
  }

  async listScripts(projectId = null) {
    const entries = this.state.scripts.filter((script) => !projectId || script.projectId === projectId);
    return Promise.all(entries.map(async (script) => ({
      ...script,
      code: await fs.readFile(this.scriptPath(script.id), "utf8"),
    })));
  }

  async getScript(id) {
    const script = this.state.scripts.find((entry) => entry.id === id);
    if (!script) return null;
    return { ...script, code: await fs.readFile(this.scriptPath(id), "utf8") };
  }

  async createScript(input) {
    const timestamp = now();
    const script = {
      id: crypto.randomUUID(),
      projectId: input.projectId,
      name: input.name,
      description: input.description || "",
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    await fs.writeFile(this.scriptPath(script.id), input.code || DEFAULT_SCRIPT, { mode: 0o600 });
    this.state.scripts.unshift(script);
    await this.persist();
    return this.getScript(script.id);
  }

  async updateScript(id, input) {
    const script = this.state.scripts.find((entry) => entry.id === id);
    if (!script) return null;
    if (input.name) script.name = input.name;
    if (typeof input.description === "string") script.description = input.description;
    script.updatedAt = now();
    if (typeof input.code === "string") {
      await fs.writeFile(this.scriptPath(id), input.code, { mode: 0o600 });
    }
    await this.persist();
    return this.getScript(id);
  }

  async deleteScript(id) {
    const index = this.state.scripts.findIndex((entry) => entry.id === id);
    if (index < 0) return false;
    if (this.state.runs.some((run) => run.scriptId === id && run.status === "running")) {
      throw new Error("A running test still uses this script.");
    }
    this.state.scripts.splice(index, 1);
    await fs.rm(this.scriptPath(id), { force: true });
    await this.persist();
    return true;
  }

  async createProject(input) {
    const timestamp = now();
    const project = {
      id: crypto.randomUUID(),
      name: input.name,
      baseUrl: input.baseUrl,
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    this.state.projects.unshift(project);
    await this.persist();
    return project;
  }

  async updateProject(id, input) {
    const project = this.state.projects.find((entry) => entry.id === id);
    if (!project) return null;
    if (input.name) project.name = input.name;
    if (input.baseUrl) project.baseUrl = input.baseUrl;
    project.updatedAt = now();
    await this.persist();
    return project;
  }

  async createRun(input) {
    const timestamp = now();
    const run = {
      id: crypto.randomUUID(),
      projectId: input.projectId,
      scriptId: input.scriptId,
      scriptName: input.scriptName,
      name: input.name,
      profile: input.profile,
      options: input.options,
      status: "queued",
      createdAt: timestamp,
      startedAt: null,
      finishedAt: null,
      summary: null,
      error: null,
      logTail: [],
      live: null,
    };
    this.state.runs.unshift(run);
    await this.persist();
    return structuredClone(run);
  }

  async patchRun(id, patch) {
    const run = this.state.runs.find((entry) => entry.id === id);
    if (!run) return null;
    Object.assign(run, patch);
    await this.persist();
    return structuredClone(run);
  }

  async appendRunSeries(id, point) {
    await fs.appendFile(this.runSeriesPath(id), `${JSON.stringify(point)}\n`, { mode: 0o600 });
  }

  async readRunSeries(id) {
    try {
      const content = await fs.readFile(this.runSeriesPath(id), "utf8");
      return content
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => JSON.parse(line));
    } catch (error) {
      if (error.code === "ENOENT") return [];
      throw error;
    }
  }

  async readRunScript(id) {
    try {
      return await fs.readFile(path.join(this.runPath(id), "script.js"), "utf8");
    } catch (error) {
      if (error.code === "ENOENT") return null;
      throw error;
    }
  }

  async deleteRun(id) {
    const index = this.state.runs.findIndex((entry) => entry.id === id);
    if (index < 0) return null;
    const [run] = this.state.runs.splice(index, 1);
    await fs.rm(this.runPath(id), { recursive: true, force: true });
    await this.persist();
    return run;
  }
}

export { DEFAULT_SCRIPT };
