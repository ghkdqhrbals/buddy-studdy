import {
  buildClientErrorRateQuery,
  buildErrorRateQuery,
  buildLatencyQuantileQuery,
  buildRequestRateQuery,
  chooseMetricStepMs,
  counterDeltaPoints,
  counterRatePoints,
  customMetricRange,
  formatBytes,
  formatCount,
  formatDurationSeconds,
  formatLogqlDuration,
  formatMilliseconds,
  formatPercent,
  formatRate,
  hasUnrecoveredRuntimeFailure,
  parseLokiMetricValues,
  parseRuntimeMetrics,
  percentagePoints,
  readLokiJson,
  relativeMetricRange,
  ratioPoints,
  toDateTimeLocalValue,
} from "./metrics.js?v=2026072501";

const RUNTIME_QUERY = '{container=~"buddystudy-backend.*"} |= "runtime_metrics "';
const RUNTIME_FAILURE_QUERY = '{container=~"buddystudy-backend.*"} |= "runtime_metrics_collection_failed"';
const COLORS = {
  blue: "#2563eb",
  red: "#c7354a",
  green: "#12805c",
  purple: "#6d4aff",
  yellow: "#b77900",
  cyan: "#0891b2",
  gray: "#7b8798",
  orange: "#d05b20",
};

const state = {
  range: null,
  snapshots: [],
  collectionFailures: [],
  requestRate: [],
  clientErrorRate: [],
  serverErrorRate: [],
  p50: [],
  p95: [],
  p99: [],
};

const els = {
  rangeSelect: document.querySelector("#rangeSelect"),
  customRangeFields: document.querySelector("#customRangeFields"),
  rangeFromInput: document.querySelector("#rangeFromInput"),
  rangeToInput: document.querySelector("#rangeToInput"),
  applyRangeButton: document.querySelector("#applyRangeButton"),
  refreshButton: document.querySelector("#refreshButton"),
  statusMessage: document.querySelector("#statusMessage"),
  rpsSummary: document.querySelector("#rpsSummary"),
  p95Summary: document.querySelector("#p95Summary"),
  p99Summary: document.querySelector("#p99Summary"),
  errorSummary: document.querySelector("#errorSummary"),
  processCpuSummary: document.querySelector("#processCpuSummary"),
  dbSummary: document.querySelector("#dbSummary"),
  dbSummaryDetail: document.querySelector("#dbSummaryDetail"),
  heapSummary: document.querySelector("#heapSummary"),
  gcPauseSummary: document.querySelector("#gcPauseSummary"),
  blockedThreadsSummary: document.querySelector("#blockedThreadsSummary"),
  eventLoopSummary: document.querySelector("#eventLoopSummary"),
  diagnosisList: document.querySelector("#diagnosisList"),
  snapshotTimestamp: document.querySelector("#snapshotTimestamp"),
  runtimeDetails: document.querySelector("#runtimeDetails"),
};

const chartDefinitions = [
  {
    canvas: document.querySelector("#throughputChart"),
    legend: document.querySelector("#throughputLegend"),
    unit: "rate",
    curve: true,
    fillPrimary: true,
    series: () => [
      { name: "Requests", color: COLORS.blue, points: state.requestRate },
      { name: "4xx", color: COLORS.yellow, points: state.clientErrorRate },
      { name: "5xx", color: COLORS.red, points: state.serverErrorRate },
    ],
  },
  {
    canvas: document.querySelector("#latencyChart"),
    legend: document.querySelector("#latencyLegend"),
    unit: "milliseconds",
    curve: true,
    fillPrimary: true,
    series: () => [
      { name: "p50", color: COLORS.green, points: state.p50 },
      { name: "p95", color: COLORS.blue, points: state.p95 },
      { name: "p99", color: COLORS.red, points: state.p99 },
    ],
  },
  {
    canvas: document.querySelector("#errorChart"),
    legend: document.querySelector("#errorLegend"),
    unit: "percent",
    curve: true,
    series: () => [
      { name: "4xx ratio", color: COLORS.yellow, points: clientErrorRatio() },
      { name: "5xx ratio", color: COLORS.red, points: serverErrorRatio() },
    ],
  },
  {
    canvas: document.querySelector("#cpuChart"),
    legend: document.querySelector("#cpuLegend"),
    unit: "percent",
    max: 100,
    series: () => [
      metricSeries("Process", "processCpuPercent", COLORS.blue),
      metricSeries("System", "systemCpuPercent", COLORS.purple),
    ],
  },
  {
    canvas: document.querySelector("#dbSaturationChart"),
    legend: document.querySelector("#dbSaturationLegend"),
    unit: "percent",
    max: 100,
    series: () => [
      {
        name: "Acquired / max",
        color: COLORS.red,
        points: percentagePoints(state.snapshots, "dbPoolAcquired", "dbPoolMaxAllocated"),
      },
    ],
  },
  {
    canvas: document.querySelector("#dbChart"),
    legend: document.querySelector("#dbLegend"),
    unit: "count",
    series: () => [
      metricSeries("Max", "dbPoolMaxAllocated", COLORS.gray),
      metricSeries("Allocated", "dbPoolAllocated", COLORS.blue),
      metricSeries("Acquired", "dbPoolAcquired", COLORS.red),
      metricSeries("Idle", "dbPoolIdle", COLORS.green),
      metricSeries("Pending", "dbPoolPending", COLORS.yellow),
    ],
  },
  {
    canvas: document.querySelector("#nettyChart"),
    legend: document.querySelector("#nettyLegend"),
    unit: "count",
    series: () => [
      metricSeries("Pending total", "reactorNettyEventLoopPendingTasks", COLORS.red),
      metricSeries("Max per loop", "reactorNettyEventLoopMaxPendingTasks", COLORS.yellow),
      metricSeries("Active connections", "reactorNettyActiveConnections", COLORS.blue),
    ],
  },
  {
    canvas: document.querySelector("#jvmMemoryChart"),
    legend: document.querySelector("#jvmMemoryLegend"),
    unit: "bytes",
    series: () => [
      metricSeries("Heap", "heapUsedBytes", COLORS.blue),
      metricSeries("Non-heap", "nonHeapUsedBytes", COLORS.purple),
      metricSeries("Netty direct", "reactorNettyDirectMemoryBytes", COLORS.yellow),
      metricSeries("Process RSS", "processResidentMemoryBytes", COLORS.green),
    ],
  },
  {
    canvas: document.querySelector("#heapPressureChart"),
    legend: document.querySelector("#heapPressureLegend"),
    unit: "percent",
    max: 100,
    series: () => [
      {
        name: "Heap used",
        color: COLORS.red,
        points: percentagePoints(state.snapshots, "heapUsedBytes", "heapMaxBytes"),
      },
    ],
  },
  {
    canvas: document.querySelector("#hostMemoryChart"),
    legend: document.querySelector("#hostMemoryLegend"),
    unit: "bytes",
    series: () => [
      metricSeries("Used", "hostMemoryUsedBytes", COLORS.red),
      metricSeries("Available", "hostMemoryAvailableBytes", COLORS.green),
    ],
  },
  {
    canvas: document.querySelector("#threadsChart"),
    legend: document.querySelector("#threadsLegend"),
    unit: "count",
    series: () => [
      metricSeries("Live", "threadsLive", COLORS.blue),
      metricSeries("Runnable", "threadsRunnable", COLORS.green),
      metricSeries("Waiting", "threadsWaiting", COLORS.purple),
      metricSeries("Blocked", "threadsBlocked", COLORS.red),
    ],
  },
  {
    canvas: document.querySelector("#gcChart"),
    legend: document.querySelector("#gcLegend"),
    unit: "milliseconds",
    series: () => [
      {
        name: "GC time",
        color: COLORS.red,
        points: counterDeltaPoints(state.snapshots, "gcCollectionTimeMsTotal"),
      },
    ],
  },
  {
    canvas: document.querySelector("#gcCountChart"),
    legend: document.querySelector("#gcCountLegend"),
    unit: "count",
    series: () => [
      {
        name: "Collections",
        color: COLORS.purple,
        points: counterDeltaPoints(state.snapshots, "gcCollectionsTotal"),
      },
    ],
  },
  {
    canvas: document.querySelector("#networkChart"),
    legend: document.querySelector("#networkLegend"),
    unit: "bytesRate",
    series: () => [
      {
        name: "Receive",
        color: COLORS.green,
        points: counterRatePoints(state.snapshots, "networkReceiveBytesTotal"),
      },
      {
        name: "Transmit",
        color: COLORS.blue,
        points: counterRatePoints(state.snapshots, "networkTransmitBytesTotal"),
      },
    ],
  },
  {
    canvas: document.querySelector("#diskChart"),
    legend: document.querySelector("#diskLegend"),
    unit: "bytes",
    series: () => [
      metricSeries("Used", "rootDiskUsedBytes", COLORS.red),
      metricSeries("Available", "rootDiskUsableBytes", COLORS.green),
    ],
  },
];

function ns(ms) {
  return (BigInt(ms) * 1_000_000n).toString();
}

function currentRange() {
  const { startMs, endMs } = els.rangeSelect.value === "custom"
    ? customMetricRange(els.rangeFromInput.value, els.rangeToInput.value)
    : relativeMetricRange(els.rangeSelect.value);
  return { startMs, endMs, startNs: ns(startMs), endNs: ns(endMs) };
}

function initializeCustomRange() {
  const endMs = Date.now();
  els.rangeFromInput.value = toDateTimeLocalValue(endMs - 3_600_000);
  els.rangeToInput.value = toDateTimeLocalValue(endMs);
}

function updateCustomRangeVisibility() {
  els.customRangeFields.classList.toggle("is-custom", els.rangeSelect.value === "custom");
}

async function lokiQueryRange(query, range, { limit = 5000, step = null, direction = "forward" } = {}) {
  const params = new URLSearchParams({
    query,
    start: range.startNs,
    end: range.endNs,
    limit: String(limit),
    direction,
  });
  if (step) params.set("step", step);
  const response = await fetch(`/loki/api/v1/query_range?${params.toString()}`);
  const payload = await readLokiJson(response);
  return (payload.data?.result ?? []).flatMap((stream) => stream.values ?? []);
}

async function loadMetrics() {
  setStatus("Loading server metrics...", "loading");
  els.refreshButton.disabled = true;
  els.applyRangeButton.disabled = true;
  try {
    const range = currentRange();
    const stepMs = chooseMetricStepMs(range.endMs - range.startMs);
    const step = formatLogqlDuration(stepMs);
    const window = formatLogqlDuration(Math.max(30_000, stepMs));

    const [
      runtimeValues,
      runtimeFailureValues,
      requestValues,
      clientErrorValues,
      serverErrorValues,
      p50Values,
      p95Values,
      p99Values,
    ] = await Promise.all([
      lokiQueryRange(RUNTIME_QUERY, range),
      lokiQueryRange(RUNTIME_FAILURE_QUERY, range, { limit: 100 }),
      lokiQueryRange(buildRequestRateQuery(window), range, { limit: 1000, step }),
      lokiQueryRange(buildClientErrorRateQuery(window), range, { limit: 1000, step }),
      lokiQueryRange(buildErrorRateQuery(window), range, { limit: 1000, step }),
      lokiQueryRange(buildLatencyQuantileQuery(0.5, window), range, { limit: 1000, step }),
      lokiQueryRange(buildLatencyQuantileQuery(0.95, window), range, { limit: 1000, step }),
      lokiQueryRange(buildLatencyQuantileQuery(0.99, window), range, { limit: 1000, step }),
    ]);

    state.range = range;
    state.snapshots = runtimeValues
      .map((value) => {
        try {
          return parseRuntimeMetrics(value);
        } catch (error) {
          console.warn("Failed to parse runtime_metrics", error, value);
          return null;
        }
      })
      .filter(Boolean)
      .sort((a, b) => a.ms - b.ms);
    state.collectionFailures = runtimeFailureValues
      .map(([timestamp, line]) => ({
        ms: Number(BigInt(timestamp) / 1_000_000n),
        line,
      }))
      .sort((a, b) => a.ms - b.ms);
    state.requestRate = parseLokiMetricValues(requestValues);
    state.clientErrorRate = parseLokiMetricValues(clientErrorValues);
    state.serverErrorRate = parseLokiMetricValues(serverErrorValues);
    state.p50 = parseLokiMetricValues(p50Values);
    state.p95 = parseLokiMetricValues(p95Values);
    state.p99 = parseLokiMetricValues(p99Values);
    render();

    const hasData = state.snapshots.length || state.requestRate.length;
    const latest = state.snapshots.at(-1);
    if (hasUnrecoveredRuntimeFailure(state.snapshots, state.collectionFailures)) {
      setStatus("Runtime metric collection failed", "error");
    } else if (latest?.runtimeMetricsDegraded) {
      setStatus("Ready with partial runtime metrics", "warning");
    } else {
      setStatus(hasData ? "Ready" : "Waiting for server samples", hasData ? "ready" : "loading");
    }
  } finally {
    els.refreshButton.disabled = false;
    els.applyRangeButton.disabled = false;
  }
}

function metricSeries(name, field, color) {
  return {
    name,
    color,
    points: state.snapshots
      .map((sample) => ({ ms: sample.ms, value: Number(sample[field]) }))
      .filter((point) => Number.isFinite(point.value)),
  };
}

function clientErrorRatio() {
  return ratioPoints(state.clientErrorRate, state.requestRate);
}

function serverErrorRatio() {
  return ratioPoints(state.serverErrorRate, state.requestRate);
}

function render() {
  renderSummary();
  renderDiagnosis();
  renderDetails();
  for (const definition of chartDefinitions) {
    const series = definition.series();
    renderLegend(definition.legend, series, definition.unit);
    drawChart(definition.canvas, series, state.range, definition);
  }
}

function renderSummary() {
  const latest = state.snapshots.at(-1);
  const latestDbSaturation = percentagePoints(
    latest ? [latest] : [],
    "dbPoolAcquired",
    "dbPoolMaxAllocated",
  ).at(-1)?.value;
  const latestServerErrorRatio = serverErrorRatio().at(-1)?.value;

  els.rpsSummary.textContent = formatRate(latestValue(state.requestRate));
  els.p95Summary.textContent = formatMilliseconds(latestValue(state.p95));
  els.p99Summary.textContent = formatMilliseconds(latestValue(state.p99));
  els.errorSummary.textContent = formatPercent(latestServerErrorRatio);
  els.processCpuSummary.textContent = formatPercent(latest?.processCpuPercent);
  els.dbSummary.textContent = formatPercent(latestDbSaturation);
  els.dbSummaryDetail.textContent = latest?.dbPoolAcquired == null
    ? "connection pool unavailable"
    : `${formatCount(latest.dbPoolAcquired)} / ${formatCount(latest.dbPoolMaxAllocated)} acquired, ${formatCount(latest.dbPoolPending)} pending`;
  els.heapSummary.textContent = formatPercent(
    latest ? percentage(latest.heapUsedBytes, latest.heapMaxBytes) : null,
  );
  els.gcPauseSummary.textContent = formatMilliseconds(
    counterDeltaPoints(state.snapshots, "gcCollectionTimeMsTotal").at(-1)?.value,
  );
  els.blockedThreadsSummary.textContent = formatCount(latest?.threadsBlocked);
  els.eventLoopSummary.textContent = formatCount(latest?.reactorNettyEventLoopMaxPendingTasks);
}

function renderDiagnosis() {
  const latest = state.snapshots.at(-1);
  const issues = [];
  const p50 = latestValue(state.p50);
  const p99 = latestValue(state.p99);
  const clientErrors = latestValue(clientErrorRatio());
  const serverErrors = latestValue(serverErrorRatio());
  const dbSaturation = percentagePoints(
    latest ? [latest] : [],
    "dbPoolAcquired",
    "dbPoolMaxAllocated",
  ).at(-1)?.value;
  const heapSaturation = latest
    ? percentage(latest.heapUsedBytes, latest.heapMaxBytes)
    : null;
  const diskSaturation = latest
    ? percentage(latest.rootDiskUsedBytes, latest.rootDiskTotalBytes)
    : null;

  if (serverErrors >= 1) {
    issues.push(["critical", `5xx ratio is ${formatPercent(serverErrors)}. Inspect failed request traces first.`]);
  } else if (serverErrors > 0) {
    issues.push(["warning", `5xx responses are present at ${formatPercent(serverErrors)}.`]);
  }
  if (clientErrors >= 10) {
    issues.push(["warning", `4xx ratio is ${formatPercent(clientErrors)}. Check authentication, validation, and client version patterns.`]);
  }
  if (Number.isFinite(p99) && Number.isFinite(p50) && p99 >= 100 && p99 >= p50 * 4) {
    issues.push(["warning", `Tail latency is spread: p99 ${formatMilliseconds(p99)} versus p50 ${formatMilliseconds(p50)}.`]);
  }
  if (Number(latest?.processCpuPercent) >= 80) {
    issues.push(["critical", `Process CPU is ${formatPercent(latest.processCpuPercent)} and may be constraining throughput.`]);
  }
  if (Number(dbSaturation) >= 80 || Number(latest?.dbPoolPending) > 0) {
    issues.push(["critical", `Database pool pressure is ${formatPercent(dbSaturation)} with ${formatCount(latest?.dbPoolPending)} pending acquisitions.`]);
  }
  if (Number(latest?.reactorNettyEventLoopMaxPendingTasks) > 0) {
    issues.push(["warning", `Reactor Netty has ${formatCount(latest.reactorNettyEventLoopMaxPendingTasks)} pending tasks on the busiest event loop.`]);
  }
  if (Number(latest?.threadsBlocked) > 0) {
    issues.push(["warning", `${formatCount(latest.threadsBlocked)} runtime threads are blocked.`]);
  }
  if (Number(heapSaturation) >= 85) {
    issues.push(["warning", `Heap usage is ${formatPercent(heapSaturation)} of maximum. Compare the post-GC floor over time.`]);
  }
  if (Number(diskSaturation) >= 85) {
    issues.push(["critical", `Root filesystem usage is ${formatPercent(diskSaturation)}.`]);
  }
  if (latest?.runtimeMetricsDegraded) {
    issues.push([
      "warning",
      `Runtime metrics are partial. Unavailable collectors: ${latest.runtimeMetricsUnavailable || "unknown"}.`,
    ]);
  }
  if (hasUnrecoveredRuntimeFailure(state.snapshots, state.collectionFailures)) {
    const failure = state.collectionFailures.at(-1);
    issues.push([
      "critical",
      `Runtime metric collection failed at ${formatKst(failure.ms)}. Inspect backend logs for the stack trace.`,
    ]);
  }

  els.diagnosisList.innerHTML = "";
  const visibleIssues = issues.slice(0, 5);
  if (!visibleIssues.length) {
    const hasData = latest || state.requestRate.length;
    visibleIssues.push([
      hasData ? "ready" : "neutral",
      hasData
        ? "No immediate error or saturation threshold is crossed. Compare trends before declaring the service healthy."
        : "No runtime sample was emitted in this range. Check the backend reporter and Loki forwarding.",
    ]);
  }
  for (const [tone, message] of visibleIssues) {
    const item = document.createElement("li");
    item.dataset.tone = tone;
    item.textContent = message;
    els.diagnosisList.append(item);
  }
}

function renderDetails() {
  const latest = state.snapshots.at(-1);
  els.runtimeDetails.innerHTML = "";
  if (!latest) {
    els.snapshotTimestamp.textContent = "No runtime sample in this time range";
    return;
  }
  els.snapshotTimestamp.textContent = formatKst(latest.ms);
  const details = [
    ["Runtime", `${latest.runtimeName || latest.jvmName || "Unknown"} ${latest.runtimeVersion || latest.jvmVersion || ""}`.trim()],
    ["Runtime mode", latest.runtimeKind || "jvm"],
    ["Collector", latest.runtimeMetricsDegraded
      ? `Partial: ${latest.runtimeMetricsUnavailable || "unknown"}`
      : "Complete"],
    ["Uptime", formatDurationSeconds(latest.processUptimeSeconds)],
    ["Processors", formatCount(latest.availableProcessors)],
    ["1m load average", finiteNumber(latest.systemLoadAverage1m, 2)],
    ["Host memory", `${formatBytes(latest.hostMemoryUsedBytes)} / ${formatBytes(latest.hostMemoryTotalBytes)}`],
    ["Root filesystem", `${formatBytes(latest.rootDiskUsedBytes)} / ${formatBytes(latest.rootDiskTotalBytes)}`],
    ["Open file descriptors", formatCount(latest.processOpenFileDescriptors)],
    ["Heap", `${formatBytes(latest.heapUsedBytes)} / ${formatBytes(latest.heapCommittedBytes)} committed / ${formatBytes(latest.heapMaxBytes)} max`],
    ["Non-heap", `${formatBytes(latest.nonHeapUsedBytes)} / ${formatBytes(latest.nonHeapCommittedBytes)} committed`],
    ["Runtime direct buffers", `${formatCount(latest.directBufferCount)} buffers, ${formatBytes(latest.directBufferMemoryUsedBytes)}`],
    ["Netty direct memory", formatBytes(latest.reactorNettyDirectMemoryBytes)],
    ["Threads", `${formatCount(latest.threadsLive)} live, ${formatCount(latest.threadsDaemon)} daemon, ${formatCount(latest.threadsPeak)} peak`],
    ["Thread states", `${formatCount(latest.threadsRunnable)} runnable, ${formatCount(latest.threadsWaiting)} waiting, ${formatCount(latest.threadsTimedWaiting)} timed, ${formatCount(latest.threadsBlocked)} blocked`],
    ["Event loop", `${formatCount(latest.reactorNettyEventLoopPendingTasks)} pending total, ${formatCount(latest.reactorNettyEventLoopMaxPendingTasks)} max per loop`],
    ["Active connections", formatCount(latest.reactorNettyActiveConnections)],
    ["GC total", `${formatCount(latest.gcCollectionsTotal)} collections, ${formatCount(latest.gcCollectionTimeMsTotal)} ms`],
    ["Classes", `${formatCount(latest.classesLoaded)} loaded, ${formatCount(latest.classesUnloadedTotal)} unloaded`],
    ["Database pool", latest.dbPoolAllocated == null
      ? "Not available"
      : `${formatCount(latest.dbPoolAllocated)} allocated, ${formatCount(latest.dbPoolAcquired)} acquired, ${formatCount(latest.dbPoolIdle)} idle, ${formatCount(latest.dbPoolPending)} pending`],
  ];
  for (const [label, value] of details) {
    const term = document.createElement("dt");
    const description = document.createElement("dd");
    term.textContent = label;
    description.textContent = value;
    els.runtimeDetails.append(term, description);
  }
}

function renderLegend(element, series, unit) {
  element.innerHTML = "";
  for (const item of series) {
    const latest = item.points.at(-1)?.value;
    const entry = document.createElement("span");
    entry.innerHTML = `<i class="${colorClass(item.color)}"></i>${escapeHtml(item.name)} <strong>${escapeHtml(formatAxisValue(latest, unit))}</strong>`;
    element.append(entry);
  }
}

function drawChart(canvas, series, range, options) {
  const context = canvas.getContext("2d");
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.floor(rect.width * ratio));
  canvas.height = Math.max(1, Math.floor(rect.height * ratio));
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, rect.width, rect.height);

  const padding = { top: 16, right: 16, bottom: 28, left: 62 };
  const width = Math.max(1, rect.width - padding.left - padding.right);
  const height = Math.max(1, rect.height - padding.top - padding.bottom);
  const values = series.flatMap((item) => item.points.map((point) => point.value)).filter(Number.isFinite);
  const maxValue = options.max ?? Math.max(1, ...values);
  const startMs = range?.startMs ?? Date.now() - 3_600_000;
  const endMs = range?.endMs ?? Date.now();
  configureChartTooltip(canvas, series, {
    ...options,
    startMs,
    endMs,
    padding,
    plotWidth: width,
  });

  context.strokeStyle = "#e1e7f0";
  context.lineWidth = 1;
  context.beginPath();
  for (let index = 0; index <= 4; index += 1) {
    const y = padding.top + (height * index) / 4;
    context.moveTo(padding.left, y);
    context.lineTo(padding.left + width, y);
    const x = padding.left + (width * index) / 4;
    context.moveTo(x, padding.top);
    context.lineTo(x, padding.top + height);
  }
  context.stroke();

  context.fillStyle = "#66758a";
  context.font = "11px Inter, system-ui, sans-serif";
  context.textAlign = "right";
  context.textBaseline = "middle";
  for (let index = 0; index <= 4; index += 1) {
    const value = maxValue - (maxValue * index) / 4;
    const y = padding.top + (height * index) / 4;
    context.fillText(formatAxisValue(value, options.unit), padding.left - 9, y);
  }

  let hasPoints = false;
  series.forEach((item, seriesIndex) => {
    const points = item.points.filter((point) => point.ms >= startMs && point.ms <= endMs && Number.isFinite(point.value));
    if (!points.length) return;
    hasPoints = true;
    const coordinates = points.map((point) => ({
      x: padding.left + ((point.ms - startMs) / Math.max(1, endMs - startMs)) * width,
      y: padding.top + height - (point.value / maxValue) * height,
    }));

    if (options.fillPrimary && seriesIndex === 0 && coordinates.length > 1) {
      const fill = context.createLinearGradient(0, padding.top, 0, padding.top + height);
      fill.addColorStop(0, `${item.color}38`);
      fill.addColorStop(1, `${item.color}05`);
      context.beginPath();
      traceSeriesPath(context, coordinates, options.curve);
      context.lineTo(coordinates.at(-1).x, padding.top + height);
      context.lineTo(coordinates[0].x, padding.top + height);
      context.closePath();
      context.fillStyle = fill;
      context.fill();
    }

    context.strokeStyle = item.color;
    context.lineWidth = seriesIndex === 0 ? 2.5 : 2;
    context.lineJoin = "round";
    context.lineCap = "round";
    context.beginPath();
    traceSeriesPath(context, coordinates, options.curve);
    context.stroke();
  });

  if (!hasPoints) {
    context.fillStyle = "#8b98aa";
    context.textAlign = "center";
    context.fillText("No metric data in this time range", padding.left + width / 2, padding.top + height / 2);
  }

  context.fillStyle = "#66758a";
  context.textBaseline = "top";
  for (let index = 0; index <= 4; index += 1) {
    const x = padding.left + (width * index) / 4;
    const timestamp = startMs + ((endMs - startMs) * index) / 4;
    context.textAlign = index === 0 ? "left" : index === 4 ? "right" : "center";
    context.fillText(formatKstAxis(timestamp), x, padding.top + height + 9);
  }
}

function configureChartTooltip(canvas, series, model) {
  canvas._metricTooltipModel = { series, ...model };
  if (canvas.dataset.tooltipReady === "true") return;
  canvas.dataset.tooltipReady = "true";
  const panel = canvas.closest(".metric-chart-panel");
  if (!panel) return;
  const cursor = document.createElement("div");
  cursor.className = "metric-chart-cursor";
  cursor.hidden = true;
  const tooltip = document.createElement("div");
  tooltip.className = "metric-chart-tooltip";
  tooltip.hidden = true;
  panel.append(cursor, tooltip);

  const handleChartHover = (event) => {
    const current = canvas._metricTooltipModel;
    if (!current) return;
    const rect = canvas.getBoundingClientRect();
    const x = Number.isFinite(event.offsetX) ? event.offsetX : event.clientX - rect.left;
    const plotStart = current.padding.left;
    const plotEnd = plotStart + current.plotWidth;
    if (x < plotStart || x > plotEnd) {
      tooltip.hidden = true;
      cursor.hidden = true;
      return;
    }
    const timestamp = current.startMs
      + ((x - plotStart) / Math.max(1, current.plotWidth)) * (current.endMs - current.startMs);
    const values = current.series
      .map((item) => ({ item, point: nearestPoint(item.points, timestamp) }))
      .filter(({ point }) => point && Number.isFinite(point.value));
    if (!values.length) {
      tooltip.hidden = true;
      cursor.hidden = true;
      return;
    }

    const heading = document.createElement("strong");
    heading.textContent = formatKst(timestamp);
    const rows = values.map(({ item, point }) => {
      const row = document.createElement("span");
      const swatch = document.createElement("i");
      swatch.className = colorClass(item.color);
      const label = document.createElement("em");
      label.textContent = item.name;
      const value = document.createElement("b");
      value.textContent = formatAxisValue(point.value, current.unit);
      row.append(swatch, label, value);
      return row;
    });
    tooltip.replaceChildren(heading, ...rows);
    tooltip.hidden = false;
    cursor.hidden = false;
    cursor.style.left = `${x}px`;
    const tooltipWidth = 220;
    tooltip.style.left = `${Math.min(
      Math.max(8, x + 12),
      Math.max(8, panel.clientWidth - tooltipWidth - 8),
    )}px`;
    tooltip.style.top = `${canvas.offsetTop + 8}px`;
  };
  canvas.addEventListener("mousemove", handleChartHover);
  const hideChartTooltip = () => {
    tooltip.hidden = true;
    cursor.hidden = true;
  };
  canvas.addEventListener("mouseleave", hideChartTooltip);
}

function nearestPoint(points, timestamp) {
  let nearest = null;
  let distance = Number.POSITIVE_INFINITY;
  for (const point of points) {
    const nextDistance = Math.abs(point.ms - timestamp);
    if (nextDistance < distance) {
      nearest = point;
      distance = nextDistance;
    }
  }
  return nearest;
}

function colorClass(color) {
  const match = Object.entries(COLORS)
    .find(([, value]) => value.toLowerCase() === String(color).toLowerCase());
  return `metric-color-${match?.[0] || "gray"}`;
}

function traceSeriesPath(context, coordinates, curved) {
  context.moveTo(coordinates[0].x, coordinates[0].y);
  for (let index = 1; index < coordinates.length; index += 1) {
    const previous = coordinates[index - 1];
    const current = coordinates[index];
    if (!curved) {
      context.lineTo(current.x, current.y);
      continue;
    }
    const midpointX = previous.x + (current.x - previous.x) / 2;
    context.bezierCurveTo(midpointX, previous.y, midpointX, current.y, current.x, current.y);
  }
}

function formatAxisValue(value, unit) {
  if (!Number.isFinite(Number(value))) return "-";
  if (unit === "bytes") return formatBytes(value);
  if (unit === "bytesRate") return `${formatBytes(value)}/s`;
  if (unit === "percent") return formatPercent(value);
  if (unit === "rate") return formatRate(value);
  if (unit === "milliseconds") return formatMilliseconds(value);
  return formatCount(value);
}

function latestValue(points) {
  return points.at(-1)?.value;
}

function percentage(numerator, denominator) {
  const first = Number(numerator);
  const second = Number(denominator);
  if (!Number.isFinite(first) || !Number.isFinite(second) || second <= 0) return null;
  return (first / second) * 100;
}

function finiteNumber(value, digits) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(digits) : "-";
}

function formatKst(ms) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(ms));
}

function formatKstAxis(ms) {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(ms));
}

function setStatus(message, tone) {
  els.statusMessage.textContent = message;
  els.statusMessage.dataset.tone = tone;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

els.rangeSelect.addEventListener("change", () => {
  updateCustomRangeVisibility();
  if (els.rangeSelect.value !== "custom") {
    loadMetrics().catch((error) => setStatus(error.message, "error"));
  }
});
els.applyRangeButton.addEventListener("click", () => {
  loadMetrics().catch((error) => setStatus(error.message, "error"));
});
els.refreshButton.addEventListener("click", () => {
  loadMetrics().catch((error) => setStatus(error.message, "error"));
});
window.addEventListener("resize", () => {
  window.clearTimeout(window.metricsResizeTimer);
  window.metricsResizeTimer = window.setTimeout(render, 120);
});

initializeCustomRange();
updateCustomRangeVisibility();
render();
loadMetrics().catch((error) => setStatus(error.message, "error"));
