import { useMemo } from "react";
import { metricCatalog, overviewTrendMetrics } from "./adminConfig";
import { MultiLineChart, Sparkline } from "./Charts";
import { EmptyState } from "./EmptyState";
import { fallbackDefinition, formatCompact, formatDateTime, formatDelta, formatMetric } from "./format";
import { OperationsPanel } from "./OperationsPanel";
import type { AdminMetricSeries, ScheduledJobRun } from "./types";

type MetricsDashboardProps = {
  series: AdminMetricSeries[];
  metricKeys: string[];
  jobs: ScheduledJobRun[];
  operationsHrefForPage: (page: number) => string;
};

export function MetricsDashboard({ series, metricKeys, jobs, operationsHrefForPage }: MetricsDashboardProps) {
  const seriesByKey = useMemo(() => new Map(series.map((item) => [item.metricKey, item])), [series]);
  const featured = metricKeys.map((key) => seriesByKey.get(key)).filter(Boolean) as AdminMetricSeries[];
  const trendKeys = metricKeys.length > 4 ? overviewTrendMetrics : metricKeys;
  const chartSeries = trendKeys.map((key) => seriesByKey.get(key)).filter((item): item is AdminMetricSeries => Boolean(item && item.points.length > 0));
  const failedJobs = jobs.filter((job) => job.status === "FAILED").slice(0, 3);

  if (featured.length === 0) {
    return <EmptyState title="No metrics" message="No data returned for the selected range." />;
  }

  return (
    <section className="dashboard">
      <div className="metric-rail" aria-label="Metric cards">
        {featured.map((item) => (
          <MetricCard key={item.metricKey} item={item} />
        ))}
      </div>

      <div className="dashboard-grid">
        <div className="primary-column">
          <div className="chart-panel">
            <div className="panel-header">
              <h2>Trends</h2>
            </div>
            <MultiLineChart series={chartSeries} />
          </div>
          <OperationsPanel
            page={{ runs: jobs.slice(0, 5), totalCount: jobs.length, limit: 5, offset: 0 }}
            onRetry={() => {}}
            hrefForPage={operationsHrefForPage}
            compact
          />
        </div>

        <aside className="insight-column">
          <TodaySummary series={featured} />
          <QuotaPanel series={seriesByKey.get("quota_used_count")} />
          <FailedJobs jobs={failedJobs} />
        </aside>
      </div>
    </section>
  );
}

function MetricCard({ item }: { item: AdminMetricSeries }) {
  const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
  const last = item.points.at(-1);
  const previous = item.points.at(-2);
  const delta = last && previous ? last.value - previous.value : 0;
  return (
    <article className="metric-card">
      <div className="metric-title">
        <span title={definition.label}>{definition.shortLabel}</span>
      </div>
      <strong>{formatMetric(definition, last?.value ?? 0)}</strong>
      <small className={delta >= 0 ? "positive" : "negative"}>
        {formatDelta(definition, delta)}
      </small>
      <Sparkline item={item} definition={definition} />
    </article>
  );
}

function TodaySummary({ series }: { series: AdminMetricSeries[] }) {
  return (
    <section className="side-panel">
      <h2>Latest</h2>
      <div className="summary-list">
        {series.slice(0, 6).map((item) => {
          const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
          const latest = item.points.at(-1);
          return (
            <div key={item.metricKey}>
              <span>{definition.shortLabel}</span>
              <strong>{formatMetric(definition, latest?.value ?? 0)}</strong>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function QuotaPanel({ series }: { series?: AdminMetricSeries }) {
  const latest = series?.points.at(-1)?.value ?? 0;
  const limit = Math.max(30000, latest);
  const ratio = Math.min(100, Math.round((latest / limit) * 100));
  return (
    <section className="side-panel quota-panel">
      <h2>Quota</h2>
      <div className="quota-meter" aria-label={`Quota used ${ratio}%`}>
        <span style={{ width: `${ratio}%` }} />
      </div>
      <div className="quota-value">
        <strong>{ratio}%</strong>
        <span>{formatCompact(latest)} / {formatCompact(limit)}</span>
      </div>
    </section>
  );
}

function FailedJobs({ jobs }: { jobs: ScheduledJobRun[] }) {
  return (
    <section className="side-panel">
      <h2>Failures</h2>
      {jobs.length === 0 ? <p className="muted-line">None</p> : null}
      {jobs.map((job) => (
        <div className="failed-job" key={job.id}>
          <strong>{job.jobName}</strong>
          <span>{formatDateTime(job.startedAt)}</span>
          <small>{job.errorMessage ?? job.summary ?? "Failed"}</small>
        </div>
      ))}
    </section>
  );
}
