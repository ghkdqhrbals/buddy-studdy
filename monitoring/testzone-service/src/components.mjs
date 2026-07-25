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
    id: "mysql",
    name: "MySQL",
    containerPort: 3306,
    defaultConfig: {
      imageTag: "8.4",
      database: "testzone",
      username: "testzone",
      hostPort: 33306,
      cpus: 1,
      memoryMb: 512,
      maxConnections: 100,
      innodbBufferPoolMb: 256,
      tmpTableSizeMb: 32,
      maxHeapTableSizeMb: 32,
      waitTimeoutSeconds: 28_800,
      environment: {},
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
      environment: {},
    },
  },
];

const IMAGE_TAGS = {
  mysql: new Set(["8.0", "8.4"]),
  redis: new Set(["7.4-alpine", "8-alpine"]),
};
const REDIS_POLICIES = new Set(["allkeys-lru", "allkeys-lfu", "volatile-lru", "noeviction"]);
const ENVIRONMENT_KEY = /^[A-Z_][A-Z0-9_]{0,127}$/;
const RESERVED_ENVIRONMENT_KEYS = new Set([
  "MYSQL_DATABASE",
  "MYSQL_USER",
  "MYSQL_PASSWORD",
  "MYSQL_ROOT_PASSWORD",
]);

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

function boundedInteger(value, name, minimum, maximum) {
  const number = boundedNumber(value, name, minimum, maximum);
  if (!Number.isInteger(number)) {
    throw new ValidationError(`${name} must be an integer between ${minimum} and ${maximum}.`);
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

function environment(input) {
  if (input === undefined) return undefined;
  if (!input || Array.isArray(input) || typeof input !== "object") {
    throw new ValidationError("Environment variables must be a key-value object.");
  }
  const entries = Object.entries(input);
  if (entries.length > 50) throw new ValidationError("At most 50 environment variables are allowed.");
  return Object.fromEntries(entries.map(([key, value]) => {
    const normalizedKey = String(key).trim().toUpperCase();
    if (!ENVIRONMENT_KEY.test(normalizedKey)) {
      throw new ValidationError(`Invalid environment variable key: ${key}`);
    }
    if (RESERVED_ENVIRONMENT_KEYS.has(normalizedKey)) {
      throw new ValidationError(`${normalizedKey} is managed by TestZone.`);
    }
    const normalizedValue = String(value ?? "");
    if (normalizedValue.length > 4_096) {
      throw new ValidationError(`${normalizedKey} exceeds 4096 characters.`);
    }
    return [normalizedKey, normalizedValue];
  }));
}

function parseMysqlMetrics(raw) {
  const values = String(raw || "").trim().split(/\s+/);
  if (values.length < 5) return {};
  return {
    connections: Number(values[0]) || 0,
    maxConnections: Number(values[1]) || 0,
    activeConnections: Number(values[2]) || 0,
    databaseSizeBytes: Number(values[3]) || 0,
    cacheHitRatio: Math.max(0, Math.min(1, Number(values[4]) || 0)),
  };
}

function parseRedisMetrics(raw) {
  const values = Object.fromEntries(String(raw || "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#") && line.includes(":"))
    .map((line) => {
      const index = line.indexOf(":");
      return [line.slice(0, index), line.slice(index + 1)];
    }));
  const hits = Number(values.keyspace_hits) || 0;
  const misses = Number(values.keyspace_misses) || 0;
  return {
    redisUsedMemoryBytes: Number(values.used_memory) || 0,
    redisMaxMemoryBytes: Number(values.maxmemory) || 0,
    connectedClients: Number(values.connected_clients) || 0,
    operationsPerSecond: Number(values.instantaneous_ops_per_sec) || 0,
    cacheHitRatio: hits + misses > 0 ? hits / (hits + misses) : 0,
  };
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
            password: component.id === "mysql"
              ? this.initialPassword
              : crypto.randomBytes(24).toString("base64url"),
          },
        ])),
      };
      await this.persist();
    } else {
      let changed = false;
      if (this.state.components?.postgres && !this.state.components.mysql) {
        const legacy = this.state.components.postgres;
        this.state.components.mysql = {
          config: {
            ...structuredClone(definition("mysql").defaultConfig),
            cpus: legacy.config?.cpus ?? 1,
            memoryMb: legacy.config?.memoryMb ?? 512,
            environment: {},
          },
          password: legacy.password || this.initialPassword,
        };
        delete this.state.components.postgres;
        changed = true;
      }
      for (const component of COMPONENT_CATALOG) {
        if (this.state.components?.[component.id]) {
          const current = this.state.components[component.id].config || {};
          const legacyMaxConnections = component.id === "mysql"
            ? Number(current.environment?.MYSQL_MAX_CONNECTIONS)
            : null;
          const merged = {
            ...structuredClone(component.defaultConfig),
            ...current,
            environment: {
              ...component.defaultConfig.environment,
              ...(current.environment || {}),
            },
          };
          if (component.id === "mysql") {
            if (current.maxConnections === undefined
              && Number.isInteger(legacyMaxConnections)
              && legacyMaxConnections >= 10
              && legacyMaxConnections <= 10_000) {
              merged.maxConnections = legacyMaxConnections;
            }
            delete merged.environment.MYSQL_MAX_CONNECTIONS;
          }
          if (JSON.stringify(current) !== JSON.stringify(merged)) {
            this.state.components[component.id].config = merged;
            changed = true;
          }
          continue;
        }
        this.state.components ||= {};
        this.state.components[component.id] = {
          config: structuredClone(component.defaultConfig),
          password: component.id === "mysql"
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
    return id === "mysql"
      ? `mysql:${config.imageTag}`
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
    if (id === "mysql") {
      return `mysql://${config.username}:[configured-password]@${this.name(id)}:3306/${config.database}`;
    }
    return `redis://:[configured-password]@${this.name(id)}:6379/0`;
  }

  async inspect(id, state = null) {
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
    if (metrics && state) {
      try {
        if (id === "mysql") {
          const { stdout } = await this.exec("docker", [
            "exec",
            this.name(id),
            "mysql",
            "--batch",
            "--skip-column-names",
            `--user=${state.config.username}`,
            `--password=${state.password}`,
            state.config.database,
            "--execute",
            `select (select count(*) from information_schema.processlist), @@global.max_connections, (select count(*) from information_schema.processlist where command <> 'Sleep'), coalesce((select sum(data_length + index_length) from information_schema.tables where table_schema = database()), 0), coalesce((select 1 - reads.variable_value / nullif(requests.variable_value, 0) from performance_schema.global_status reads join performance_schema.global_status requests where reads.variable_name = 'Innodb_buffer_pool_reads' and requests.variable_name = 'Innodb_buffer_pool_read_requests'), 0);`,
          ]);
          Object.assign(metrics, parseMysqlMetrics(stdout));
        } else {
          const { stdout } = await this.exec("docker", [
            "exec",
            this.name(id),
            "redis-cli",
            "--no-auth-warning",
            "-a", state.password,
            "INFO",
          ]);
          Object.assign(metrics, parseRedisMetrics(stdout));
        }
      } catch {
        // Container CPU and memory remain available when native statistics are unavailable.
      }
    }
    return { status, detail, startedAt, metrics };
  }

  async list() {
    await this.init();
    const results = [];
    for (const component of COMPONENT_CATALOG) {
      const state = this.state.components[component.id];
      const runtime = await this.inspect(component.id, state);
      results.push({
        id: component.id,
        name: component.name,
        image: this.image(component.id, state.config),
        endpoint: this.publicEndpoint(component.id, state.config),
        hostEndpoint: component.id === "mysql"
          ? `mysql://${state.config.username}:[configured-password]@127.0.0.1:${state.config.hostPort}/${state.config.database}`
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
    if (id === "mysql") {
      return {
        username: config.username,
        password: state.password,
        database: config.database,
        internalUrl: `mysql://${config.username}:${state.password}@${this.name(id)}:3306/${config.database}`,
        hostUrl: `mysql://${config.username}:${state.password}@127.0.0.1:${config.hostPort}/${config.database}`,
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
    const nextEnvironment = environment(input.environment);
    if (nextEnvironment !== undefined) next.environment = nextEnvironment;
    if (id === "mysql") {
      if (input.database !== undefined) next.database = identifier(input.database, "Database");
      if (input.username !== undefined) next.username = identifier(input.username, "Username");
      if (input.maxConnections !== undefined) {
        next.maxConnections = boundedInteger(input.maxConnections, "Max connections", 10, 10_000);
      }
      if (input.innodbBufferPoolMb !== undefined) {
        next.innodbBufferPoolMb = boundedInteger(input.innodbBufferPoolMb, "InnoDB buffer pool", 32, 6144);
      }
      if (input.tmpTableSizeMb !== undefined) {
        next.tmpTableSizeMb = boundedInteger(input.tmpTableSizeMb, "Temporary table size", 1, 1024);
      }
      if (input.maxHeapTableSizeMb !== undefined) {
        next.maxHeapTableSizeMb = boundedInteger(input.maxHeapTableSizeMb, "Maximum heap table size", 1, 1024);
      }
      if (input.waitTimeoutSeconds !== undefined) {
        next.waitTimeoutSeconds = boundedInteger(input.waitTimeoutSeconds, "Wait timeout", 1, 604_800);
      }
      if (next.innodbBufferPoolMb >= next.memoryMb) {
        throw new ValidationError("InnoDB buffer pool must be lower than the container memory limit.");
      }
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
    for (const [key, value] of Object.entries(config.environment || {})) {
      args.push("-e", `${key}=${value}`);
    }
    if (id === "mysql") {
      args.push(
        "-e", `MYSQL_DATABASE=${config.database}`,
        "-e", `MYSQL_USER=${config.username}`,
        "-e", `MYSQL_PASSWORD=${state.password}`,
        "-e", `MYSQL_ROOT_PASSWORD=${state.password}`,
        "-v", `${this.volume(id)}:/var/lib/mysql`,
        this.image(id, config),
        `--max-connections=${config.maxConnections}`,
        `--innodb-buffer-pool-size=${config.innodbBufferPoolMb}M`,
        `--tmp-table-size=${config.tmpTableSizeMb}M`,
        `--max-heap-table-size=${config.maxHeapTableSizeMb}M`,
        `--wait-timeout=${config.waitTimeoutSeconds}`,
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
        "--appendfsync", "everysec",
        "--aof-use-rdb-preamble", "yes",
        "--auto-aof-rewrite-percentage", "100",
        "--auto-aof-rewrite-min-size", "64mb",
        "--save", "3600", "1",
        "--save", "300", "100",
        "--save", "60", "10000",
        "--rdbcompression", "yes",
        "--rdbchecksum", "yes",
        "--dbfilename", "dump.rdb",
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
    return this.deploy(id);
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
