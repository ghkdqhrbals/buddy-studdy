import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { summarizeK6 } from "./influx.mjs";
import { K6LiveMetricsReader } from "./live-metrics.mjs";
import { validateScript } from "./validation.mjs";

function tail(lines, maximum = 200) {
  return lines.slice(Math.max(0, lines.length - maximum));
}

export function projectForRun(store, run) {
  const project = store.state.projects.find((entry) => entry.id === run.projectId);
  if (!project) throw new Error(`Project ${run.projectId} was not found for run ${run.id}.`);
  return project;
}

export function appendUniqueLiveWarning(logs, error, previousMessage) {
  const message = String(error?.message || error);
  if (message === previousMessage) return previousMessage;
  logs.push(`Live metrics warning: ${message}`);
  return message;
}

export async function persistRunMetrics({
  influx,
  metricsPath,
  run,
  project,
  script,
  summary,
}) {
  const warnings = [];
  const persist = async (label, operation) => {
    try {
      await operation();
    } catch (error) {
      warnings.push(`${label}: ${String(error?.message || error)}`);
    }
  };

  await persist("Raw metrics import failed", () => influx.importK6Json(metricsPath, {
    runId: run.id,
    projectId: project.id,
    scriptId: script.id,
  }));
  await persist("Summary metrics write failed", () => influx.writeRunSummary(
    run,
    project,
    script,
    summary,
  ));
  return warnings;
}

export class RunManager {
  constructor({ store, influx, config, spawnImpl = spawn }) {
    this.store = store;
    this.influx = influx;
    this.config = config;
    this.spawn = spawnImpl;
    this.active = new Map();
  }

  async start(run, script) {
    if (this.active.size >= this.config.maxConcurrentRuns) {
      throw new Error(`TestZone already has ${this.active.size} active run(s).`);
    }
    validateScript(script.code, {
      maxVus: this.config.maxVus,
      maxTargetRps: this.config.maxTargetRps,
      maxDurationSeconds: this.config.maxDurationSeconds,
    });
    const project = projectForRun(this.store, run);

    const runDirectory = this.store.runPath(run.id);
    const scriptPath = path.join(runDirectory, "script.js");
    const summaryPath = path.join(runDirectory, "summary.json");
    const metricsPath = path.join(runDirectory, "metrics.jsonl");
    const logPath = path.join(runDirectory, "run.log");
    await fs.mkdir(runDirectory, { recursive: true });
    await fs.writeFile(scriptPath, script.code, { mode: 0o600 });
    await fs.writeFile(this.store.runSeriesPath(run.id), "", { mode: 0o600 });

    const args = [
      "run",
      "--quiet",
      "--summary-export",
      summaryPath,
      "--out",
      `json=${metricsPath}`,
      scriptPath,
    ];
    const child = this.spawn("k6", args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
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
    let lastLiveWarning = null;
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
        if (points.length) lastLiveWarning = null;
      } catch (error) {
        const previousWarning = lastLiveWarning;
        lastLiveWarning = appendUniqueLiveWarning(logs, error, lastLiveWarning);
        if (lastLiveWarning !== previousWarning) {
          await this.store.patchRun(run.id, { logTail: tail(logs) });
        }
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
          metricsWarning: null,
          logTail: tail(logs),
          live: {
            ...(this.store.state.runs.find((entry) => entry.id === run.id)?.live || {}),
            progress: 1,
            updatedAt: finishedAt,
          },
        });
        if (summary) {
          const warnings = await persistRunMetrics({
            influx: this.influx,
            metricsPath,
            run: updated,
            project,
            script,
            summary,
          });
          if (warnings.length) {
            logs.push(...warnings.map((warning) => `Metrics warning: ${warning}`));
            await this.store.patchRun(run.id, {
              metricsWarning: warnings.join(" | "),
              logTail: tail(logs),
            });
          }
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
