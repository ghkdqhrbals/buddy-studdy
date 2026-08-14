import { lokiMetricTimestampToMs } from "./logs.js?v=2026070711";

const RUNTIME_METRICS_MARKER = "runtime_metrics ";

export async function readLokiJson(response) {
  if (!response.ok) {
    throw new Error(`Loki query failed: ${response.status}`);
  }

  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.includes("application/json")) {
    throw new Error(
      "Loki returned a non-JSON response. Open Server Dashboard at monitoring.lowfidev.cloud.",
    );
  }

  return response.json();
}

export function parseRuntimeMetrics(value) {
  const [nanoseconds, line] = value;
  const markerIndex = line.indexOf(RUNTIME_METRICS_MARKER);
  if (markerIndex < 0) return null;
  const payload = JSON.parse(line.slice(markerIndex + RUNTIME_METRICS_MARKER.length).trim());
  return {
    ...payload,
    nanoseconds,
    ms: Number(payload.capturedAtEpochMs ?? BigInt(nanoseconds) / 1_000_000n),
  };
}

export function parseLokiMetricValues(values) {
  return values
    .map(([timestamp, value]) => ({
      ms: lokiMetricTimestampToMs(timestamp),
      value: Number(value),
    }))
    .filter((point) => Number.isFinite(point.value))
    .sort((a, b) => a.ms - b.ms);
}

export function counterDeltaPoints(samples, field) {
  const points = [];
  let previous = null;
  for (const sample of samples) {
    const value = Number(sample[field]);
    if (!Number.isFinite(value)) continue;
    if (previous && value >= previous.value) {
      points.push({ ms: sample.ms, value: value - previous.value });
    }
    previous = { value, ms: sample.ms };
  }
  return points;
}

export function counterRatePoints(samples, field) {
  const points = [];
  let previous = null;
  for (const sample of samples) {
    const value = Number(sample[field]);
    if (!Number.isFinite(value)) continue;
    if (previous && value >= previous.value && sample.ms > previous.ms) {
      points.push({
        ms: sample.ms,
        value: (value - previous.value) / ((sample.ms - previous.ms) / 1000),
      });
    }
    previous = { value, ms: sample.ms };
  }
  return points;
}

export function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes)) return "-";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let amount = Math.max(0, bytes);
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  const digits = amount >= 100 || unit === 0 ? 0 : amount >= 10 ? 1 : 2;
  return `${amount.toFixed(digits)} ${units[unit]}`;
}

export function formatPercent(value) {
  const percent = Number(value);
  return Number.isFinite(percent) ? `${percent.toFixed(1)}%` : "-";
}

export function formatCount(value) {
  const count = Number(value);
  return Number.isFinite(count) ? Math.round(count).toLocaleString("en-US") : "-";
}

export function formatRate(value) {
  const rate = Number(value);
  if (!Number.isFinite(rate)) return "-";
  return `${rate.toFixed(rate >= 10 ? 1 : 2)}/s`;
}

export function formatMilliseconds(value) {
  const milliseconds = Number(value);
  if (!Number.isFinite(milliseconds)) return "-";
  if (milliseconds >= 1000) return `${(milliseconds / 1000).toFixed(milliseconds >= 10_000 ? 1 : 2)} s`;
  return `${milliseconds.toFixed(milliseconds >= 100 ? 0 : milliseconds >= 10 ? 1 : 2)} ms`;
}

export function formatDurationSeconds(value) {
  if (value == null || (typeof value === "string" && value.trim() === "")) return "-";
  const total = Number(value);
  if (!Number.isFinite(total) || total < 0) return "-";
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

export function chooseMetricStepMs(durationMs) {
  const target = durationMs / 180;
  const steps = [5_000, 10_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000];
  return steps.find((step) => step >= target) ?? 1_800_000;
}

export function relativeMetricRange(durationMs, endMs = Date.now()) {
  const duration = Number(durationMs);
  const end = Number(endMs);
  if (!Number.isFinite(duration) || duration <= 0 || !Number.isFinite(end)) {
    throw new Error("Time range is invalid.");
  }
  return { startMs: end - duration, endMs: end };
}

export function customMetricRange(fromValue, toValue) {
  const startMs = new Date(fromValue).getTime();
  const endMs = new Date(toValue).getTime();
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) {
    throw new Error("Select both From and To.");
  }
  if (startMs >= endMs) {
    throw new Error("From must be earlier than To.");
  }
  return { startMs, endMs };
}

export function toDateTimeLocalValue(ms) {
  const date = new Date(ms);
  if (!Number.isFinite(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 19);
}

export function hasUnrecoveredRuntimeFailure(snapshots, failures) {
  const latestSnapshotMs = Number(snapshots.at(-1)?.ms ?? 0);
  const latestFailureMs = Number(failures.at(-1)?.ms ?? 0);
  return latestFailureMs > latestSnapshotMs;
}

export function formatLogqlDuration(ms) {
  if (ms % 3_600_000 === 0) return `${ms / 3_600_000}h`;
  if (ms % 60_000 === 0) return `${ms / 60_000}m`;
  return `${Math.max(1, Math.round(ms / 1000))}s`;
}

export function buildRequestRateQuery(window) {
  return `sum(rate(({app="buddystudy"} |= "api_exchange ")[${window}]))`;
}

export function buildErrorRateQuery(window) {
  return buildStatusClassRateQuery(500, 600, window);
}

export function buildClientErrorRateQuery(window) {
  return buildStatusClassRateQuery(400, 500, window);
}

export function buildLatencyQuantileQuery(quantile, window) {
  const value = Number(quantile);
  if (!Number.isFinite(value) || value <= 0 || value >= 1) {
    throw new Error("Latency quantile must be between 0 and 1");
  }
  return `max(quantile_over_time(${value}, {app="buddystudy"} |= "api_exchange " | pattern "<_> api_exchange <payload>" | line_format "{{.payload}}" | json | __error__ = "" | unwrap durationMs [${window}]))`;
}

export function ratioPoints(numerator, denominator, multiplier = 100) {
  if (!denominator.length) return [];
  const valuesByTimestamp = new Map(numerator.map((point) => [point.ms, Number(point.value)]));
  return denominator
    .map((point) => {
      const denominatorValue = Number(point.value);
      if (!Number.isFinite(denominatorValue) || denominatorValue <= 0) return null;
      const numeratorValue = valuesByTimestamp.get(point.ms) ?? 0;
      return {
        ms: point.ms,
        value: (Math.max(0, numeratorValue) / denominatorValue) * multiplier,
      };
    })
    .filter(Boolean);
}

export function percentagePoints(samples, numeratorField, denominatorField) {
  return samples
    .map((sample) => {
      const numerator = Number(sample[numeratorField]);
      const denominator = Number(sample[denominatorField]);
      if (!Number.isFinite(numerator) || !Number.isFinite(denominator) || denominator <= 0) return null;
      return { ms: sample.ms, value: (numerator / denominator) * 100 };
    })
    .filter(Boolean);
}

function buildStatusClassRateQuery(minimum, maximumExclusive, window) {
  return `sum(rate(({app="buddystudy"} |= "api_exchange " | pattern "<_> api_exchange <payload>" | line_format "{{.payload}}" | json | __error__ = "" | status >= ${minimum} and status < ${maximumExclusive})[${window}]))`;
}
