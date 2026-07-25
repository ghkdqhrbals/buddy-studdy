import fs from "node:fs";
import readline from "node:readline";
import { percentile } from "./statistics.mjs";

const TRACKED_K6_METRICS = new Set([
  "checks",
  "dropped_iterations",
  "http_req_duration",
  "http_req_failed",
  "http_req_waiting",
  "http_reqs",
  "iterations",
  "vus",
  "vus_max",
]);

function escapeMeasurement(value) {
  return String(value).replaceAll(",", "\\,").replaceAll(" ", "\\ ");
}

function escapeTag(value) {
  return String(value)
    .replaceAll("\\", "\\\\")
    .replaceAll(",", "\\,")
    .replaceAll("=", "\\=")
    .replaceAll(" ", "\\ ");
}

function fieldValue(value) {
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : null;
  if (typeof value === "boolean") return value ? "true" : "false";
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

export function lineProtocol(measurement, tags, fields, timestampMs = Date.now()) {
  const tagSet = Object.entries(tags)
    .filter(([, value]) => value !== undefined && value !== null && value !== "")
    .map(([key, value]) => `${escapeTag(key)}=${escapeTag(value)}`)
    .join(",");
  const fieldSet = Object.entries(fields)
    .map(([key, value]) => [escapeTag(key), fieldValue(value)])
    .filter(([, value]) => value !== null)
    .map(([key, value]) => `${key}=${value}`)
    .join(",");
  if (!fieldSet) return null;
  return `${escapeMeasurement(measurement)}${tagSet ? `,${tagSet}` : ""} ${fieldSet} ${BigInt(timestampMs) * 1_000_000n}`;
}

export function summarizeK6(summary = {}) {
  const metrics = summary.metrics || {};
  const values = (name) => metrics[name]?.values || metrics[name] || {};
  const requests = values("http_reqs");
  const iterations = values("iterations");
  const duration = values("http_req_duration");
  const failures = values("http_req_failed");
  const waiting = values("http_req_waiting");
  const checks = values("checks");
  const vus = values("vus_max");
  const dropped = values("dropped_iterations");
  const requestCount = requests.count ?? null;
  const errorRate = failures.rate ?? failures.value ?? null;
  const errorCount = requestCount !== null && errorRate !== null
    ? Math.round(requestCount * errorRate)
    : null;
  const successCount = requestCount !== null && errorCount !== null
    ? Math.max(0, requestCount - errorCount)
    : null;
  return {
    requestRate: requests.rate ?? null,
    tps: requests.rate ?? null,
    iterationRate: iterations.rate ?? null,
    averageMs: duration.avg ?? null,
    minimumMs: duration.min ?? null,
    medianMs: duration.med ?? duration["p(50)"] ?? null,
    maximumMs: duration.max ?? null,
    p50Ms: duration["p(50)"] ?? duration.med ?? null,
    p90Ms: duration["p(90)"] ?? null,
    p95Ms: duration["p(95)"] ?? null,
    p99Ms: duration["p(99)"] ?? null,
    mttMs: duration.avg ?? null,
    mttfbMs: waiting.avg ?? null,
    errorRate,
    successCount,
    errorCount,
    checkRate: checks.rate ?? checks.value ?? null,
    maxVus: vus.max ?? vus.value ?? null,
    requests: requestCount,
    iterations: iterations.count ?? null,
    droppedIterations: dropped.count ?? 0,
  };
}

function aggregateValue(metric, values) {
  if (!values.length) return null;
  if (metric === "http_req_duration") return percentile(values, 0.95);
  if (metric === "http_req_waiting") {
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }
  if (metric === "http_req_failed" || metric === "checks") {
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }
  if (metric === "vus" || metric === "vus_max") return Math.max(...values);
  return values.reduce((sum, value) => sum + value, 0);
}

export class InfluxWriter {
  constructor(config, fetchImpl = fetch) {
    this.config = config;
    this.fetch = fetchImpl;
  }

  get enabled() {
    return Boolean(this.config.url && this.config.token && this.config.org && this.config.bucket);
  }

  async write(lines) {
    const body = lines.filter(Boolean).join("\n");
    if (!body || !this.enabled) return { written: 0, skipped: !this.enabled };
    const url = new URL("/api/v2/write", this.config.url);
    url.searchParams.set("org", this.config.org);
    url.searchParams.set("bucket", this.config.bucket);
    url.searchParams.set("precision", "ns");
    const response = await this.fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Token ${this.config.token}`,
        "Content-Type": "text/plain; charset=utf-8",
      },
      body,
    });
    if (!response.ok) {
      throw new Error(`InfluxDB write failed (${response.status}): ${await response.text()}`);
    }
    return { written: lines.length, skipped: false };
  }

  async writeRunSummary(run, project, script, summary) {
    return this.write([
      lineProtocol("testzone_run_summary", {
        run_id: run.id,
        project_id: project.id,
        project: project.name,
        script_id: script.id,
        script: script.name,
        profile: run.profile,
        status: run.status,
      }, summary, Date.parse(run.finishedAt || run.startedAt || run.createdAt)),
    ]);
  }

  async writeLiveSnapshot(run, project, script, snapshot) {
    const tags = {
      run_id: run.id,
      project_id: project.id,
      project: project.name,
      script_id: script.id,
      script: script.name,
      profile: run.profile,
    };
    const fields = (value) => ({
      requestRate: value.requestRate,
      tps: value.tps,
      successCount: value.successCount,
      errorCount: value.errorCount,
      averageMs: value.averageMs,
      minimumMs: value.minimumMs,
      medianMs: value.medianMs,
      maximumMs: value.maximumMs,
      p90Ms: value.p90Ms,
      p95Ms: value.p95Ms,
      mttMs: value.mttMs,
      mttfbMs: value.mttfbMs,
      errorRate: value.errorRate,
      vus: value.vus,
      droppedIterations: value.droppedIterations,
      progress: snapshot.progress,
    });
    return this.write([
      lineProtocol("testzone_run_live", tags, fields(snapshot), Date.parse(snapshot.timestamp)),
      ...Object.entries(snapshot.scenarios || {}).map(([scenario, value]) =>
        lineProtocol("testzone_run_live", {
          ...tags,
          scenario,
        }, fields(value), Date.parse(snapshot.timestamp))),
    ]);
  }

  async writeComponentSnapshots(components, timestampMs = Date.now()) {
    return this.write(components.map((component) => lineProtocol(
      "testzone_component_runtime",
      {
        component: component.id,
        image: component.image,
        status: component.status,
      },
      {
        cpuPercent: component.metrics?.cpuPercent,
        memoryUsedMb: component.metrics?.memoryUsedMb,
        memoryLimitMb: component.metrics?.memoryLimitMb,
        memoryPercent: component.metrics?.memoryPercent,
        processes: component.metrics?.processes,
        networkIO: component.metrics?.networkIO,
        blockIO: component.metrics?.blockIO,
        connections: component.metrics?.connections,
        maxConnections: component.metrics?.maxConnections,
        activeConnections: component.metrics?.activeConnections,
        databaseSizeBytes: component.metrics?.databaseSizeBytes,
        cacheHitRatio: component.metrics?.cacheHitRatio,
        redisUsedMemoryBytes: component.metrics?.redisUsedMemoryBytes,
        redisMaxMemoryBytes: component.metrics?.redisMaxMemoryBytes,
        connectedClients: component.metrics?.connectedClients,
        operationsPerSecond: component.metrics?.operationsPerSecond,
      },
      timestampMs,
    )));
  }

  async importK6Json(filePath, context) {
    if (!this.enabled || !fs.existsSync(filePath)) return { written: 0, skipped: !this.enabled };
    const input = fs.createReadStream(filePath);
    const reader = readline.createInterface({ input, crlfDelay: Infinity });
    let batch = [];
    let written = 0;
    let currentSecond = null;
    let groups = new Map();

    const flushSecond = async () => {
      if (currentSecond === null) return;
      for (const group of groups.values()) {
        const point = lineProtocol("testzone_k6_metric", {
          run_id: context.runId,
          project_id: context.projectId,
          script_id: context.scriptId,
          metric: group.metric,
          scenario: group.tags.scenario,
          api: group.tags.api,
          method: group.tags.method,
          status: group.tags.status,
          group: group.tags.group,
        }, { value: aggregateValue(group.metric, group.values) }, currentSecond);
        if (point) batch.push(point);
      }
      groups = new Map();
      if (batch.length >= 1_000) {
        await this.write(batch);
        written += batch.length;
        batch = [];
      }
    };

    for await (const line of reader) {
      if (!line.trim()) continue;
      let item;
      try {
        item = JSON.parse(line);
      } catch {
        continue;
      }
      const metric = item.data?.metric || item.metric;
      if (
        item.type !== "Point"
        || !TRACKED_K6_METRICS.has(metric)
        || !Number.isFinite(item.data?.value)
      ) continue;
      const tags = item.data.tags || {};
      const timestamp = Date.parse(item.data.time);
      const second = Math.floor((Number.isFinite(timestamp) ? timestamp : Date.now()) / 1_000) * 1_000;
      if (currentSecond !== null && second !== currentSecond) {
        await flushSecond();
      }
      currentSecond = second;
      const key = [
        metric,
        tags.scenario || "",
        tags.api || "",
        tags.method || "",
        tags.status || "",
        tags.group || "",
      ].join("\u0000");
      const group = groups.get(key) || { metric, tags, values: [] };
      group.values.push(Number(item.data.value));
      groups.set(key, group);
    }
    await flushSecond();
    if (batch.length) {
      await this.write(batch);
      written += batch.length;
    }
    return { written, skipped: false };
  }

  async deleteRun(runId) {
    if (!this.enabled) return { deleted: false, skipped: true };
    const url = new URL("/api/v2/delete", this.config.url);
    url.searchParams.set("org", this.config.org);
    url.searchParams.set("bucket", this.config.bucket);
    const response = await this.fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Token ${this.config.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        start: "1970-01-01T00:00:00Z",
        stop: new Date(Date.now() + 86_400_000).toISOString(),
        predicate: `run_id="${String(runId).replaceAll('"', '\\"')}"`,
      }),
    });
    if (!response.ok) throw new Error(`InfluxDB delete failed (${response.status}): ${await response.text()}`);
    return { deleted: true, skipped: false };
  }
}
