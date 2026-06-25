import { FormEvent, useEffect, useMemo, useState } from "react";
import { clearToken, fetchJobRuns, fetchMetrics, getStoredToken, login, refreshMetrics, retryJob, storeToken } from "./api";
import { JOB_PAGE_SIZE, metricCatalog, overviewTrendMetrics, sectionPaths, sections, type MetricDefinition } from "./adminConfig";
import { clamp, fallbackDefinition, formatCompact, formatDateTime, formatDelta, formatMetric, formatShortDate, statusLabel } from "./format";
import { Pagination } from "./Pagination";
import type { AdminDailyMetricPoint, AdminMetricSeries, ScheduledJobRun, ScheduledJobRunsResponse, SectionKey, Theme } from "./types";

const today = new Date();
const isoDate = (date: Date) => date.toISOString().slice(0, 10);
const defaultEnd = isoDate(today);
const defaultStart = isoDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6));
const LOGIN_PATH = "/login";
const emptyJobPage: ScheduledJobRunsResponse = {
  runs: [],
  totalCount: 0,
  limit: JOB_PAGE_SIZE,
  offset: 0,
};

function isIsoDate(value: string | null): value is string {
  return Boolean(value && /^\d{4}-\d{2}-\d{2}$/.test(value));
}

function routeState(): { section: SectionKey; jobOffset: number; startDate: string; endDate: string } {
  const path = window.location.pathname;
  const params = new URLSearchParams(window.location.search);
  const section: SectionKey = path.startsWith("/operations")
    ? "operations"
    : path === "/overview"
      ? "overview"
      : sections.find((item) => path === sectionPaths[item.key])?.key ?? "overview";
  const page = Number(params.get("page") ?? "1");
  return {
    section,
    jobOffset: section === "operations" ? (Math.max(1, Number.isFinite(page) ? page : 1) - 1) * JOB_PAGE_SIZE : 0,
    startDate: isIsoDate(params.get("startDate")) ? params.get("startDate")! : defaultStart,
    endDate: isIsoDate(params.get("endDate")) ? params.get("endDate")! : defaultEnd,
  };
}

function sectionHref(section: SectionKey, offset = 0, range?: { startDate: string; endDate: string }): string {
  const params = new URLSearchParams();
  if (section !== "operations") {
    if (range) {
      params.set("startDate", range.startDate);
      params.set("endDate", range.endDate);
    }
    const query = params.toString();
    return `${sectionPaths[section]}${query ? `?${query}` : ""}`;
  }
  params.set("page", String(Math.floor(Math.max(0, offset) / JOB_PAGE_SIZE) + 1));
  return `${sectionPaths.operations}?${params}`;
}

export function App() {
  const [token, setToken] = useState(() => getStoredToken());
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem("buddystuddy.adminTheme") as Theme) || "light");
  const [activeSection, setActiveSection] = useState<SectionKey>(() => routeState().section);
  const [startDate, setStartDate] = useState(() => routeState().startDate);
  const [endDate, setEndDate] = useState(() => routeState().endDate);
  const [series, setSeries] = useState<AdminMetricSeries[]>([]);
  const [jobPage, setJobPage] = useState<ScheduledJobRunsResponse>(emptyJobPage);
  const [jobOffset, setJobOffset] = useState(() => routeState().jobOffset);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const active = sections.find((section) => section.key === activeSection) ?? sections[0];
  const isAuthenticated = Boolean(token);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("buddystuddy.adminTheme", theme);
  }, [theme]);

  useEffect(() => {
    if (window.location.pathname === "/") {
      window.history.replaceState(null, "", sectionHref(activeSection, jobOffset, { startDate, endDate }));
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated) return;
    void loadSection();
  }, [isAuthenticated, activeSection, startDate, endDate, jobOffset]);

  useEffect(() => {
    if (isAuthenticated || window.location.pathname === LOGIN_PATH) return;
    window.history.replaceState(null, "", LOGIN_PATH);
  }, [isAuthenticated]);

  useEffect(() => {
    if (!isAuthenticated || window.location.pathname !== LOGIN_PATH) return;
    window.history.replaceState(null, "", sectionHref(activeSection, jobOffset, { startDate, endDate }));
  }, [isAuthenticated, activeSection, jobOffset, startDate, endDate]);

  useEffect(() => {
    const handlePopState = () => {
      const next = routeState();
      setActiveSection(next.section);
      setJobOffset(next.jobOffset);
      setStartDate(next.startDate);
      setEndDate(next.endDate);
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const handleUnauthorized = () => {
    setToken(null);
    setError("Session expired");
    window.history.replaceState(null, "", LOGIN_PATH);
  };

  async function loadSection() {
    setLoading(true);
    setError(null);
    try {
      if (activeSection === "operations") {
        setJobPage(await fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset));
        setSeries([]);
      } else {
        const [metrics, runs] = await Promise.all([
          fetchMetrics(startDate, endDate, active.metrics, handleUnauthorized),
          fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, 0).catch(() => emptyJobPage),
        ]);
        setSeries(metrics.series);
        setJobPage(runs);
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
        setJobPage(await fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset));
      } else {
        const [metrics, runs] = await Promise.all([
          refreshMetrics(startDate, endDate, handleUnauthorized),
          fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, 0).catch(() => jobPage),
        ]);
        setSeries(metrics.series.filter((item) => active.metrics.includes(item.metricKey)));
        setJobPage(runs);
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
      setJobPage(await fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset));
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
    setJobPage(emptyJobPage);
    setJobOffset(0);
    setError(null);
    window.history.pushState(null, "", LOGIN_PATH);
  }

  function navigateToSection(section: SectionKey) {
    window.history.pushState(null, "", sectionHref(section, 0, { startDate, endDate }));
    setActiveSection(section);
    setJobOffset(0);
  }

  function navigateToJobPage(offset: number) {
    const safeOffset = Math.max(0, offset);
    window.history.pushState(null, "", sectionHref("operations", safeOffset));
    setActiveSection("operations");
    setJobOffset(safeOffset);
  }

  function updateDateRange(nextStartDate: string, nextEndDate: string) {
    setStartDate(nextStartDate);
    setEndDate(nextEndDate);
    if (activeSection !== "operations") {
      window.history.replaceState(null, "", sectionHref(activeSection, 0, { startDate: nextStartDate, endDate: nextEndDate }));
    }
  }

  if (!isAuthenticated) {
    return (
      <LoginScreen
        onLoggedIn={(newToken) => {
          setToken(newToken);
          window.history.replaceState(null, "", sectionHref("overview", 0, { startDate, endDate }));
          setActiveSection("overview");
          setJobOffset(0);
        }}
        theme={theme}
        setTheme={setTheme}
        error={error}
      />
    );
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
            <a
              key={section.key}
              href={sectionHref(section.key, 0, { startDate, endDate })}
              className={section.key === activeSection ? "nav-item active" : "nav-item"}
              onClick={(event) => {
                event.preventDefault();
                navigateToSection(section.key);
              }}
            >
              {section.label}
            </a>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="admin-chip">
            <span className="admin-avatar">A</span>
            <span>
              <strong>admin</strong>
              <small>Administrator</small>
            </span>
          </div>
          <button className="logout-button" onClick={handleLogout}>Sign out</button>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div className="topbar-title">
            <h1>{active.label}</h1>
          </div>
          <div className="toolbar">
            {activeSection === "operations" ? null : (
              <DateRange
                startDate={startDate}
                endDate={endDate}
                setStartDate={(value) => updateDateRange(value, endDate)}
                setEndDate={(value) => updateDateRange(startDate, value)}
              />
            )}
            <button className="secondary-button icon-button square-button" aria-label="Refresh" title="Refresh" onClick={handleRefresh} disabled={loading}>
              <Icon name="refresh" />
            </button>
            <button className="secondary-button icon-button square-button" aria-label="Toggle theme" title="Toggle theme" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
              <Icon name={theme === "light" ? "moon" : "sun"} />
            </button>
          </div>
        </header>

        {error ? <div className="error-banner">{error}</div> : null}
        {loading ? <div className="loading-bar" /> : null}

        {activeSection === "operations" ? (
          <Operations page={jobPage} onRetry={handleRetry} onPageChange={navigateToJobPage} />
        ) : (
          <MetricsDashboard series={series} metricKeys={active.metrics} jobs={jobPage.runs} />
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
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-head">
          <div className="login-mark">B</div>
          <button type="button" className="secondary-button square-button" aria-label="Toggle theme" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
            <Icon name={theme === "light" ? "moon" : "sun"} />
          </button>
        </div>
        <h1>Admin console</h1>
        <p>BuddyStuddy operations</p>
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
      <input
        aria-label="Start date"
        type="date"
        value={startDate}
        onInput={(event) => setStartDate(event.currentTarget.value)}
        onChange={(event) => setStartDate(event.target.value)}
      />
      <span>~</span>
      <input
        aria-label="End date"
        type="date"
        value={endDate}
        onInput={(event) => setEndDate(event.currentTarget.value)}
        onChange={(event) => setEndDate(event.target.value)}
      />
    </div>
  );
}

function MetricsDashboard({ series, metricKeys, jobs }: { series: AdminMetricSeries[]; metricKeys: string[]; jobs: ScheduledJobRun[] }) {
  const seriesByKey = useMemo(() => new Map(series.map((item) => [item.metricKey, item])), [series]);
  const featured = metricKeys.map((key) => seriesByKey.get(key)).filter(Boolean) as AdminMetricSeries[];
  const trendKeys = metricKeys.length > 4 ? overviewTrendMetrics : metricKeys;
  const chartSeries = trendKeys.map((key) => seriesByKey.get(key)).filter((item): item is AdminMetricSeries => Boolean(item && item.points.length > 0));
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
              <h2>Trend</h2>
              <span>Normalized per metric</span>
            </div>
            <MultiLineChart series={chartSeries} />
          </div>
          <Operations page={{ runs: jobs.slice(0, 5), totalCount: jobs.length, limit: 5, offset: 0 }} onRetry={() => {}} compact />
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
  const width = 720;
  const height = 260;
  const padding = { top: 12, right: 18, bottom: 34, left: 42 };
  const allDates = series[0]?.points.map((point) => point.date) ?? [];
  const seriesMax = useMemo(
    () => new Map(series.map((item) => [item.metricKey, Math.max(1, ...item.points.map((point) => point.value))])),
    [series],
  );
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const activeIndex = hovered ?? Math.max(0, allDates.length - 1);
  const x = (index: number) => padding.left + (allDates.length <= 1 ? 0 : (index / (allDates.length - 1)) * plotWidth);
  const y = (metricKey: string, value: number) => {
    const max = seriesMax.get(metricKey) ?? 1;
    return padding.top + (1 - value / max) * plotHeight;
  };

  const linePath = (item: AdminMetricSeries) => {
    return item.points.map((point, index) => {
      return `${index === 0 ? "M" : "L"} ${x(index)} ${y(item.metricKey, point.value)}`;
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
        <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label="Metric trend chart">
          {[0, 0.25, 0.5, 0.75, 1].map((tick) => {
            const yy = padding.top + tick * plotHeight;
            const value = Math.round((1 - tick) * 100);
            return (
              <g key={tick}>
                <line x1={padding.left} x2={width - padding.right} y1={yy} y2={yy} className="grid-line" />
                <text x={padding.left - 10} y={yy + 4} textAnchor="end" className="axis-label">{value}%</text>
              </g>
            );
          })}
          {series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            return <path key={item.metricKey} d={linePath(item)} className="line-path" stroke={definition.color} />;
          })}
          {hovered !== null ? series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            const active = item.points[activeIndex];
            if (!active) return null;
            return <circle key={item.metricKey} cx={x(activeIndex)} cy={y(item.metricKey, active.value)} r={4} className="chart-dot" stroke={definition.color} />;
          }) : null}
          {hovered !== null ? <line x1={x(activeIndex)} x2={x(activeIndex)} y1={padding.top} y2={height - padding.bottom} className="hover-line" /> : null}
          {xTicks(allDates).map((tick) => (
            <text key={`${tick.index}-${tick.date}`} x={x(tick.index)} y={height - 12} textAnchor={tick.anchor} className="axis-label">
              {formatShortDate(tick.date)}
            </text>
          ))}
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
        {hovered !== null ? <ChartTooltip index={activeIndex} dates={allDates} series={series} left={clamp((x(activeIndex) / width) * 100, 14, 86)} /> : null}
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
        const max = Math.max(1, ...item.points.map((point) => point.value));
        const normalized = Math.round((value / max) * 100);
        return (
          <span key={item.metricKey}>
            <i style={{ background: definition.color }} />
            <small>{definition.shortLabel}</small>
            <b>{formatMetric(definition, value)}</b>
            <em>{normalized}%</em>
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

function Operations({
  page,
  onRetry,
  onPageChange,
  compact = false,
}: {
  page: ScheduledJobRunsResponse;
  onRetry: (job: ScheduledJobRun) => void;
  onPageChange?: (offset: number) => void;
  compact?: boolean;
}) {
  const jobs = page.runs;
  if (jobs.length === 0) {
    return <EmptyState title="No job runs" compact={compact} />;
  }
  const start = page.offset + 1;
  const end = Math.min(page.offset + jobs.length, page.totalCount);
  const currentPage = Math.floor(page.offset / page.limit) + 1;
  const totalPages = Math.max(1, Math.ceil(page.totalCount / page.limit));
  return (
    <section className={compact ? "operations-panel compact-panel" : "operations-panel"}>
      <div className="panel-header">
        <h2>Scheduler runs</h2>
        {compact ? (
          <a className="panel-link" href={sectionHref("operations", 0)}>View all</a>
        ) : (
          <span>{start}-{end} of {page.totalCount}</span>
        )}
      </div>
      <div className="table-wrap horizontal-scroll">
        <table>
          <thead>
            <tr>
              <th className="job-col">Job</th>
              <th className="time-col">Last run</th>
              <th className="status-col">Status</th>
              <th className="result-col">Result</th>
              <th className="duration-col">Duration</th>
              <th className="retry-col">Retry</th>
              <th className="action-col"></th>
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
                <td className="result-cell" title={job.summary ?? job.status}>{job.summary ?? job.status}</td>
                <td>{job.durationMs == null ? "-" : `${job.durationMs}ms`}</td>
                <td>{job.retryOfRunId ? `#${job.retryOfRunId}` : "-"}</td>
                <td className="action-cell"><button className="secondary-button compact" onClick={() => onRetry(job)}>Retry</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {onPageChange ? (
        <Pagination
          start={start}
          end={end}
          totalCount={page.totalCount}
          currentPage={currentPage}
          totalPages={totalPages}
          hrefForPage={(nextPage) => sectionHref("operations", (nextPage - 1) * page.limit)}
          onPageChange={(nextPage) => onPageChange((nextPage - 1) * page.limit)}
        />
      ) : null}
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

function Icon({ name }: { name: "refresh" | "moon" | "sun" }) {
  if (name === "refresh") {
    return (
      <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M15.4 6.2A6 6 0 1 0 16 10" />
        <path d="M15.5 3.8v3h-3" />
      </svg>
    );
  }
  if (name === "moon") {
    return (
      <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M14.7 13.7A6.7 6.7 0 0 1 6.3 5.3 6.9 6.9 0 1 0 14.7 13.7Z" />
      </svg>
    );
  }
  if (name === "sun") {
    return (
      <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="10" cy="10" r="3.3" />
        <path d="M10 1.8v2M10 16.2v2M1.8 10h2M16.2 10h2M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M15.8 4.2l-1.4 1.4M5.6 14.4l-1.4 1.4" />
      </svg>
    );
  }
}

function xTicks(dates: string[]) {
  if (dates.length === 0) return [];
  if (dates.length === 1) return [{ index: 0, date: dates[0], anchor: "middle" as const }];
  const middle = Math.floor((dates.length - 1) / 2);
  return [
    { index: 0, date: dates[0], anchor: "start" as const },
    { index: middle, date: dates[middle], anchor: "middle" as const },
    { index: dates.length - 1, date: dates[dates.length - 1], anchor: "end" as const },
  ];
}
