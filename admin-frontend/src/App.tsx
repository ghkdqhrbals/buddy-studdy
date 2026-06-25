import { FormEvent, useEffect, useMemo, useState } from "react";
import { clearToken, fetchJobRuns, fetchMetrics, getStoredToken, getTokenExpiry, login, refreshMetrics, retryJob, storeToken } from "./api";
import type { AdminMetricSeries, ScheduledJobRun } from "./types";

type SectionKey = "overview" | "users" | "learning" | "notifications" | "quota" | "operations";
type Theme = "light" | "dark";

const sections: Array<{ key: SectionKey; label: string; metrics: string[] }> = [
  { key: "overview", label: "Overview", metrics: ["daily_active_users", "weekly_active_learners", "question_created_count", "answer_submitted_count"] },
  { key: "users", label: "Users", metrics: ["daily_active_users", "weekly_active_learners", "study_streak"] },
  { key: "learning", label: "Learning", metrics: ["question_created_count", "answer_submitted_count", "answer_rate", "question_to_answer_latency"] },
  { key: "notifications", label: "Notifications", metrics: ["push_open_rate"] },
  { key: "quota", label: "Quota", metrics: ["quota_used_count"] },
  { key: "operations", label: "Operations", metrics: [] },
];

const metricLabels: Record<string, string> = {
  daily_active_users: "Daily active users",
  weekly_active_learners: "Weekly active learners",
  question_created_count: "Questions created",
  answer_submitted_count: "Answers submitted",
  answer_rate: "Average answer rate",
  push_open_rate: "Average push open rate",
  question_to_answer_latency: "Average answer latency",
  study_streak: "Study streak",
  quota_used_count: "Quota used",
};

const today = new Date();
const isoDate = (date: Date) => date.toISOString().slice(0, 10);
const defaultEnd = isoDate(today);
const defaultStart = isoDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 13));

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
        const data = await fetchMetrics(startDate, endDate, active.metrics, handleUnauthorized);
        setSeries(data.series);
        setJobs([]);
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
      const data = await refreshMetrics(startDate, endDate, handleUnauthorized);
      if (activeSection === "operations") {
        setJobs(await fetchJobRuns(handleUnauthorized));
      } else {
        setSeries(data.series.filter((item) => active.metrics.includes(item.metricKey)));
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
              {section.label}
            </button>
          ))}
        </nav>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <h1>{active.label}</h1>
          </div>
          <div className="toolbar">
            <DateInput label="From" value={startDate} onChange={setStartDate} />
            <DateInput label="To" value={endDate} onChange={setEndDate} />
            <button className="secondary-button" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
              {theme === "light" ? "Dark" : "Light"}
            </button>
            <button className="primary-button" onClick={handleRefresh} disabled={loading || activeSection === "operations"}>
              Refresh
            </button>
            <button className="ghost-button" onClick={handleLogout}>Logout</button>
          </div>
        </header>

        {error ? <div className="error-banner">{error}</div> : null}
        {loading ? <div className="loading-bar" /> : null}

        {activeSection === "operations" ? (
          <Operations jobs={jobs} onRetry={handleRetry} />
        ) : (
          <MetricsDashboard series={series} metricKeys={active.metrics} />
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

function DateInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="date-input">
      <span>{label}</span>
      <input type="date" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  );
}

function MetricsDashboard({ series, metricKeys }: { series: AdminMetricSeries[]; metricKeys: string[] }) {
  const seriesByKey = useMemo(() => new Map(series.map((item) => [item.metricKey, item])), [series]);
  const featured = metricKeys.map((key) => seriesByKey.get(key)).filter(Boolean) as AdminMetricSeries[];
  const selected = featured[0];

  if (featured.length === 0) {
    return <EmptyState title="No metrics" />;
  }

  return (
    <section className="metrics-grid">
      <div className="metric-cards">
        {featured.map((item) => (
          <MetricCard key={item.metricKey} item={item} />
        ))}
      </div>
      <div className="chart-panel">
        <div className="panel-header">
          <h2>{selected ? metricLabels[selected.metricKey] : "Trend"}</h2>
          <span>{selected?.points.length ?? 0} days</span>
        </div>
        {selected ? <LineChart series={selected} /> : null}
      </div>
      <div className="series-list">
        {featured.slice(1).map((item) => (
          <MiniSeries key={item.metricKey} item={item} />
        ))}
      </div>
    </section>
  );
}

function MetricCard({ item }: { item: AdminMetricSeries }) {
  const last = item.points.at(-1);
  const previous = item.points.at(-2);
  const delta = last && previous ? last.value - previous.value : 0;
  return (
    <article className="metric-card">
      <span>{metricLabels[item.metricKey] ?? item.metricKey}</span>
      <strong>{formatMetric(item.metricKey, last?.value ?? 0)}</strong>
      <small className={delta >= 0 ? "positive" : "negative"}>{delta === 0 ? "0" : `${delta > 0 ? "+" : ""}${formatMetric(item.metricKey, delta)}`}</small>
    </article>
  );
}

function LineChart({ series }: { series: AdminMetricSeries }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const width = 760;
  const height = 310;
  const padding = { top: 18, right: 24, bottom: 34, left: 52 };
  const points = series.points;
  const max = Math.max(1, ...points.map((point) => point.value));
  const min = Math.min(0, ...points.map((point) => point.value));
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const x = (index: number) => padding.left + (points.length <= 1 ? 0 : (index / (points.length - 1)) * plotWidth);
  const y = (value: number) => padding.top + ((max - value) / Math.max(1, max - min)) * plotHeight;
  const path = points.map((point, index) => `${index === 0 ? "M" : "L"} ${x(index)} ${y(point.value)}`).join(" ");
  const activeIndex = hovered ?? points.length - 1;
  const active = points[activeIndex];

  return (
    <div className="chart-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${metricLabels[series.metricKey]} chart`}>
        {[0, 0.25, 0.5, 0.75, 1].map((tick) => {
          const value = min + (max - min) * (1 - tick);
          const yy = padding.top + tick * plotHeight;
          return (
            <g key={tick}>
              <line x1={padding.left} x2={width - padding.right} y1={yy} y2={yy} className="grid-line" />
              <text x={padding.left - 12} y={yy + 4} textAnchor="end" className="axis-label">{formatMetric(series.metricKey, value)}</text>
            </g>
          );
        })}
        <path d={path} className="line-path" />
        {points.map((point, index) => (
          <circle
            key={`${point.date}-${index}`}
            cx={x(index)}
            cy={y(point.value)}
            r={index === activeIndex ? 5 : 3}
            className="chart-dot"
            onMouseEnter={() => setHovered(index)}
            onFocus={() => setHovered(index)}
            tabIndex={0}
          />
        ))}
        {points[0] ? <text x={padding.left} y={height - 8} className="axis-label">{formatShortDate(points[0].date)}</text> : null}
        {points.at(-1) ? <text x={width - padding.right} y={height - 8} textAnchor="end" className="axis-label">{formatShortDate(points.at(-1)!.date)}</text> : null}
      </svg>
      {active ? (
        <div className="chart-tooltip" style={{ left: `${(x(activeIndex) / width) * 100}%`, top: `${(y(active.value) / height) * 100}%` }}>
          <strong>{formatMetric(series.metricKey, active.value)}</strong>
          <span>{formatShortDate(active.date)}</span>
          {active.sampleCount ? <span>{active.sampleCount} samples</span> : null}
        </div>
      ) : null}
    </div>
  );
}

function MiniSeries({ item }: { item: AdminMetricSeries }) {
  const latest = item.points.at(-1);
  return (
    <div className="mini-series">
      <div>
        <strong>{metricLabels[item.metricKey] ?? item.metricKey}</strong>
        <span>{latest ? formatShortDate(latest.date) : "-"}</span>
      </div>
      <b>{formatMetric(item.metricKey, latest?.value ?? 0)}</b>
    </div>
  );
}

function Operations({ jobs, onRetry }: { jobs: ScheduledJobRun[]; onRetry: (job: ScheduledJobRun) => void }) {
  if (jobs.length === 0) {
    return <EmptyState title="No job runs" />;
  }
  return (
    <section className="operations-panel">
      <div className="panel-header">
        <h2>Job runs</h2>
        <span>{jobs.length} latest</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Job</th>
              <th>Status</th>
              <th>Trigger</th>
              <th>Started</th>
              <th>Duration</th>
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
                <td><span className={`status ${job.status.toLowerCase()}`}>{job.status}</span></td>
                <td>{job.triggerType}</td>
                <td>{formatDateTime(job.startedAt)}</td>
                <td>{job.durationMs == null ? "-" : `${job.durationMs}ms`}</td>
                <td><button className="secondary-button compact" onClick={() => onRetry(job)}>Retry</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function EmptyState({ title }: { title: string }) {
  return (
    <div className="empty-state">
      <h2>{title}</h2>
    </div>
  );
}

function formatMetric(metricKey: string, value: number): string {
  if (metricKey.includes("rate")) return `${Math.round(value * 100) / 100}%`;
  if (metricKey.includes("latency")) return `${Math.round(value)}s`;
  return new Intl.NumberFormat("en", { maximumFractionDigits: value < 10 ? 1 : 0 }).format(value);
}

function formatShortDate(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric" }).format(new Date(value));
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
