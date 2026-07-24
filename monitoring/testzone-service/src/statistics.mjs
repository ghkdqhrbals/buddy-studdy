export function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * quantile) - 1);
  return sorted[Math.max(0, index)];
}

export function summarizeLatencySamples(values) {
  const samples = values
    .filter((value) => value !== null && value !== undefined && value !== "")
    .map(Number)
    .filter(Number.isFinite);
  if (!samples.length) {
    return {
      averageMs: null,
      minimumMs: null,
      medianMs: null,
      maximumMs: null,
      p90Ms: null,
      p95Ms: null,
    };
  }
  return {
    averageMs: samples.reduce((sum, value) => sum + value, 0) / samples.length,
    minimumMs: Math.min(...samples),
    medianMs: percentile(samples, 0.5),
    maximumMs: Math.max(...samples),
    p90Ms: percentile(samples, 0.9),
    p95Ms: percentile(samples, 0.95),
  };
}
