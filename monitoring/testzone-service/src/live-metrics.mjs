import fs from "node:fs/promises";

const TRACKED_METRICS = new Set([
  "dropped_iterations",
  "http_req_duration",
  "http_req_failed",
  "http_reqs",
  "vus",
  "vus_max",
]);

function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * quantile) - 1);
  return sorted[Math.max(0, index)];
}

function emptyBucket(timestamp) {
  return {
    timestamp,
    requests: 0,
    durations: [],
    failures: [],
    vus: [],
    droppedIterations: 0,
  };
}

export function summarizeLiveBucket(bucket) {
  const failures = bucket.failures.length
    ? bucket.failures.reduce((sum, value) => sum + value, 0) / bucket.failures.length
    : 0;
  return {
    timestamp: new Date(bucket.timestamp).toISOString(),
    requestRate: bucket.requests,
    p95Ms: percentile(bucket.durations, 0.95),
    errorRate: failures,
    vus: bucket.vus.length ? Math.max(...bucket.vus) : 0,
    droppedIterations: bucket.droppedIterations,
  };
}

export function consumeK6Lines(lines, buckets = new Map()) {
  for (const line of lines) {
    if (!line.trim()) continue;
    let item;
    try {
      item = JSON.parse(line);
    } catch {
      continue;
    }
    const metric = item.data?.metric || item.metric;
    const value = Number(item.data?.value);
    if (item.type !== "Point" || !TRACKED_METRICS.has(metric) || !Number.isFinite(value)) continue;
    const parsed = Date.parse(item.data?.time);
    const timestamp = Math.floor((Number.isFinite(parsed) ? parsed : Date.now()) / 1_000) * 1_000;
    const bucket = buckets.get(timestamp) || emptyBucket(timestamp);
    if (metric === "http_reqs") bucket.requests += value;
    if (metric === "http_req_duration") bucket.durations.push(value);
    if (metric === "http_req_failed") bucket.failures.push(value);
    if (metric === "vus" || metric === "vus_max") bucket.vus.push(value);
    if (metric === "dropped_iterations") bucket.droppedIterations += value;
    buckets.set(timestamp, bucket);
  }
  return buckets;
}

export class K6LiveMetricsReader {
  constructor(filePath) {
    this.filePath = filePath;
    this.offset = 0;
    this.remainder = "";
    this.buckets = new Map();
    this.emitted = new Set();
  }

  async read(final = false) {
    let handle;
    try {
      handle = await fs.open(this.filePath, "r");
      const stat = await handle.stat();
      if (stat.size > this.offset) {
        const buffer = Buffer.alloc(stat.size - this.offset);
        await handle.read(buffer, 0, buffer.length, this.offset);
        this.offset = stat.size;
        const text = this.remainder + buffer.toString("utf8");
        const lines = text.split(/\r?\n/);
        this.remainder = lines.pop() || "";
        consumeK6Lines(lines, this.buckets);
      }
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    } finally {
      await handle?.close();
    }

    if (final && this.remainder.trim()) {
      consumeK6Lines([this.remainder], this.buckets);
      this.remainder = "";
    }

    const timestamps = [...this.buckets.keys()].sort((left, right) => left - right);
    const newest = timestamps.at(-1);
    const ready = timestamps.filter((timestamp) =>
      !this.emitted.has(timestamp) && (final || timestamp !== newest));
    return ready.map((timestamp) => {
      this.emitted.add(timestamp);
      return summarizeLiveBucket(this.buckets.get(timestamp));
    });
  }
}
