import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";

export const DEFAULT_EXECUTION_OPTIONS = `export const options = {
  scenarios: {
    publicQuestions: {
      executor: "constant-arrival-rate",
      rate: 300,
      timeUnit: "1s",
      duration: "30s",
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
};`;

export const DEFAULT_TEST_CONFIG = `export const testConfig = {
  name: "Public questions",
  targetUrl: "https://api.ghkdqhrbals.org",
};`;

export const DEFAULT_SCRIPT = `import http from "k6/http";
import { check, sleep } from "k6";

${DEFAULT_TEST_CONFIG}

${DEFAULT_EXECUTION_OPTIONS}

const headers = {
  Accept: "application/json",
};

export default function () {
  const response = http.get(\`\${testConfig.targetUrl}/api/v1/public/questions?limit=20&offset=0\`, {
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
const MANAGED_EXECUTION_OPTIONS = /const targetRps = Number\(__ENV\.TARGET_RPS \|\| 0\);[\s\S]*?export const options = targetRps > 0[\s\S]*?: \{ vus, duration \};\s*/;

export function stripManagedExecutionOptions(code) {
  return code.replace(MANAGED_EXECUTION_OPTIONS, "").replace(/^\s*\n/, "");
}

export function ensureScriptExecutionOptions(code) {
  if (/export\s+const\s+options\s*=/.test(code)) return code;
  return `${DEFAULT_EXECUTION_OPTIONS}\n\n${code}`;
}

export function ensureScriptTestConfig(code) {
  let migrated = code
    .replace("const baseUrl = __ENV.BASE_URL;", "const baseUrl = testConfig.targetUrl;")
    .replace(
      'const headers = JSON.parse(__ENV.HEADERS_JSON || "{}");',
      'const headers = { Accept: "application/json" };',
    );
  if (/export\s+const\s+testConfig\s*=/.test(migrated)) return migrated;
  migrated = `${DEFAULT_TEST_CONFIG}\n\n${migrated}`;
  return migrated;
}

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
    deployments: [],
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
    if (!Array.isArray(this.state.deployments)) {
      this.state.deployments = [];
      await this.persist();
    }
    return this;
  }

  async migrateScripts() {
    let changed = false;
    await Promise.all(this.state.scripts.map(async (script) => {
      const scriptPath = this.scriptPath(script.id);
      const code = await fs.readFile(scriptPath, "utf8");
      const migrated = ensureScriptTestConfig(
        ensureScriptExecutionOptions(
          stripManagedExecutionOptions(
            code.replace(LEGACY_MAX_VUS_OPTION, FIXED_MAX_VUS_OPTION),
          ),
        ),
      );
      if (migrated === code) return;
      await fs.writeFile(
        scriptPath,
        migrated,
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
      if (!run.targetUrl) {
        const project = this.state.projects.find((entry) => entry.id === run.projectId);
        if (project?.baseUrl) {
          run.targetUrl = project.baseUrl;
          changed = true;
        }
      }
      if (!Object.hasOwn(run, "live")) {
        run.live = null;
        changed = true;
      }
      if (!Object.hasOwn(run, "metricsWarning")) {
        run.metricsWarning = null;
        changed = true;
      }
      if (
        run.status === "failed"
        && run.summary
        && String(run.error || "").startsWith("InfluxDB write failed")
      ) {
        run.status = "completed";
        run.metricsWarning = run.error;
        run.error = null;
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
    const code = typeof input.code === "string" ? input.code : DEFAULT_SCRIPT;
    await fs.writeFile(this.scriptPath(script.id), code, { mode: 0o600 });
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
    project.updatedAt = now();
    await this.persist();
    return project;
  }

  async deleteProject(id) {
    const index = this.state.projects.findIndex((entry) => entry.id === id);
    if (index < 0) return null;
    const projectRuns = this.state.runs.filter((run) => run.projectId === id);
    if (projectRuns.some((run) => ["queued", "running", "cancelling"].includes(run.status))) {
      throw new Error("Cancel active project runs before deleting the project.");
    }
    const projectScripts = this.state.scripts.filter((script) => script.projectId === id);
    const [project] = this.state.projects.splice(index, 1);
    this.state.scripts = this.state.scripts.filter((script) => script.projectId !== id);
    this.state.runs = this.state.runs.filter((run) => run.projectId !== id);
    await Promise.all([
      ...projectScripts.map((script) => fs.rm(this.scriptPath(script.id), { force: true })),
      ...projectRuns.map((run) => fs.rm(this.runPath(run.id), { recursive: true, force: true })),
    ]);
    await this.persist();
    return {
      project,
      scriptIds: projectScripts.map((script) => script.id),
      runIds: projectRuns.map((run) => run.id),
    };
  }

  async createRun(input) {
    const timestamp = now();
    const run = {
      id: crypto.randomUUID(),
      projectId: input.projectId,
      scriptId: input.scriptId,
      scriptName: input.scriptName,
      targetUrl: input.targetUrl,
      name: input.name,
      profile: input.profile,
      options: input.options,
      status: "queued",
      createdAt: timestamp,
      startedAt: null,
      finishedAt: null,
      summary: null,
      error: null,
      metricsWarning: null,
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

  listDeployments({ limit = 20, offset = 0, service = "", status = "" } = {}) {
    const normalizedService = String(service).trim().toLowerCase();
    const normalizedStatus = String(status).trim().toUpperCase();
    const values = this.state.deployments.filter((deployment) => (
      (!normalizedService || deployment.service.toLowerCase() === normalizedService)
      && (!normalizedStatus || deployment.status === normalizedStatus)
    ));
    const since = Date.now() - 24 * 60 * 60 * 1000;
    const activeStatuses = new Set(["QUEUED", "RUNNING"]);
    const current = this.state.deployments.find((deployment) => activeStatuses.has(deployment.status))
      || this.state.deployments[0]
      || null;
    return {
      items: structuredClone(values.slice(offset, offset + limit)),
      totalCount: values.length,
      summary: {
        activeCount: this.state.deployments.filter(
          (deployment) => activeStatuses.has(deployment.status),
        ).length,
        succeeded24h: this.state.deployments.filter(
          (deployment) => deployment.status === "SUCCEEDED"
            && Date.parse(deployment.startedAt) >= since,
        ).length,
        failed24h: this.state.deployments.filter(
          (deployment) => deployment.status === "FAILED"
            && Date.parse(deployment.startedAt) >= since,
        ).length,
        current: current ? structuredClone(current) : null,
      },
    };
  }

  deployment(id) {
    const deployment = this.state.deployments.find((entry) => entry.id === id);
    return deployment ? structuredClone(deployment) : null;
  }

  async upsertDeployment(input) {
    const timestamp = now();
    const existing = this.state.deployments.find((entry) => entry.id === input.id);
    if (existing) {
      Object.assign(existing, input, { updatedAt: timestamp });
    } else {
      this.state.deployments.unshift({
        ...input,
        createdAt: input.createdAt || timestamp,
        updatedAt: timestamp,
      });
    }
    this.state.deployments.sort((left, right) => (
      Date.parse(right.startedAt || right.createdAt) - Date.parse(left.startedAt || left.createdAt)
    ));
    this.state.deployments = this.state.deployments.slice(0, 500);
    await this.persist();
    return this.deployment(input.id);
  }
}
