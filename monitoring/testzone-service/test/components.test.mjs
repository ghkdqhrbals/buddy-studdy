import test from "node:test";
import assert from "node:assert/strict";
import { ComponentManager } from "../src/components.mjs";

test("ComponentManager deploys, restarts, and removes an approved isolated component", async () => {
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

  const restarted = await manager.restart("redis");
  assert.equal(restarted.status, "running");
  assert.ok(commands.some((entry) => entry.join(" ") === "docker restart buddystudy-testzone-redis"));

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
