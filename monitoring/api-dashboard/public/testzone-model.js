export function resultsFor(execution, { scenario = "", tool = "" } = {}) {
  return (execution?.results ?? []).filter(
    (result) =>
      (!scenario || result.scenario === scenario) &&
      (!tool || result.tool === tool),
  );
}

export function unique(values) {
  return [...new Set(values)].sort((left, right) =>
    String(left).localeCompare(String(right), undefined, { numeric: true }),
  );
}

export function sustainableCapacity(results, runtime) {
  const stages = results
    .filter(
      (result) =>
        result.runtime === runtime &&
        result.load?.type === "rps" &&
        result.validity?.valid !== false,
    )
    .filter((result) => {
      const summary = result.summary ?? {};
      const target = Number(result.load.value);
      return (
        Number(summary.successRps ?? 0) >= target * 0.95 &&
        Number(summary.failureRate ?? 0) < 0.001 &&
        Number(summary.dropped ?? 0) === 0
      );
    })
    .map((result) => Number(result.load.value));
  return stages.length ? Math.max(...stages) : null;
}

export function maximumMetric(results, section, key) {
  const values = results
    .map((result) => result?.[section]?.[key])
    .filter((value) => typeof value === "number" && Number.isFinite(value));
  return values.length ? Math.max(...values) : null;
}

export function runtimeSeries(results, metric, { section = "summary" } = {}) {
  const loads = unique(
    results
      .filter((result) => result.load?.type === "rps")
      .map((result) => Number(result.load.value)),
  );
  const runtimes = unique(results.map((result) => result.runtime));
  return {
    loads,
    series: runtimes.map((runtime) => ({
      runtime,
      values: loads.map((load) => {
        const result = results.find(
          (candidate) =>
            candidate.runtime === runtime &&
            candidate.load?.type === "rps" &&
            Number(candidate.load.value) === load,
        );
        const value = result?.[section]?.[metric];
        return typeof value === "number" ? value : null;
      }),
    })),
  };
}

export function p95CollectionStatus(results) {
  const measured = results.filter(
    (result) => typeof result.summary?.successfulRequestP95Ms === "number",
  ).length;
  return {
    measured,
    total: results.length,
    complete: measured > 0 && measured === results.length,
  };
}
