import {
  buildErrorRateQuery,
  buildRequestRateQuery,
  chooseMetricStepMs,
  counterDeltaPoints,
  counterRatePoints,
  formatBytes,
  formatCount,
  formatDurationSeconds,
  formatLogqlDuration,
  formatPercent,
  formatRate,
  parseLokiMetricValues,
  parseRuntimeMetrics,
} from "./metrics.js?v=2026072301";

const RUNTIME_QUERY = '{container=~"buddystudy-backend.*"} |= "runtime_metrics "';
const COLORS = {
  blue: "#2563eb",
  red: "#c7354a",
  green: "#12805c",
  purple: "#6d4aff",
  yellow: "#b77900",
  cyan: "#0891b2",
  gray: "#7b8798",
};

const state = {
  range: null,
  snapshots: [],
  requestRate: [],
  errorRate: [],
};

const els = {
  rangeSelect: document.querySelector("#rangeSelect"),
  refreshButton: document.querySelector("#refreshButton"),
  statusMessage: document.querySelector("#statusMessage"),
  processCpuSummary: document.querySelector("#processCpuSummary"),
  heapSummary: document.querySelector("#heapSummary"),
  rssSummary: document.querySelector("#rssSummary"),
  threadsSummary: document.querySelector("#threadsSummary"),
  rpsSummary: document.querySelector("#rpsSummary"),
  dbSummary: document.querySelector("#dbSummary"),
  snapshotTimestamp: document.querySelector("#snapshotTimestamp"),
  runtimeDetails: document.querySelector("#runtimeDetails"),
};

const chartDefinitions = [
  {
    canvas: document.querySelector("#throughputChart"),
    legend: document.querySelector("#throughputLegend"),
    unit: "rate",
    series: () => [
      { name: "Requests", color: COLORS.blue, points: state.requestRate },
      { name: "5xx", color: COLORS.red, points: state.errorRate },
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
    canvas: document.querySelector("#jvmMemoryChart"),
    legend: document.querySelector("#jvmMemoryLegend"),
    unit: "bytes",
    series: () => [
      metricSeries("Heap", "heapUsedBytes", COLORS.blue),
      metricSeries("Non-heap", "nonHeapUsedBytes", COLORS.purple),
      metricSeries("Direct", "directBufferMemoryUsedBytes", COLORS.yellow),
      metricSeries("Process RSS", "processResidentMemoryBytes", COLORS.green),
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
    canvas: document.querySelector("#dbChart"),
    legend: document.querySelector("#dbLegend"),
    unit: "count",
    series: () => [
      metricSeries("Allocated", "dbPoolAllocated", COLORS.blue),
      metricSeries("Acquired", "dbPoolAcquired", COLORS.red),
      metricSeries("Idle", "dbPoolIdle", COLORS.green),
      metricSeries("Pending", "dbPoolPending", COLORS.yellow),
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
  const endMs = Date.now();
  const startMs = endMs - Number(els.rangeSelect.value);
  return { startMs, endMs, startNs: ns(startMs), endNs: ns(endMs) };
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
  if (!response.ok) throw new Error(`Loki query failed: ${response.status}`);
  const payload = await response.json();
  return (payload.data?.result ?? []).flatMap((stream) => stream.values ?? []);
}

async function loadMetrics() {
  setStatus("Loading system metrics...", "loading");
  const range = currentRange();
  const stepMs = chooseMetricStepMs(range.endMs - range.startMs);
  const step = formatLogqlDuration(stepMs);
  const window = formatLogqlDuration(Math.max(30_000, stepMs));

  const [runtimeValues, requestValues, errorValues] = await Promise.all([
    lokiQueryRange(RUNTIME_QUERY, range),
    lokiQueryRange(buildRequestRateQuery(window), range, { limit: 1000, step }),
    lokiQueryRange(buildErrorRateQuery(window), range, { limit: 1000, step }),
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
  state.requestRate = parseLokiMetricValues(requestValues);
  state.errorRate = parseLokiMetricValues(errorValues);
  render();
  setStatus(state.snapshots.length ? "Ready" : "Waiting for runtime samples", state.snapshots.length ? "ready" : "loading");
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

function render() {
  renderSummary();
  renderDetails();
  for (const definition of chartDefinitions) {
    const series = definition.series();
    renderLegend(definition.legend, series, definition.unit);
    drawChart(definition.canvas, series, state.range, definition);
  }
}

function renderSummary() {
  const latest = state.snapshots.at(-1);
  const latestRps = state.requestRate.at(-1)?.value;
  els.processCpuSummary.textContent = formatPercent(latest?.processCpuPercent);
  els.heapSummary.textContent = latest
    ? `${formatBytes(latest.heapUsedBytes)} / ${formatBytes(latest.heapMaxBytes)}`
    : "-";
  els.rssSummary.textContent = formatBytes(latest?.processResidentMemoryBytes);
  els.threadsSummary.textContent = formatCount(latest?.threadsLive);
  els.rpsSummary.textContent = formatRate(latestRps);
  els.dbSummary.textContent = latest?.dbPoolAcquired == null
    ? "-"
    : `${formatCount(latest.dbPoolAcquired)} / ${formatCount(latest.dbPoolMaxAllocated)}`;
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
    ["JVM", `${latest.jvmName} ${latest.jvmVersion}`],
    ["Uptime", formatDurationSeconds(latest.processUptimeSeconds)],
    ["Processors", formatCount(latest.availableProcessors)],
    ["1m load average", Number(latest.systemLoadAverage1m).toFixed(2)],
    ["Host memory", `${formatBytes(latest.hostMemoryUsedBytes)} / ${formatBytes(latest.hostMemoryTotalBytes)}`],
    ["Root filesystem", `${formatBytes(latest.rootDiskUsedBytes)} / ${formatBytes(latest.rootDiskTotalBytes)}`],
    ["Open file descriptors", formatCount(latest.processOpenFileDescriptors)],
    ["Heap", `${formatBytes(latest.heapUsedBytes)} / ${formatBytes(latest.heapCommittedBytes)} committed / ${formatBytes(latest.heapMaxBytes)} max`],
    ["Non-heap", `${formatBytes(latest.nonHeapUsedBytes)} / ${formatBytes(latest.nonHeapCommittedBytes)} committed`],
    ["Direct buffers", `${formatCount(latest.directBufferCount)} buffers, ${formatBytes(latest.directBufferMemoryUsedBytes)}`],
    ["Threads", `${formatCount(latest.threadsLive)} live, ${formatCount(latest.threadsDaemon)} daemon, ${formatCount(latest.threadsPeak)} peak`],
    ["Thread states", `${formatCount(latest.threadsRunnable)} runnable, ${formatCount(latest.threadsWaiting)} waiting, ${formatCount(latest.threadsTimedWaiting)} timed, ${formatCount(latest.threadsBlocked)} blocked`],
    ["GC total", `${formatCount(latest.gcCollectionsTotal)} collections, ${formatCount(latest.gcCollectionTimeMsTotal)} ms`],
    ["Classes", `${formatCount(latest.classesLoaded)} loaded, ${formatCount(latest.classesUnloadedTotal)} unloaded`],
    ["R2DBC pool", latest.dbPoolAllocated == null
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
    entry.innerHTML = `<i style="background:${item.color}"></i>${escapeHtml(item.name)} <strong>${escapeHtml(formatAxisValue(latest, unit))}</strong>`;
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

  context.strokeStyle = "#e1e7f0";
  context.lineWidth = 1;
  context.beginPath();
  for (let index = 0; index <= 4; index += 1) {
    const y = padding.top + (height * index) / 4;
    context.moveTo(padding.left, y);
    context.lineTo(padding.left + width, y);
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
  for (const item of series) {
    const points = item.points.filter((point) => point.ms >= startMs && point.ms <= endMs && Number.isFinite(point.value));
    if (!points.length) continue;
    hasPoints = true;
    context.strokeStyle = item.color;
    context.lineWidth = 2;
    context.lineJoin = "round";
    context.lineCap = "round";
    context.beginPath();
    points.forEach((point, index) => {
      const x = padding.left + ((point.ms - startMs) / Math.max(1, endMs - startMs)) * width;
      const y = padding.top + height - (point.value / maxValue) * height;
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.stroke();
  }

  if (!hasPoints) {
    context.fillStyle = "#8b98aa";
    context.textAlign = "center";
    context.fillText("No metric data in this time range", padding.left + width / 2, padding.top + height / 2);
  }

  context.fillStyle = "#66758a";
  context.textBaseline = "top";
  context.textAlign = "left";
  context.fillText(formatKstAxis(startMs), padding.left, padding.top + height + 9);
  context.textAlign = "right";
  context.fillText(formatKstAxis(endMs), padding.left + width, padding.top + height + 9);
}

function formatAxisValue(value, unit) {
  if (!Number.isFinite(Number(value))) return "-";
  if (unit === "bytes") return formatBytes(value);
  if (unit === "bytesRate") return `${formatBytes(value)}/s`;
  if (unit === "percent") return formatPercent(value);
  if (unit === "rate") return formatRate(value);
  if (unit === "milliseconds") return `${Math.round(value)} ms`;
  return formatCount(value);
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
  loadMetrics().catch((error) => setStatus(error.message, "error"));
});
els.refreshButton.addEventListener("click", () => {
  loadMetrics().catch((error) => setStatus(error.message, "error"));
});
window.addEventListener("resize", () => {
  window.clearTimeout(window.metricsResizeTimer);
  window.metricsResizeTimer = window.setTimeout(render, 120);
});

loadMetrics().catch((error) => setStatus(error.message, "error"));
