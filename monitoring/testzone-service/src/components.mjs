import crypto from "node:crypto";
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { ValidationError } from "./validation.mjs";

const exec = promisify(execFile);
const NETWORK = "buddystudy-testzone";

export const COMPONENT_CATALOG = [
  {
    id: "postgres",
    name: "PostgreSQL",
    containerPort: 5432,
    defaultConfig: {
      imageTag: "16-alpine",
      database: "testzone",
      username: "testzone",
      hostPort: 35432,
      cpus: 1,
      memoryMb: 512,
    },
  },
  {
    id: "redis",
    name: "Redis",
    containerPort: 6379,
    defaultConfig: {
      imageTag: "7.4-alpine",
      hostPort: 36379,
      cpus: 0.5,
      memoryMb: 256,
      maxMemoryMb: 192,
      evictionPolicy: "allkeys-lru",
    },
  },
];

const IMAGE_TAGS = {
  postgres: new Set(["16-alpine", "17-alpine"]),
  redis: new Set(["7.4-alpine", "8-alpine"]),
};
const REDIS_POLICIES = new Set(["allkeys-lru", "allkeys-lfu", "volatile-lru", "noeviction"]);

function definition(id) {
  const component = COMPONENT_CATALOG.find((entry) => entry.id === id);
  if (!component) throw new ValidationError(`Unknown TestZone component: ${id}`);
  return component;
}

function boundedNumber(value, name, minimum, maximum) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < minimum || number > maximum) {
    throw new ValidationError(`${name} must be between ${minimum} and ${maximum}.`);
  }
  return number;
}

function identifier(value, name) {
  const normalized = String(value ?? "").trim();
  if (!/^[a-z][a-z0-9_]{0,62}$/i.test(normalized)) {
    throw new ValidationError(`${name} must use letters, numbers, and underscores.`);
  }
  return normalized;
}

function parseMemory(value = "") {
  const match = String(value).match(/([\d.]+)\s*([KMG]i?B)/i);
  if (!match) return null;
  const unit = match[2].toUpperCase();
  const multiplier = unit.startsWith("G") ? 1024 : unit.startsWith("M") ? 1 : 1 / 1024;
  return Number(match[1]) * multiplier;
}

function parseStats(raw) {
  if (!raw.trim()) return null;
  try {
    const value = JSON.parse(raw.trim().split(/\r?\n/).at(-1));
    return {
      cpuPercent: Number.parseFloat(String(value.CPUPerc || "0").replace("%", "")) || 0,
      memoryUsedMb: parseMemory(String(value.MemUsage || "").split("/")[0]) || 0,
      memoryLimitMb: parseMemory(String(value.MemUsage || "").split("/")[1]) || 0,
      memoryPercent: Number.parseFloat(String(value.MemPerc || "0").replace("%", "")) || 0,
      networkIO: value.NetIO || "",
      blockIO: value.BlockIO || "",
      processes: Number.parseInt(value.PIDs || "0", 10) || 0,
    };
  } catch {
    return null;
  }
}

export class ComponentManager {
  constructor(options = {}) {
    this.exec = options.exec || exec;
    this.dataDir = options.dataDir || null;
    this.statePath = this.dataDir ? path.join(this.dataDir, "components.json") : null;
    this.initialPassword = options.password || crypto.randomBytes(24).toString("base64url");
    this.state = null;
  }

  async init() {
    if (this.state) return this;
    if (this.dataDir) {
      await fs.mkdir(this.dataDir, { recursive: true });
      try {
        this.state = JSON.parse(await fs.readFile(this.statePath, "utf8"));
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
    }
    if (!this.state) {
      this.state = {
        version: 1,
        components: Object.fromEntries(COMPONENT_CATALOG.map((component) => [
          component.id,
          {
            config: structuredClone(component.defaultConfig),
            password: component.id === "postgres"
              ? this.initialPassword
              : crypto.randomBytes(24).toString("base64url"),
          },
        ])),
      };
      await this.persist();
    } else {
      let changed = false;
      for (const component of COMPONENT_CATALOG) {
        if (this.state.components?.[component.id]) continue;
        this.state.components ||= {};
        this.state.components[component.id] = {
          config: structuredClone(component.defaultConfig),
          password: component.id === "postgres"
            ? this.initialPassword
            : crypto.randomBytes(24).toString("base64url"),
        };
        changed = true;
      }
      if (changed) await this.persist();
    }
    return this;
  }

  async persist() {
    if (!this.statePath) return;
    const temporary = `${this.statePath}.tmp`;
    await fs.writeFile(temporary, `${JSON.stringify(this.state, null, 2)}\n`, { mode: 0o600 });
    await fs.rename(temporary, this.statePath);
  }

  name(id) {
    return `buddystudy-testzone-${id}`;
  }

  volume(id) {
    return `${this.name(id)}-data`;
  }

  image(id, config) {
    return id === "postgres"
      ? `postgres:${config.imageTag}`
      : `redis:${config.imageTag}`;
  }

  async componentState(id) {
    await this.init();
    definition(id);
    return this.state.components[id];
  }

  async ensureNetwork() {
    try {
      await this.exec("docker", ["network", "inspect", NETWORK]);
    } catch {
      await this.exec("docker", ["network", "create", NETWORK]);
    }
  }

  publicEndpoint(id, config) {
    if (id === "postgres") {
      return `postgresql://${config.username}:[configured-password]@${this.name(id)}:5432/${config.database}`;
    }
    return `redis://:[configured-password]@${this.name(id)}:6379/0`;
  }

  async inspect(id) {
    let status = "not-deployed";
    let detail = "";
    let startedAt = null;
    try {
      const { stdout } = await this.exec("docker", [
        "inspect",
        "--format",
        "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.StartedAt}}",
        this.name(id),
      ]);
      [status, detail, startedAt] = stdout.trim().split("|");
    } catch {
      return { status, detail, startedAt, metrics: null };
    }
    let metrics = null;
    try {
      const { stdout } = await this.exec("docker", [
        "stats",
        "--no-stream",
        "--format",
        "{{json .}}",
        this.name(id),
      ]);
      metrics = parseStats(stdout);
    } catch {
      // Container state remains usable even if Docker statistics are unavailable.
    }
    return { status, detail, startedAt, metrics };
  }

  async list() {
    await this.init();
    const results = [];
    for (const component of COMPONENT_CATALOG) {
      const state = this.state.components[component.id];
      const runtime = await this.inspect(component.id);
      results.push({
        id: component.id,
        name: component.name,
        image: this.image(component.id, state.config),
        endpoint: this.publicEndpoint(component.id, state.config),
        hostEndpoint: component.id === "postgres"
          ? `postgresql://${state.config.username}:[configured-password]@127.0.0.1:${state.config.hostPort}/${state.config.database}`
          : `redis://:[configured-password]@127.0.0.1:${state.config.hostPort}/0`,
        config: structuredClone(state.config),
        ...runtime,
      });
    }
    return results;
  }

  async credentials(id) {
    const state = await this.componentState(id);
    const config = state.config;
    if (id === "postgres") {
      return {
        username: config.username,
        password: state.password,
        database: config.database,
        internalUrl: `postgresql://${config.username}:${state.password}@${this.name(id)}:5432/${config.database}`,
        hostUrl: `postgresql://${config.username}:${state.password}@127.0.0.1:${config.hostPort}/${config.database}`,
      };
    }
    return {
      password: state.password,
      internalUrl: `redis://:${state.password}@${this.name(id)}:6379/0`,
      hostUrl: `redis://:${state.password}@127.0.0.1:${config.hostPort}/0`,
    };
  }

  async updateConfig(id, input) {
    const component = definition(id);
    const state = await this.componentState(id);
    const current = state.config;
    const next = { ...current };
    if (input.imageTag !== undefined) {
      const imageTag = String(input.imageTag);
      if (!IMAGE_TAGS[id].has(imageTag)) throw new ValidationError(`Unsupported ${component.name} image tag.`);
      next.imageTag = imageTag;
    }
    if (input.hostPort !== undefined) next.hostPort = boundedNumber(input.hostPort, "Host port", 1024, 65535);
    if (input.cpus !== undefined) next.cpus = boundedNumber(input.cpus, "CPU limit", 0.1, 8);
    if (input.memoryMb !== undefined) next.memoryMb = boundedNumber(input.memoryMb, "Memory limit", 64, 8192);
    if (id === "postgres") {
      if (input.database !== undefined) next.database = identifier(input.database, "Database");
      if (input.username !== undefined) next.username = identifier(input.username, "Username");
    } else {
      if (input.maxMemoryMb !== undefined) next.maxMemoryMb = boundedNumber(input.maxMemoryMb, "Redis max memory", 32, 4096);
      if (next.maxMemoryMb >= next.memoryMb) {
        throw new ValidationError("Redis max memory must be lower than the container memory limit.");
      }
      if (input.evictionPolicy !== undefined) {
        if (!REDIS_POLICIES.has(input.evictionPolicy)) throw new ValidationError("Unsupported Redis eviction policy.");
        next.evictionPolicy = input.evictionPolicy;
      }
    }
    state.config = next;
    await this.persist();
    return (await this.list()).find((entry) => entry.id === id);
  }

  runArguments(id, state) {
    const component = definition(id);
    const config = state.config;
    const args = [
      "run", "-d",
      "--name", this.name(id),
      "--network", NETWORK,
      "--restart", "unless-stopped",
      "--label", "testzone.managed=true",
      "--cpus", String(config.cpus),
      "--memory", `${config.memoryMb}m`,
      "-p", `127.0.0.1:${config.hostPort}:${component.containerPort}`,
    ];
    if (id === "postgres") {
      args.push(
        "-e", `POSTGRES_DB=${config.database}`,
        "-e", `POSTGRES_USER=${config.username}`,
        "-e", `POSTGRES_PASSWORD=${state.password}`,
        "-v", `${this.volume(id)}:/var/lib/postgresql/data`,
        this.image(id, config),
      );
    } else {
      args.push(
        "-v", `${this.volume(id)}:/data`,
        this.image(id, config),
        "redis-server",
        "--requirepass", state.password,
        "--maxmemory", `${config.maxMemoryMb}mb`,
        "--maxmemory-policy", config.evictionPolicy,
        "--appendonly", "yes",
        "--dir", "/data",
      );
    }
    return args;
  }

  async deploy(id) {
    const state = await this.componentState(id);
    await this.ensureNetwork();
    await this.exec("docker", ["pull", this.image(id, state.config)]);
    await this.exec("docker", ["rm", "-f", this.name(id)]).catch(() => {});
    await this.exec("docker", this.runArguments(id, state));
    return (await this.list()).find((entry) => entry.id === id);
  }

  async restart(id) {
    await this.componentState(id);
    await this.exec("docker", ["restart", this.name(id)]);
    return (await this.list()).find((entry) => entry.id === id);
  }

  async reset(id) {
    await this.componentState(id);
    await this.exec("docker", ["rm", "-f", this.name(id)]).catch(() => {});
    await this.exec("docker", ["volume", "rm", "-f", this.volume(id)]).catch(() => {});
    return this.deploy(id);
  }

  async delete(id) {
    await this.componentState(id);
    await this.exec("docker", ["rm", "-f", this.name(id)]).catch(() => {});
    await this.exec("docker", ["volume", "rm", "-f", this.volume(id)]).catch(() => {});
    return { id, status: "not-deployed" };
  }
}
