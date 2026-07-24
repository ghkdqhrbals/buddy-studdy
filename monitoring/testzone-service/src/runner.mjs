import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { summarizeK6 } from "./influx.mjs";
import { K6LiveMetricsReader } from "./live-metrics.mjs";
import { validateScript } from "./validation.mjs";

const SECRET_NAME_PATTERN = /(token|secret|password|authorization|api[_-]?key)/i;

function sanitizedEnvironment(values = {}) {
  return Object.fromEntries(
    Object.entries(values).map(([key, value]) => {
      if (key === "HEADERS_JSON") {
        try {
          const headers = JSON.parse(String(value));
          const masked = Object.fromEntries(
            Object.entries(headers).map(([name, headerValue]) => [
              name,
              SECRET_NAME_PATTERN.test(name) ? "[REDACTED]" : headerValue,
            ]),
          );
          return [key, JSON.stringify(masked)];
        } catch {
          return [key, "[REDACTED]"];
        }
      }
      return [key, SECRET_NAME_PATTERN.test(key) ? "[REDACTED]" : String(value)];
    }),
  );
}

function tail(lines, maximum = 200) {
  return lines.slice(Math.max(0, lines.length - maximum));
}

export function buildExecutionScript() {
  return `import userScenario from "./script.js";

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

export default userScenario;
`;
}

export class RunManager {
  constructor({ store, influx, config, spawnImpl = spawn }) {
    this.store = store;
    this.influx = influx;
    this.config = config;
    this.spawn = spawnImpl;
    this.active = new Map();
  }

  async start(run, project, script, environment = {}) {
    if (this.active.size >= this.config.maxConcurrentRuns) {
      throw new Error(`TestZone already has ${this.active.size} active run(s).`);
    }
    validateScript(script.code, {
      maxVus: this.config.maxVus,
      maxDurationSeconds: this.config.maxDurationSeconds,
      duration: run.options.duration,
      targetBaseUrl: run.targetUrl,
    });

    const runDirectory = this.store.runPath(run.id);
    const scriptPath = path.join(runDirectory, "script.js");
    const executionPath = path.join(runDirectory, "execution.js");
    const summaryPath = path.join(runDirectory, "summary.json");
    const metricsPath = path.join(runDirectory, "metrics.jsonl");
    const logPath = path.join(runDirectory, "run.log");
    await fs.mkdir(runDirectory, { recursive: true });
    await fs.writeFile(scriptPath, script.code, { mode: 0o600 });
    await fs.writeFile(executionPath, buildExecutionScript(), { mode: 0o600 });
    await fs.writeFile(this.store.runSeriesPath(run.id), "", { mode: 0o600 });
    await fs.writeFile(path.join(runDirectory, "environment.json"), `${JSON.stringify(sanitizedEnvironment(environment), null, 2)}\n`, { mode: 0o600 });

    const processEnvironment = {
      ...process.env,
      ...Object.fromEntries(Object.entries(environment).map(([key, value]) => [key, String(value)])),
      BASE_URL: run.targetUrl,
      VUS: String(run.options.vus),
      MAX_VUS: String(run.options.maxVus),
      TARGET_RPS: String(run.options.targetRps),
      DURATION: run.options.duration,
    };
    const args = [
      "run",
      "--quiet",
      "--summary-export",
      summaryPath,
      "--out",
      `json=${metricsPath}`,
      executionPath,
    ];
    const child = this.spawn("k6", args, { env: processEnvironment, stdio: ["ignore", "pipe", "pipe"] });
    this.active.set(run.id, child);
    const logs = [];
    const append = async (chunk) => {
      const text = chunk.toString();
      logs.push(...text.split(/\r?\n/).filter(Boolean));
      await fs.appendFile(logPath, text);
      await this.store.patchRun(run.id, { logTail: tail(logs) });
    };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    const startedAt = new Date().toISOString();
    await this.store.patchRun(run.id, { status: "running", startedAt });

    const liveReader = new K6LiveMetricsReader(metricsPath);
    let liveReadPending = false;
    const collectLive = async (final = false) => {
      if (liveReadPending) return;
      liveReadPending = true;
      try {
        const points = await liveReader.read(final);
        for (const point of points) {
          const elapsedSeconds = Math.max(0, (Date.parse(point.timestamp) - Date.parse(startedAt)) / 1_000);
          const snapshot = {
            ...point,
            elapsedSeconds,
            progress: Math.min(1, elapsedSeconds / run.options.durationSeconds),
            updatedAt: new Date().toISOString(),
          };
          await this.store.appendRunSeries(run.id, snapshot);
          await this.store.patchRun(run.id, { live: snapshot });
          await this.influx.writeLiveSnapshot(run, project, script, snapshot);
        }
      } catch (error) {
        logs.push(`Live metrics warning: ${error.message}`);
        await this.store.patchRun(run.id, { logTail: tail(logs) });
      } finally {
        liveReadPending = false;
      }
    };
    const liveInterval = setInterval(() => void collectLive(false), 1_000);

    const timeoutMs = (run.options.durationSeconds + 90) * 1000;
    const timeoutId = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
    child.once("close", async (exitCode, signal) => {
      clearTimeout(timeoutId);
      clearInterval(liveInterval);
      while (liveReadPending) await new Promise((resolve) => setTimeout(resolve, 20));
      await collectLive(true);
      this.active.delete(run.id);
      const finishedAt = new Date().toISOString();
      try {
        let rawSummary = null;
        try {
          rawSummary = JSON.parse(await fs.readFile(summaryPath, "utf8"));
        } catch (error) {
          if (error.code !== "ENOENT") throw error;
        }
        const summary = rawSummary ? summarizeK6(rawSummary) : null;
        const status = exitCode === 0 && summary ? "completed" : "failed";
        const failureReason = exitCode === 0
          ? "k6 did not produce a summary."
          : `k6 exited with ${exitCode ?? signal}${logs.length ? `: ${logs.at(-1)}` : ""}`;
        const updated = await this.store.patchRun(run.id, {
          status,
          finishedAt,
          summary,
          error: status === "completed" ? null : failureReason,
          logTail: tail(logs),
          live: {
            ...(this.store.state.runs.find((entry) => entry.id === run.id)?.live || {}),
            progress: 1,
            updatedAt: finishedAt,
          },
        });
        if (summary) {
          await this.influx.importK6Json(metricsPath, {
            runId: run.id,
            projectId: project.id,
            scriptId: script.id,
          });
          await this.influx.writeRunSummary(updated, project, script, summary);
        }
      } catch (error) {
        await this.store.patchRun(run.id, {
          status: "failed",
          finishedAt,
          error: error.message,
          logTail: tail(logs),
        });
      }
    });
    return this.store.patchRun(run.id, { status: "running" });
  }

  async cancel(runId) {
    const child = this.active.get(runId);
    if (!child) return false;
    child.kill("SIGTERM");
    await this.store.patchRun(runId, { status: "cancelling" });
    return true;
  }
}
