import {
  maximumMetric,
  p95CollectionStatus,
  resultsFor,
  runtimeSeries,
  sustainableCapacity,
  unique,
} from "./testzone-model.js?v=2026072401";

const COLORS = {
  mvc: "#c7354a",
  webflux: "#2563eb",
  target: "#8b98aa",
  timeout: "#9a6400",
};

const state = { payload: null, project: null, execution: null };
const ids = [
  "projectSelect", "executionSelect", "scenarioSelect", "toolSelect",
  "resetFiltersButton", "generatedAt", "verdictBand", "verdictTitle",
  "verdictDetail", "testzoneSummary", "throughputLegend", "latencyLegend",
  "successLatencyLegend", "resourceLegend", "latencyDescription", "findingList", "historyRows",
  "measurementRows", "newRunButton", "newRunDialog", "runProfile",
  "runTool", "mvcRefInput", "webfluxRefInput", "runScenarios",
  "runCommand", "copyRunCommandButton",
];
const els = Object.fromEntries(ids.map((id) => [id, document.getElementById(id)]));

function formatNumber(value, digits = 1) {
  return typeof value === "number"
    ? value.toLocaleString("en-US", { maximumFractionDigits: digits })
    : "-";
}

function formatPercent(value, digits = 1) {
  return typeof value === "number" ? `${(value * 100).toFixed(digits)}%` : "-";
}

function formatBytes(value) {
  return typeof value === "number" ? `${(value / 1048576).toFixed(1)} MiB` : "-";
}

function formatDate(value) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function fillSelect(element, values, label = (value) => value) {
  element.innerHTML = values
    .map((value) => `<option value="${value}">${label(value)}</option>`)
    .join("");
}

function currentResults() {
  return resultsFor(state.execution, {
    scenario: els.scenarioSelect.value,
    tool: els.toolSelect.value,
  });
}

function refreshProject() {
  state.project = state.payload.projects.find(
    (project) => project.id === els.projectSelect.value,
  );
  const executions = state.payload.executions.filter(
    (execution) => execution.projectId === state.project.id,
  );
  fillSelect(
    els.executionSelect,
    executions.map((execution) => execution.id),
    (id) => {
      const execution = executions.find((item) => item.id === id);
      return `${formatDate(execution.startedAt)} · ${execution.profile}`;
    },
  );
  els.executionSelect.value = state.project.latestExecutionId ?? executions[0]?.id ?? "";
  refreshExecution();
}

function refreshExecution() {
  state.execution = state.payload.executions.find(
    (execution) => execution.id === els.executionSelect.value,
  );
  const scenarios = unique((state.execution?.results ?? []).map((result) => result.scenario));
  const tools = unique((state.execution?.results ?? []).map((result) => result.tool));
  const previousScenario = els.scenarioSelect.value;
  const previousTool = els.toolSelect.value;
  fillSelect(els.scenarioSelect, scenarios);
  fillSelect(els.toolSelect, tools);
  if (scenarios.includes(previousScenario)) els.scenarioSelect.value = previousScenario;
  if (tools.includes(previousTool)) els.toolSelect.value = previousTool;
  render();
}

function renderVerdict(results) {
  const { execution } = state;
  const timeoutCount = results.filter(
    (result) => result.classification?.timeoutBoundaryReached,
  ).length;
  els.verdictBand.dataset.tone = execution.status;
  els.verdictTitle.textContent =
    execution.status === "passed"
      ? "Capacity checks passed"
      : execution.status === "invalid"
        ? "Run excluded from conclusions"
        : "Capacity target missed";
  els.verdictDetail.textContent = timeoutCount
    ? `${els.scenarioSelect.value} has ${timeoutCount} runtime/load stages whose all-request p95 reached the 5-second client timeout.`
    : `${els.scenarioSelect.value} contains ${results.length} measured runtime/load stages.`;
}

function renderSummary(results) {
  const mvcCapacity = sustainableCapacity(results, "mvc");
  const webfluxCapacity = sustainableCapacity(results, "webflux");
  const maxSuccess = maximumMetric(results, "summary", "successRps");
  const maxP95 = maximumMetric(results, "summary", "allRequestP95Ms");
  const maxFailure = maximumMetric(results, "summary", "failureRate");
  const maxCpu = maximumMetric(results, "resources", "appCpuP95");
  const items = [
    ["MVC sustainable", mvcCapacity == null ? "< first stage" : `${formatNumber(mvcCapacity, 0)} RPS`, "95% target, <0.1% error"],
    ["WebFlux sustainable", webfluxCapacity == null ? "< first stage" : `${formatNumber(webfluxCapacity, 0)} RPS`, "95% target, <0.1% error"],
    ["Peak success", `${formatNumber(maxSuccess)} RPS`, "highest valid stage"],
    ["Worst all p95", maxP95 == null ? "-" : `${formatNumber(maxP95)} ms`, "includes timeouts"],
    ["Peak error", formatPercent(maxFailure), "started requests"],
    ["Peak app CPU", maxCpu == null ? "-" : `${formatNumber(maxCpu)}%`, "100% equals one core"],
  ];
  els.testzoneSummary.innerHTML = items
    .map(
      ([label, value, detail]) =>
        `<article><span>${label}</span><strong>${value}</strong><small>${detail}</small></article>`,
    )
    .join("");
}

function chartLegend(element, entries) {
  element.innerHTML = entries
    .map((entry) => `<span><i style="background:${entry.color}"></i>${entry.label}</span>`)
    .join("");
}

function drawLineChart(canvas, {
  labels,
  series,
  formatter = (value) => formatNumber(value),
  log = false,
  floor = 0,
}) {
  const box = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, box.width * dpr);
  canvas.height = Math.max(1, box.height * dpr);
  const context = canvas.getContext("2d");
  context.scale(dpr, dpr);
  const width = box.width;
  const height = box.height;
  const padding = { left: 58, right: 20, top: 18, bottom: 38 };
  const values = series
    .flatMap((item) => item.values)
    .filter((value) => typeof value === "number" && value >= 0);
  context.clearRect(0, 0, width, height);
  if (!labels.length || !values.length) {
    context.fillStyle = "#66758a";
    context.textAlign = "center";
    context.fillText("No measured values", width / 2, height / 2);
    return;
  }
  const transformed = (value) => (log ? Math.log10(Math.max(1, value)) : value);
  const maxValue = Math.max(...values);
  const max = transformed(maxValue * 1.08 || 1);
  const min = transformed(log ? 1 : floor);
  const x = (index) =>
    padding.left +
    (labels.length === 1
      ? (width - padding.left - padding.right) / 2
      : (index * (width - padding.left - padding.right)) / (labels.length - 1));
  const y = (value) =>
    padding.top +
    ((max - transformed(value)) * (height - padding.top - padding.bottom)) /
      Math.max(0.0001, max - min);

  context.font = "11px Inter, sans-serif";
  context.strokeStyle = "#e8edf4";
  context.fillStyle = "#66758a";
  context.textAlign = "right";
  for (let index = 0; index <= 4; index += 1) {
    const transformedValue = min + ((max - min) * index) / 4;
    const value = log ? 10 ** transformedValue : transformedValue;
    const yy = y(value);
    context.beginPath();
    context.moveTo(padding.left, yy);
    context.lineTo(width - padding.right, yy);
    context.stroke();
    context.fillText(formatter(value), padding.left - 8, yy + 4);
  }
  context.textAlign = "center";
  labels.forEach((label, index) => {
    context.fillText(formatNumber(label, 0), x(index), height - 12);
  });
  series.forEach((item) => {
    context.strokeStyle = item.color;
    context.fillStyle = item.color;
    context.lineWidth = item.width ?? 2;
    context.setLineDash(item.dashed ? [6, 5] : []);
    context.beginPath();
    let started = false;
    item.values.forEach((value, index) => {
      if (typeof value !== "number") {
        started = false;
        return;
      }
      if (!started) {
        context.moveTo(x(index), y(value));
        started = true;
      } else {
        context.lineTo(x(index), y(value));
      }
    });
    context.stroke();
    context.setLineDash([]);
    item.values.forEach((value, index) => {
      if (typeof value !== "number") return;
      context.beginPath();
      context.arc(x(index), y(value), 3.5, 0, Math.PI * 2);
      context.fill();
    });
  });
}

function chartSeries(results, metric, section = "summary") {
  const data = runtimeSeries(results, metric, { section });
  return {
    labels: data.loads,
    series: data.series.map((item) => ({
      label: item.runtime === "mvc" ? "MVC / JDBC" : "WebFlux / R2DBC",
      color: COLORS[item.runtime] ?? COLORS.target,
      values: item.values,
    })),
  };
}

function renderCharts(results) {
  const throughput = chartSeries(results, "successRps");
  const target = {
    label: "Target",
    color: COLORS.target,
    dashed: true,
    values: throughput.labels,
  };
  drawLineChart(document.getElementById("throughputChart"), {
    labels: throughput.labels,
    series: [target, ...throughput.series],
  });
  chartLegend(els.throughputLegend, [target, ...throughput.series]);

  const latency = chartSeries(results, "allRequestP95Ms");
  const timeout = {
    label: "5s timeout",
    color: COLORS.timeout,
    dashed: true,
    values: latency.labels.map(() => 5000),
  };
  drawLineChart(document.getElementById("latencyChart"), {
    labels: latency.labels,
    series: [...latency.series, timeout],
    formatter: (value) =>
      value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${formatNumber(value, 0)}ms`,
    log: true,
  });
  chartLegend(els.latencyLegend, [...latency.series, timeout]);

  const successfulLatency = chartSeries(results, "successfulRequestP95Ms");
  drawLineChart(document.getElementById("successLatencyChart"), {
    labels: successfulLatency.labels,
    series: successfulLatency.series,
    formatter: (value) =>
      value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${formatNumber(value, 0)}ms`,
    log: true,
  });
  chartLegend(els.successLatencyLegend, successfulLatency.series);
  const successP95 = p95CollectionStatus(results);
  els.latencyDescription.textContent = successP95.measured
    ? `Successful-only p95 is available for ${successP95.measured}/${successP95.total} stages; all-request p95 remains the timeout-inclusive view.`
    : "This historical execution collected only timeout-inclusive p95. Successful-only p95 is intentionally shown as unavailable.";

  const cpu = chartSeries(results, "appCpuP95", "resources");
  drawLineChart(document.getElementById("cpuChart"), {
    labels: cpu.labels,
    series: cpu.series,
    formatter: (value) => `${formatNumber(value, 0)}%`,
  });
  const memory = chartSeries(results, "appRssPeakBytes", "resources");
  const memorySeries = memory.series.map((item) => ({
    ...item,
    values: item.values.map((value) =>
      typeof value === "number" ? value / 1048576 : null,
    ),
  }));
  drawLineChart(document.getElementById("memoryChart"), {
    labels: memory.labels,
    series: memorySeries,
    formatter: (value) => `${formatNumber(value, 0)} MiB`,
  });
  chartLegend(els.resourceLegend, cpu.series);
}

function renderFindings() {
  els.findingList.innerHTML = (state.execution.findings ?? [])
    .map(
      (finding) =>
        `<li><span class="finding-severity" data-tone="${finding.severity}">${finding.severity}</span>` +
        `<div class="finding-copy"><strong>${finding.title}</strong><span>${finding.detail}</span></div></li>`,
    )
    .join("");
}

function renderHistory() {
  const executions = state.payload.executions.filter(
    (execution) => execution.projectId === state.project.id,
  );
  els.historyRows.innerHTML = executions
    .map((execution) => {
      const scenarios = unique(execution.results.map((result) => result.scenario));
      const commit = execution.refs?.webflux ?? execution.refs?.mvc ?? "-";
      return `<tr data-execution-id="${execution.id}" aria-current="${execution.id === state.execution.id}">` +
        `<td>${formatDate(execution.startedAt)}</td><td>${execution.profile}</td>` +
        `<td><span class="status" data-tone="${execution.status}">${execution.status}</span></td>` +
        `<td><code>${commit.slice(0, 8)}</code></td><td>${execution.results.length}</td>` +
        `<td>${scenarios.join(", ")}</td></tr>`;
    })
    .join("");
  els.historyRows.querySelectorAll("tr").forEach((row) => {
    row.addEventListener("click", () => {
      els.executionSelect.value = row.dataset.executionId;
      refreshExecution();
    });
  });
}

function renderMeasurements(results) {
  els.measurementRows.innerHTML = results
    .slice()
    .sort((left, right) =>
      left.runtime.localeCompare(right.runtime) ||
      Number(left.load.value) - Number(right.load.value),
    )
    .map((result) => {
      const summary = result.summary ?? {};
      const target = result.load?.type === "rps" ? Number(result.load.value) : null;
      const met = target ? Number(summary.successRps ?? 0) / target : null;
      const timeout = typeof summary.timeoutRate === "number"
        ? formatPercent(summary.timeoutRate)
        : result.classification?.timeoutBoundaryReached ? "p95 = timeout" : "not collected";
      const saturated = result.classification?.saturated;
      return `<tr><td class="${result.runtime}">${result.runtime.toUpperCase()}</td>` +
        `<td>${formatNumber(result.load.value, 0)} ${result.load.type}</td>` +
        `<td>${formatNumber(summary.successRps)}</td>` +
        `<td class="${met != null && met >= 0.95 ? "metric-good" : "metric-bad"}">${formatPercent(met)}</td>` +
        `<td>${typeof summary.allRequestP95Ms === "number" ? `${formatNumber(summary.allRequestP95Ms)} ms` : "-"}</td>` +
        `<td>${typeof summary.successfulRequestP95Ms === "number" ? `${formatNumber(summary.successfulRequestP95Ms)} ms` : "not collected"}</td>` +
        `<td class="${result.classification?.timeoutBoundaryReached ? "metric-bad" : ""}">${timeout}</td>` +
        `<td>${formatPercent(summary.failureRate, 2)}</td><td>${formatNumber(summary.dropped, 0)}</td>` +
        `<td>${typeof result.resources?.appCpuP95 === "number" ? `${formatNumber(result.resources.appCpuP95)}%` : "-"}</td>` +
        `<td>${formatBytes(result.resources?.appRssPeakBytes)}</td>` +
        `<td class="${saturated ? "metric-bad" : "metric-good"}">${saturated ? "saturated" : "sustainable"}</td></tr>`;
    })
    .join("");
}

function render() {
  const results = currentResults();
  renderVerdict(results);
  renderSummary(results);
  renderCharts(results);
  renderFindings();
  renderHistory();
  renderMeasurements(results);
}

function updateRunCommand() {
  els.runCommand.textContent = [
    `TOOL=${els.runTool.value}`,
    `PROFILE=${els.runProfile.value}`,
    `MVC_REF=${els.mvcRefInput.value.trim() || "eca7e320"}`,
    `WEBFLUX_REF=${els.webfluxRefInput.value.trim() || "HEAD"}`,
    `SCENARIOS=${els.runScenarios.value.trim()}`,
    "MAX_CONCURRENT_USERS=1000",
    "./backend/loadtest/run-comparison.sh",
  ].join(" \\\n  ");
}

async function initialize() {
  const response = await fetch("/testzone-data.json?v=2026072401");
  if (!response.ok) throw new Error(`TestZone data request failed: ${response.status}`);
  state.payload = await response.json();
  fillSelect(
    els.projectSelect,
    state.payload.projects.map((project) => project.id),
    (id) => state.payload.projects.find((project) => project.id === id).name,
  );
  els.generatedAt.textContent = `Catalog generated ${formatDate(state.payload.generatedAt)}`;
  refreshProject();
}

els.projectSelect.addEventListener("change", refreshProject);
els.executionSelect.addEventListener("change", refreshExecution);
els.scenarioSelect.addEventListener("change", render);
els.toolSelect.addEventListener("change", render);
els.resetFiltersButton.addEventListener("click", refreshExecution);
els.newRunButton.addEventListener("click", () => {
  updateRunCommand();
  els.newRunDialog.showModal();
});
[els.runProfile, els.runTool, els.mvcRefInput, els.webfluxRefInput, els.runScenarios]
  .forEach((element) => element.addEventListener("input", updateRunCommand));
els.copyRunCommandButton.addEventListener("click", async () => {
  await navigator.clipboard.writeText(els.runCommand.textContent);
  els.copyRunCommandButton.textContent = "Copied";
  window.setTimeout(() => {
    els.copyRunCommandButton.textContent = "Copy run command";
  }, 1200);
});
window.addEventListener("resize", () => {
  if (state.execution) renderCharts(currentResults());
});

initialize().catch((error) => {
  console.error(error);
  els.verdictTitle.textContent = "TestZone data unavailable";
  els.verdictDetail.textContent = error.message;
  els.verdictBand.dataset.tone = "failed";
});
