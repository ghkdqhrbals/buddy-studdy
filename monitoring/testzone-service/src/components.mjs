import { execFile } from "node:child_process";
import { promisify } from "node:util";

const exec = promisify(execFile);
const NETWORK = "buddystudy-testzone";

export const COMPONENT_CATALOG = [
  {
    id: "postgres",
    name: "PostgreSQL 16",
    image: "postgres:16-alpine",
    endpoint: "postgresql://testzone:<password>@buddystudy-testzone-postgres:5432/testzone",
    env(password) {
      return ["POSTGRES_DB=testzone", "POSTGRES_USER=testzone", `POSTGRES_PASSWORD=${password}`];
    },
  },
  {
    id: "redis",
    name: "Redis 7",
    image: "redis:7.4-alpine",
    endpoint: "redis://buddystudy-testzone-redis:6379",
    env() {
      return [];
    },
  },
  {
    id: "kafka",
    name: "Kafka 3",
    image: "apache/kafka:3.9.0",
    endpoint: "buddystudy-testzone-kafka:9092",
    env() {
      return [
        "KAFKA_NODE_ID=1",
        "KAFKA_PROCESS_ROLES=broker,controller",
        "KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093",
        "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://buddystudy-testzone-kafka:9092",
        "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
        "KAFKA_CONTROLLER_QUORUM_VOTERS=1@buddystudy-testzone-kafka:9093",
        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
      ];
    },
  },
];

function definition(id) {
  const component = COMPONENT_CATALOG.find((entry) => entry.id === id);
  if (!component) throw new Error(`Unknown TestZone component: ${id}`);
  return component;
}

export class ComponentManager {
  constructor(options = {}) {
    this.exec = options.exec || exec;
    this.password = options.password || "testzone-local-only";
  }

  name(id) {
    return `buddystudy-testzone-${id}`;
  }

  async ensureNetwork() {
    try {
      await this.exec("docker", ["network", "inspect", NETWORK]);
    } catch {
      await this.exec("docker", ["network", "create", NETWORK]);
    }
  }

  async list() {
    const results = [];
    for (const component of COMPONENT_CATALOG) {
      let status = "not-deployed";
      let detail = "";
      try {
        const { stdout } = await this.exec("docker", [
          "inspect",
          "--format",
          "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}",
          this.name(component.id),
        ]);
        [status, detail] = stdout.trim().split("|");
      } catch {
        // Missing containers are represented as not-deployed.
      }
      results.push({
        ...component,
        env: undefined,
        endpoint: component.endpoint.replace("<password>", "[configured-password]"),
        status,
        detail,
      });
    }
    return results;
  }

  async deploy(id) {
    const component = definition(id);
    await this.ensureNetwork();
    await this.exec("docker", ["pull", component.image]);
    await this.exec("docker", ["rm", "-f", this.name(id)]).catch(() => {});
    const args = [
      "run",
      "-d",
      "--name",
      this.name(id),
      "--network",
      NETWORK,
      "--restart",
      "unless-stopped",
      "--label",
      "testzone.managed=true",
    ];
    for (const value of component.env(this.password)) args.push("-e", value);
    args.push(component.image);
    await this.exec("docker", args);
    return (await this.list()).find((entry) => entry.id === id);
  }

  async restart(id) {
    definition(id);
    await this.exec("docker", ["restart", this.name(id)]);
    return (await this.list()).find((entry) => entry.id === id);
  }

  async delete(id) {
    definition(id);
    await this.exec("docker", ["rm", "-f", this.name(id)]).catch(() => {});
    return { id, status: "not-deployed" };
  }
}
