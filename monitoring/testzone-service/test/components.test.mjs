import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { ComponentManager } from "../src/components.mjs";

test("ComponentManager deploys, recreates on restart, and removes an approved isolated component", async () => {
  const commands = [];
  let deployed = false;
  const exec = async (command, args) => {
    commands.push([command, ...args]);
    if (args[0] === "network" && args[1] === "inspect") {
      throw new Error("network missing");
    }
    if (args[0] === "run") deployed = true;
    if (args[0] === "inspect") {
      if (!deployed) throw new Error("container missing");
      return { stdout: "running|\n" };
    }
    if (args[0] === "rm") deployed = false;
    return { stdout: "" };
  };
  const manager = new ComponentManager({ exec, password: "not-a-real-secret" });

  const deployedComponent = await manager.deploy("redis");
  assert.equal(deployedComponent.status, "running");
  assert.ok(commands.some((entry) => entry.join(" ") === "docker network create buddystudy-testzone"));
  assert.ok(commands.some((entry) => entry.join(" ") === "docker pull redis:7.4-alpine"));
  assert.ok(commands.some((entry) => (
    entry[0] === "docker"
    && entry[1] === "run"
    && entry.includes("--network")
    && entry.includes("buddystudy-testzone")
    && entry.includes("--label")
    && entry.includes("testzone.managed=true")
  )));
  const redisRun = commands.find((entry) => entry[0] === "docker" && entry[1] === "run");
  assert.ok(redisRun.includes("--appendonly"));
  assert.ok(redisRun.includes("--appendfsync"));
  assert.ok(redisRun.includes("--aof-use-rdb-preamble"));
  assert.ok(redisRun.includes("--save"));
  assert.ok(redisRun.includes("--rdbcompression"));
  assert.ok(redisRun.includes("--rdbchecksum"));

  const restarted = await manager.restart("redis");
  assert.equal(restarted.status, "running");
  assert.equal(commands.filter((entry) => entry.join(" ") === "docker pull redis:7.4-alpine").length, 2);
  assert.equal(commands.filter((entry) => entry[0] === "docker" && entry[1] === "run").length, 2);

  const deleted = await manager.delete("redis");
  assert.deepEqual(deleted, { id: "redis", status: "not-deployed" });
  assert.ok(commands.some((entry) => entry.join(" ") === "docker rm -f buddystudy-testzone-redis"));
});

test("ComponentManager rejects components outside the fixed catalog", async () => {
  const manager = new ComponentManager({
    exec: async () => ({ stdout: "" }),
    password: "not-a-real-secret",
  });

  await assert.rejects(manager.deploy("arbitrary-image"), /Unknown TestZone component/);
  await assert.rejects(manager.restart("arbitrary-image"), /Unknown TestZone component/);
  await assert.rejects(manager.delete("arbitrary-image"), /Unknown TestZone component/);
});

test("ComponentManager persists private credentials and configurable resource limits", async (context) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "testzone-components-"));
  context.after(() => fs.rm(dataDir, { recursive: true, force: true }));
  const exec = async (_command, args) => {
    if (args[0] === "inspect") throw new Error("container missing");
    return { stdout: "" };
  };
  const first = await new ComponentManager({
    dataDir,
    exec,
    password: "mysql-password",
  }).init();

  await first.updateConfig("mysql", {
    imageTag: "8.4",
    database: "load_zone",
    username: "load_user",
    hostPort: 43306,
    cpus: 2,
    memoryMb: 1024,
    maxConnections: 250,
    innodbBufferPoolMb: 256,
    tmpTableSizeMb: 32,
    maxHeapTableSizeMb: 32,
    waitTimeoutSeconds: 5_000,
    environment: {
      TZ: "UTC",
    },
  });
  const mysql = await first.credentials("mysql");
  const redis = await first.credentials("redis");
  const second = await new ComponentManager({
    dataDir,
    exec,
    password: "ignored-after-initialization",
  }).init();

  assert.equal(mysql.password, "mysql-password");
  assert.equal(mysql.internalUrl, "mysql://load_user:mysql-password@buddystudy-testzone-mysql:3306/load_zone");
  assert.match(redis.internalUrl, /^redis:\/\/:[^@]+@buddystudy-testzone-redis:6379\/0$/);
  assert.deepEqual(await second.credentials("mysql"), mysql);
  const listed = await second.list();
  assert.equal(listed.find((entry) => entry.id === "mysql").config.memoryMb, 1024);
  const savedMysql = listed.find((entry) => entry.id === "mysql").config;
  assert.equal(savedMysql.maxConnections, 250);
  assert.equal(savedMysql.innodbBufferPoolMb, 256);
  assert.deepEqual(savedMysql.environment, { TZ: "UTC" });
  assert.equal(listed.some((entry) => entry.id === "kafka"), false);
  const mode = (await fs.stat(path.join(dataDir, "components.json"))).mode & 0o777;
  assert.equal(mode, 0o600);
});

test("ComponentManager passes key-value environment and MySQL server parameters to Docker", async () => {
  const commands = [];
  let deployed = false;
  const exec = async (command, args) => {
    commands.push([command, ...args]);
    if (args[0] === "network" && args[1] === "inspect") return { stdout: "" };
    if (args[0] === "run") deployed = true;
    if (args[0] === "inspect") {
      if (!deployed) throw new Error("container missing");
      return { stdout: "running||2026-07-25T00:00:00Z\n" };
    }
    return { stdout: "" };
  };
  const manager = new ComponentManager({ exec, password: "secret" });
  await manager.updateConfig("mysql", {
    memoryMb: 1024,
    maxConnections: 250,
    innodbBufferPoolMb: 256,
    tmpTableSizeMb: 32,
    maxHeapTableSizeMb: 32,
    waitTimeoutSeconds: 5_000,
    environment: {
      TZ: "UTC",
    },
  });

  await manager.deploy("mysql");
  const run = commands.find((entry) => entry[0] === "docker" && entry[1] === "run");
  assert.ok(run.includes("TZ=UTC"));
  assert.ok(run.includes("--max-connections=250"));
  assert.ok(run.includes("--innodb-buffer-pool-size=256M"));
  assert.ok(run.includes("--tmp-table-size=32M"));
  assert.ok(run.includes("--max-heap-table-size=32M"));
  assert.ok(run.includes("--wait-timeout=5000"));
});

test("ComponentManager rejects managed and invalid environment variables", async () => {
  const manager = new ComponentManager({
    exec: async () => ({ stdout: "" }),
    password: "secret",
  });

  await assert.rejects(
    manager.updateConfig("mysql", { environment: { MYSQL_PASSWORD: "override" } }),
    /managed by TestZone/,
  );
  await assert.rejects(
    manager.updateConfig("mysql", { environment: { "INVALID-KEY": "five" } }),
    /Invalid environment variable key/,
  );
  await assert.rejects(
    manager.updateConfig("mysql", { maxConnections: 5 }),
    /between 10 and 10000/,
  );
});
