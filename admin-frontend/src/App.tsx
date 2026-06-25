import { FormEvent, useEffect, useMemo, useState } from "react";
import { clearToken, fetchJobRuns, fetchMetrics, getStoredToken, login, refreshMetrics, retryJob, storeToken } from "./api";
import type { AdminDailyMetricPoint, AdminMetricSeries, ScheduledJobRun } from "./types";

type SectionKey = "overview" | "users" | "learning" | "notifications" | "quota" | "operations";
type Theme = "light" | "dark";
type MetricKind = "count" | "rate" | "duration" | "days";

type MetricDefinition = {
  key: string;
  label: string;
  shortLabel: string;
  kind: MetricKind;
  color: string;
  compareLabel: string;
};

const metricCatalog: Record<string, MetricDefinition> = {
  daily_active_users: { key: "daily_active_users", label: "Daily Active Users", shortLabel: "DAU", kind: "count", color: "#2563eb", compareLabel: "vs previous day" },
  weekly_active_learners: { key: "weekly_active_learners", label: "Weekly Active Learners", shortLabel: "WAU", kind: "count", color: "#7c3aed", compareLabel: "vs last 7 days" },
  question_created_count: { key: "question_created_count", label: "Questions Created", shortLabel: "Questions", kind: "count", color: "#2563eb", compareLabel: "vs previous day" },
  answer_submitted_count: { key: "answer_submitted_count", label: "Answers Submitted", shortLabel: "Answers", kind: "count", color: "#16a34a", compareLabel: "vs previous day" },
  answer_rate: { key: "answer_rate", label: "Answer Rate", shortLabel: "Answer Rate", kind: "rate", color: "#9333ea", compareLabel: "pp vs previous day" },
  push_open_rate: { key: "push_open_rate", label: "Push Open Rate", shortLabel: "Push Open", kind: "rate", color: "#f97316", compareLabel: "pp vs previous day" },
  question_to_answer_latency: { key: "question_to_answer_latency", label: "Question to Answer", shortLabel: "Latency", kind: "duration", color: "#0ea5e9", compareLabel: "vs previous day" },
  study_streak: { key: "study_streak", label: "Study Streak", shortLabel: "Streak", kind: "days", color: "#22c55e", compareLabel: "vs previous day" },
  quota_used_count: { key: "quota_used_count", label: "Quota Used", shortLabel: "Quota", kind: "count", color: "#64748b", compareLabel: "this month" },
};

const overviewMetrics = [
  "daily_active_users",
  "weekly_active_learners",
  "question_created_count",
  "answer_submitted_count",
  "answer_rate",
  "push_open_rate",
  "question_to_answer_latency",
  "study_streak",
];

const sections: Array<{ key: SectionKey; label: string; metrics: string[] }> = [
  { key: "overview", label: "Overview", metrics: overviewMetrics },
  { key: "users", label: "Users", metrics: ["daily_active_users", "weekly_active_learners", "study_streak"] },
  { key: "learning", label: "Learning", metrics: ["question_created_count", "answer_submitted_count", "answer_rate", "question_to_answer_latency"] },
  { key: "notifications", label: "Notifications", metrics: ["push_open_rate"] },
  { key: "quota", label: "Quota", metrics: ["quota_used_count"] },
  { key: "operations", label: "Operations", metrics: [] },
];

const today = new Date();
const isoDate = (date: Date) => date.toISOString().slice(0, 10);
const defaultEnd = isoDate(today);
const defaultStart = isoDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6));

export function App() {
  const [token, setToken] = useState(() => getStoredToken());
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem("buddystuddy.adminTheme") as Theme) || "light");
  const [activeSection, setActiveSection] = useState<SectionKey>("overview");
  const [startDate, setStartDate] = useState(defaultStart);
  const [endDate, setEndDate] = useState(defaultEnd);
  const [series, setSeries] = useState<AdminMetricSeries[]>([]);
  const [jobs, setJobs] = useState<ScheduledJobRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const active = sections.find((section) => section.key === activeSection) ?? sections[0];
  const isAuthenticated = Boolean(token);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("buddystuddy.adminTheme", theme);
  }, [theme]);

  useEffect(() => {
    if (!isAuthenticated) return;
    void loadSection();
  }, [isAuthenticated, activeSection, startDate, endDate]);

  const handleUnauthorized = () => {
    setToken(null);
    setError("Session expired");
  };

  async function loadSection() {
    setLoading(true);
    setError(null);
    try {
      if (activeSection === "operations") {
        setJobs(await fetchJobRuns(handleUnauthorized));
        setSeries([]);
      } else {
        const [metrics, runs] = await Promise.all([
          fetchMetrics(startDate, endDate, active.metrics, handleUnauthorized),
          fetchJobRuns(handleUnauthorized).catch(() => [] as ScheduledJobRun[]),
        ]);
        setSeries(metrics.series);
        setJobs(runs);
      }
    } catch (err) {
      if (getStoredToken()) {
        setError(err instanceof Error ? err.message : "Request failed");
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleRefresh() {
    setLoading(true);
    setError(null);
    try {
      if (activeSection === "operations") {
        setJobs(await fetchJobRuns(handleUnauthorized));
      } else {
        const [metrics, runs] = await Promise.all([
          refreshMetrics(startDate, endDate, handleUnauthorized),
          fetchJobRuns(handleUnauthorized).catch(() => jobs),
        ]);
        setSeries(metrics.series.filter((item) => active.metrics.includes(item.metricKey)));
        setJobs(runs);
      }
    } catch (err) {
      if (getStoredToken()) {
        setError(err instanceof Error ? err.message : "Refresh failed");
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleRetry(job: ScheduledJobRun) {
    setError(null);
    try {
      await retryJob(job.jobName, job.id, handleUnauthorized);
      setJobs(await fetchJobRuns(handleUnauthorized));
    } catch (err) {
      if (getStoredToken()) {
        setError(err instanceof Error ? err.message : "Retry failed");
      }
    }
  }

  function handleLogout() {
    clearToken();
    setToken(null);
    setSeries([]);
    setJobs([]);
    setError(null);
  }

  if (!isAuthenticated) {
    return <LoginScreen onLoggedIn={(newToken) => setToken(newToken)} theme={theme} setTheme={setTheme} error={error} />;
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">B</div>
          <div>
            <strong>BuddyStuddy</strong>
            <span>Admin</span>
          </div>
        </div>
        <nav className="nav-list">
          {sections.map((section) => (
            <button
              key={section.key}
              className={section.key === activeSection ? "nav-item active" : "nav-item"}
              onClick={() => setActiveSection(section.key)}
            >
              <span className="nav-icon" aria-hidden="true" />
              {section.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="online-dot" />
          <span>admin</span>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <button className="menu-button" aria-label="Menu">☰</button>
          <div className="toolbar">
            <DateRange startDate={startDate} endDate={endDate} setStartDate={setStartDate} setEndDate={setEndDate} />
            <button className="secondary-button icon-button" onClick={handleRefresh} disabled={loading}>
              ↻ <span>Refresh</span>
            </button>
            <button className="secondary-button icon-button" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
              {theme === "light" ? "☾" : "☀"}
            </button>
            <button className="ghost-button" onClick={handleLogout}>admin</button>
          </div>
        </header>

        {error ? <div className="error-banner">{error}</div> : null}
        {loading ? <div className="loading-bar" /> : null}

        {activeSection === "operations" ? (
          <Operations jobs={jobs} onRetry={handleRetry} />
        ) : (
          <MetricsDashboard series={series} metricKeys={active.metrics} jobs={jobs} />
        )}
      </main>
    </div>
  );
}

function LoginScreen({
  onLoggedIn,
  theme,
  setTheme,
  error,
}: {
  onLoggedIn: (token: string) => void;
  theme: Theme;
  setTheme: (theme: Theme) => void;
  error: string | null;
}) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin");
  const [busy, setBusy] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(error);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setLoginError(null);
    try {
      const response = await login(username, password);
      storeToken(response);
      onLoggedIn(response.adminToken);
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-shell">
      <button className="theme-floating" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
        {theme === "light" ? "Dark" : "Light"}
      </button>
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-mark">B</div>
        <h1>BuddyStuddy Admin</h1>
        <label>
          ID
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
        </label>
        <label>
          Password
          <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" />
        </label>
        {loginError ? <div className="form-error">{loginError}</div> : null}
        <button className="primary-button full" disabled={busy}>
          {busy ? "Signing in" : "Sign in"}
        </button>
      </form>
    </div>
  );
}

function DateRange({
  startDate,
  endDate,
  setStartDate,
  setEndDate,
}: {
  startDate: string;
  endDate: string;
  setStartDate: (value: string) => void;
  setEndDate: (value: string) => void;
}) {
  return (
    <div className="date-range">
      <input aria-label="Start date" type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
      <span>~</span>
      <input aria-label="End date" type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} />
    </div>
  );
}

function MetricsDashboard({ series, metricKeys, jobs }: { series: AdminMetricSeries[]; metricKeys: string[]; jobs: ScheduledJobRun[] }) {
  const seriesByKey = useMemo(() => new Map(series.map((item) => [item.metricKey, item])), [series]);
  const featured = metricKeys.map((key) => seriesByKey.get(key)).filter(Boolean) as AdminMetricSeries[];
  const chartSeries = featured.filter((item) => item.points.length > 0);
  const failedJobs = jobs.filter((job) => job.status === "FAILED").slice(0, 3);

  if (featured.length === 0) {
    return <EmptyState title="No metrics" />;
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
              <h2>Metric trend</h2>
              <span>Daily</span>
            </div>
            <MultiLineChart series={chartSeries} />
          </div>
          <Operations jobs={jobs.slice(0, 8)} onRetry={() => {}} compact />
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
        <span>{definition.label}</span>
        <b title={definition.label}>i</b>
      </div>
      <strong>{formatMetric(definition, last?.value ?? 0)}</strong>
      <small className={delta >= 0 ? "positive" : "negative"}>
        {formatDelta(definition, delta)} <em>{definition.compareLabel}</em>
      </small>
      <Sparkline item={item} definition={definition} />
    </article>
  );
}

function Sparkline({ item, definition }: { item: AdminMetricSeries; definition: MetricDefinition }) {
  const points = item.points;
  const width = 148;
  const height = 34;
  const max = Math.max(1, ...points.map((point) => point.value));
  const min = Math.min(0, ...points.map((point) => point.value));
  const path = points.map((point, index) => {
    const x = points.length <= 1 ? 0 : (index / (points.length - 1)) * width;
    const y = height - ((point.value - min) / Math.max(1, max - min)) * height;
    return `${index === 0 ? "M" : "L"} ${x} ${y}`;
  }).join(" ");
  return (
    <svg className="sparkline" viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <path d={path} stroke={definition.color} />
    </svg>
  );
}

function MultiLineChart({ series }: { series: AdminMetricSeries[] }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const width = 960;
  const height = 380;
  const padding = { top: 18, right: 58, bottom: 40, left: 62 };
  const allDates = series[0]?.points.map((point) => point.date) ?? [];
  const countSeries = series.filter((item) => metricKind(item.metricKey) !== "rate");
  const rateSeries = series.filter((item) => metricKind(item.metricKey) === "rate");
  const countMax = Math.max(1, ...countSeries.flatMap((item) => item.points.map((point) => point.value)));
  const rateMax = Math.max(100, ...rateSeries.flatMap((item) => item.points.map((point) => point.value)));
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const activeIndex = hovered ?? Math.max(0, allDates.length - 1);
  const x = (index: number) => padding.left + (allDates.length <= 1 ? 0 : (index / (allDates.length - 1)) * plotWidth);
  const yCount = (value: number) => padding.top + (1 - value / countMax) * plotHeight;
  const yRate = (value: number) => padding.top + (1 - value / rateMax) * plotHeight;

  const linePath = (item: AdminMetricSeries) => {
    const isRate = metricKind(item.metricKey) === "rate";
    return item.points.map((point, index) => {
      const y = isRate ? yRate(point.value) : yCount(point.value);
      return `${index === 0 ? "M" : "L"} ${x(index)} ${y}`;
    }).join(" ");
  };

  const moveHover = (clientX: number, bounds: DOMRect) => {
    const ratio = Math.max(0, Math.min(1, (clientX - bounds.left - padding.left * (bounds.width / width)) / (plotWidth * (bounds.width / width))));
    setHovered(Math.round(ratio * Math.max(0, allDates.length - 1)));
  };

  if (series.length === 0 || allDates.length === 0) {
    return <EmptyState title="No chart data" compact />;
  }

  return (
    <div className="chart-wrap horizontal-scroll">
      <div className="chart-canvas">
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Metric trend chart">
          {[0, 0.25, 0.5, 0.75, 1].map((tick) => {
            const yy = padding.top + tick * plotHeight;
            const leftValue = countMax * (1 - tick);
            const rightValue = rateMax * (1 - tick);
            return (
              <g key={tick}>
                <line x1={padding.left} x2={width - padding.right} y1={yy} y2={yy} className="grid-line" />
                <text x={padding.left - 12} y={yy + 4} textAnchor="end" className="axis-label">{formatCompact(leftValue)}</text>
                <text x={width - padding.right + 12} y={yy + 4} className="axis-label">{Math.round(rightValue)}%</text>
              </g>
            );
          })}
          <text x={padding.left - 8} y={padding.top - 4} textAnchor="end" className="axis-title">Count</text>
          <text x={width - padding.right + 8} y={padding.top - 4} className="axis-title">Rate</text>
          {series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            return <path key={item.metricKey} d={linePath(item)} className="line-path" stroke={definition.color} />;
          })}
          {series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            const active = item.points[activeIndex];
            if (!active) return null;
            const y = metricKind(item.metricKey) === "rate" ? yRate(active.value) : yCount(active.value);
            return <circle key={item.metricKey} cx={x(activeIndex)} cy={y} r={5} className="chart-dot" stroke={definition.color} />;
          })}
          <line x1={x(activeIndex)} x2={x(activeIndex)} y1={padding.top} y2={height - padding.bottom} className="hover-line" />
          {allDates[0] ? <text x={padding.left} y={height - 10} className="axis-label">{formatShortDate(allDates[0])}</text> : null}
          {allDates.at(-1) ? <text x={width - padding.right} y={height - 10} textAnchor="end" className="axis-label">{formatShortDate(allDates.at(-1)!)}</text> : null}
          <rect
            x={padding.left}
            y={padding.top}
            width={plotWidth}
            height={plotHeight}
            fill="transparent"
            onMouseMove={(event) => moveHover(event.clientX, event.currentTarget.getBoundingClientRect())}
            onMouseLeave={() => setHovered(null)}
          />
        </svg>
        <ChartTooltip index={activeIndex} dates={allDates} series={series} left={(x(activeIndex) / width) * 100} />
      </div>
      <div className="chart-legend">
        {series.map((item) => {
          const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
          return (
            <span key={item.metricKey}>
              <i style={{ background: definition.color }} />
              {definition.shortLabel}
            </span>
          );
        })}
      </div>
    </div>
  );
}

function ChartTooltip({ index, dates, series, left }: { index: number; dates: string[]; series: AdminMetricSeries[]; left: number }) {
  const date = dates[index];
  if (!date) return null;
  return (
    <div className="chart-tooltip" style={{ left: `${left}%` }}>
      <strong>{formatShortDate(date)}</strong>
      {series.map((item) => {
        const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
        const value = item.points[index]?.value ?? 0;
        return (
          <span key={item.metricKey}>
            <i style={{ background: definition.color }} />
            {definition.shortLabel}
            <b>{formatMetric(definition, value)}</b>
          </span>
        );
      })}
    </div>
  );
}

function TodaySummary({ series }: { series: AdminMetricSeries[] }) {
  return (
    <section className="side-panel">
      <h2>Today</h2>
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
      <div className="donut" style={{ background: `conic-gradient(var(--accent) ${ratio}%, var(--surface-2) 0)` }}>
        <div>
          <strong>{ratio}%</strong>
          <span>{formatCompact(latest)} / {formatCompact(limit)}</span>
        </div>
      </div>
    </section>
  );
}

function FailedJobs({ jobs }: { jobs: ScheduledJobRun[] }) {
  return (
    <section className="side-panel">
      <h2>Recent failures</h2>
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

function Operations({ jobs, onRetry, compact = false }: { jobs: ScheduledJobRun[]; onRetry: (job: ScheduledJobRun) => void; compact?: boolean }) {
  if (jobs.length === 0) {
    return <EmptyState title="No job runs" compact={compact} />;
  }
  return (
    <section className={compact ? "operations-panel compact-panel" : "operations-panel"}>
      <div className="panel-header">
        <h2>Scheduler runs</h2>
        <span>{jobs.length} latest</span>
      </div>
      <div className="table-wrap horizontal-scroll">
        <table>
          <thead>
            <tr>
              <th>Job</th>
              <th>Last run</th>
              <th>Status</th>
              <th>Result</th>
              <th>Duration</th>
              <th>Retry</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td>
                  <strong>{job.jobName}</strong>
                  {job.errorMessage ? <small>{job.errorMessage}</small> : null}
                </td>
                <td>{formatDateTime(job.startedAt)}</td>
                <td><span className={`status ${job.status.toLowerCase()}`}>{statusLabel(job.status)}</span></td>
                <td>{job.summary ?? job.status}</td>
                <td>{job.durationMs == null ? "-" : `${job.durationMs}ms`}</td>
                <td>{job.retryOfRunId ? `#${job.retryOfRunId}` : "-"}</td>
                <td><button className="secondary-button compact" onClick={() => onRetry(job)}>Retry</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function EmptyState({ title, compact = false }: { title: string; compact?: boolean }) {
  return (
    <div className={compact ? "empty-state compact-empty" : "empty-state"}>
      <h2>{title}</h2>
    </div>
  );
}

function metricKind(metricKey: string): MetricKind {
  return metricCatalog[metricKey]?.kind ?? "count";
}

function fallbackDefinition(metricKey: string): MetricDefinition {
  return { key: metricKey, label: metricKey, shortLabel: metricKey, kind: "count", color: "#64748b", compareLabel: "vs previous day" };
}

function formatMetric(definition: MetricDefinition, value: number): string {
  if (definition.kind === "rate") return `${roundOne(value)}%`;
  if (definition.kind === "duration") return `${roundOne(value)}h`;
  if (definition.kind === "days") return `${roundOne(value)}d`;
  return formatCompact(value);
}

function formatDelta(definition: MetricDefinition, value: number): string {
  const sign = value > 0 ? "+" : "";
  if (definition.kind === "rate") return `${sign}${roundOne(value)}pp`;
  return `${sign}${formatMetric(definition, value)}`;
}

function roundOne(value: number): number {
  return Math.round(value * 10) / 10;
}

function formatCompact(value: number): string {
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: value < 1000 ? 0 : 1 }).format(value);
}

function statusLabel(status: string): string {
  if (status === "SUCCESS") return "Success";
  if (status === "FAILED") return "Failed";
  if (status === "RUNNING") return "Running";
  return status;
}

function formatShortDate(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "2-digit", day: "2-digit" }).format(new Date(value));
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
