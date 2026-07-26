import { useEffect, useState } from "react";
import { clearToken, fetchJobRuns, fetchJobStatuses, fetchMetrics, getStoredToken, refreshMetrics, retryJob } from "./api";
import { AdminShell } from "./AdminShell";
import { JOB_PAGE_SIZE, sectionPaths, sections } from "./adminConfig";
import { LoginScreen } from "./LoginScreen";
import { MetricsDashboard } from "./MetricsDashboard";
import { OperationsPanel } from "./OperationsPanel";
import { EventStreamsPanel } from "./EventStreamsPanel";
import type {
  AdminMetricSeries,
  ScheduledJobRun,
  ScheduledJobRunsResponse,
  ScheduledJobStatusResponse,
  SectionKey,
  Theme,
} from "./types";

const today = new Date();
const isoDate = (date: Date) => date.toISOString().slice(0, 10);
const defaultEnd = isoDate(today);
const defaultStart = isoDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6));
const basePath = normalizeBasePath(import.meta.env.BASE_URL);
const LOGIN_PATH = withBasePath("/login");
const legacySectionPaths: Partial<Record<SectionKey, string[]>> = {
  overview: ["/overview"],
  users: ["/users"],
  learning: ["/learning"],
  notifications: ["/notifications"],
  quota: ["/quota"],
};
const emptyJobPage: ScheduledJobRunsResponse = {
  runs: [],
  totalCount: 0,
  limit: JOB_PAGE_SIZE,
  offset: 0,
};
const emptyJobStatuses: ScheduledJobStatusResponse = {
  jobs: [],
};

function isIsoDate(value: string | null): value is string {
  return Boolean(value && /^\d{4}-\d{2}-\d{2}$/.test(value));
}

function parseRunId(value: string | null): number | null {
  const parsed = Number(value);
  return value && Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function routeState(): { section: SectionKey; jobOffset: number; jobName: string | null; runId: number | null; startDate: string; endDate: string } {
  const path = stripBasePath(window.location.pathname);
  const params = new URLSearchParams(window.location.search);
  const section: SectionKey = path.startsWith(sectionPaths.operations)
    ? "operations"
    : sections.find((item) => path === sectionPaths[item.key] || legacySectionPaths[item.key]?.includes(path))?.key ?? "overview";
  const pathPage = path.match(/^\/operations\/scheduler-runs\/page\/(\d+)$/)?.[1];
  const page = Number(pathPage ?? params.get("page") ?? "1");
  return {
    section,
    jobOffset: section === "operations" ? (Math.max(1, Number.isFinite(page) ? page : 1) - 1) * JOB_PAGE_SIZE : 0,
    jobName: section === "operations" ? params.get("jobName")?.trim() || null : null,
    runId: section === "operations" ? parseRunId(params.get("runId")) : null,
    startDate: isIsoDate(params.get("startDate")) ? params.get("startDate")! : defaultStart,
    endDate: isIsoDate(params.get("endDate")) ? params.get("endDate")! : defaultEnd,
  };
}

function normalizeBasePath(value: string): string {
  const trimmed = value.replace(/\/+$/, "");
  return trimmed === "" ? "" : trimmed;
}

function stripBasePath(path: string): string {
  if (!basePath || path === basePath) return path === basePath ? "/" : path;
  return path.startsWith(`${basePath}/`) ? path.slice(basePath.length) : path;
}

function withBasePath(path: string): string {
  if (!basePath) return path;
  return path === "/" ? `${basePath}/` : `${basePath}${path}`;
}

function currentPathWithSearch(): string {
  return `${window.location.pathname}${window.location.search}`;
}

function loginHref(returnTo: string): string {
  return `${LOGIN_PATH}?returnTo=${encodeURIComponent(returnTo)}`;
}

function safeReturnPath(value: string | null): string | null {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return null;
  if (basePath && value !== basePath && !value.startsWith(`${basePath}/`)) return null;
  if (stripBasePath(value.split("?")[0]) === "/login") return null;
  return value;
}

function sectionHref(
  section: SectionKey,
  offset = 0,
  range?: { startDate: string; endDate: string },
  jobName: string | null = null,
  runId: number | null = null,
): string {
  const params = new URLSearchParams();
  if (section !== "operations") {
    if (range && sectionUsesDateRange(section)) {
      params.set("startDate", range.startDate);
      params.set("endDate", range.endDate);
    }
    const query = params.toString();
    return `${withBasePath(sectionPaths[section])}${query ? `?${query}` : ""}`;
  }
  const page = Math.floor(Math.max(0, offset) / JOB_PAGE_SIZE) + 1;
  if (jobName?.trim()) {
    params.set("jobName", jobName.trim());
  }
  if (runId !== null) {
    params.set("runId", String(runId));
  }
  const query = params.toString();
  const path = page <= 1 ? withBasePath(sectionPaths.operations) : withBasePath(`${sectionPaths.operations}/page/${page}`);
  return `${path}${query ? `?${query}` : ""}`;
}

function sectionUsesDateRange(section: SectionKey): boolean {
  return section !== "operations" && section !== "streams";
}

export function App() {
  const [token, setToken] = useState(() => getStoredToken());
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem("buddystudy.adminTheme") as Theme) || "light");
  const [activeSection, setActiveSection] = useState<SectionKey>(() => routeState().section);
  const [startDate, setStartDate] = useState(() => routeState().startDate);
  const [endDate, setEndDate] = useState(() => routeState().endDate);
  const [series, setSeries] = useState<AdminMetricSeries[]>([]);
  const [jobPage, setJobPage] = useState<ScheduledJobRunsResponse>(emptyJobPage);
  const [jobStatuses, setJobStatuses] = useState<ScheduledJobStatusResponse>(emptyJobStatuses);
  const [jobOffset, setJobOffset] = useState(() => routeState().jobOffset);
  const [jobNameFilter, setJobNameFilter] = useState(() => routeState().jobName);
  const [highlightRunId, setHighlightRunId] = useState(() => routeState().runId);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [streamRefreshKey, setStreamRefreshKey] = useState(0);

  const active = sections.find((section) => section.key === activeSection) ?? sections[0];
  const isAuthenticated = Boolean(token);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("buddystudy.adminTheme", theme);
  }, [theme]);

  useEffect(() => {
    if (window.location.pathname === "/") {
      window.history.replaceState(null, "", sectionHref(activeSection, jobOffset, { startDate, endDate }));
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated) return;
    void loadSection();
  }, [isAuthenticated, activeSection, startDate, endDate, jobOffset, jobNameFilter, highlightRunId]);

  useEffect(() => {
    if (isAuthenticated || window.location.pathname === LOGIN_PATH) return;
    window.history.replaceState(null, "", loginHref(currentPathWithSearch()));
  }, [isAuthenticated]);

  useEffect(() => {
    if (!isAuthenticated || window.location.pathname !== LOGIN_PATH) return;
    const returnTo = safeReturnPath(new URLSearchParams(window.location.search).get("returnTo"));
    window.history.replaceState(null, "", returnTo ?? sectionHref(activeSection, jobOffset, { startDate, endDate }, jobNameFilter, highlightRunId));
    if (returnTo) {
      const next = routeState();
      setActiveSection(next.section);
      setJobOffset(next.jobOffset);
      setJobNameFilter(next.jobName);
      setHighlightRunId(next.runId);
      setStartDate(next.startDate);
      setEndDate(next.endDate);
    }
  }, [isAuthenticated, activeSection, jobOffset, startDate, endDate, highlightRunId]);

  useEffect(() => {
    const handlePopState = () => {
      const next = routeState();
      setActiveSection(next.section);
      setJobOffset(next.jobOffset);
      setJobNameFilter(next.jobName);
      setHighlightRunId(next.runId);
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
        const [runs, statuses] = await Promise.all([
          fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset, jobNameFilter, highlightRunId),
          fetchJobStatuses(handleUnauthorized).catch(() => emptyJobStatuses),
        ]);
        setJobPage(runs);
        setJobStatuses(statuses);
        setSeries([]);
      } else if (activeSection === "streams") {
        setSeries([]);
        setJobPage(emptyJobPage);
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
      if (activeSection === "streams") {
        setStreamRefreshKey((current) => current + 1);
      } else if (activeSection === "operations") {
        const [runs, statuses] = await Promise.all([
          fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset, jobNameFilter, highlightRunId),
          fetchJobStatuses(handleUnauthorized).catch(() => jobStatuses),
        ]);
        setJobPage(runs);
        setJobStatuses(statuses);
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
      const [runs, statuses] = await Promise.all([
        fetchJobRuns(handleUnauthorized, JOB_PAGE_SIZE, jobOffset, jobNameFilter, highlightRunId),
        fetchJobStatuses(handleUnauthorized).catch(() => jobStatuses),
      ]);
      setJobPage(runs);
      setJobStatuses(statuses);
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
    setJobStatuses(emptyJobStatuses);
    setJobOffset(0);
    setHighlightRunId(null);
    setError(null);
    window.history.pushState(null, "", LOGIN_PATH);
  }

  function navigateToSection(section: SectionKey) {
    window.history.pushState(null, "", sectionHref(section, 0, { startDate, endDate }));
    setActiveSection(section);
    setJobOffset(0);
    setJobNameFilter(null);
    setHighlightRunId(null);
  }

  function navigateToJobPage(offset: number) {
    const safeOffset = Math.max(0, offset);
    window.history.pushState(null, "", sectionHref("operations", safeOffset, undefined, jobNameFilter, highlightRunId));
    setActiveSection("operations");
    setJobOffset(safeOffset);
  }

  function updateDateRange(nextStartDate: string, nextEndDate: string) {
    setStartDate(nextStartDate);
    setEndDate(nextEndDate);
    if (sectionUsesDateRange(activeSection)) {
      window.history.replaceState(null, "", sectionHref(activeSection, 0, { startDate: nextStartDate, endDate: nextEndDate }));
    }
  }

  if (!isAuthenticated) {
    return (
      <LoginScreen
        onLoggedIn={(newToken) => {
          setToken(newToken);
          const returnTo = safeReturnPath(new URLSearchParams(window.location.search).get("returnTo"));
          window.history.replaceState(null, "", returnTo ?? sectionHref("overview", 0, { startDate, endDate }));
          const next = routeState();
          setActiveSection(next.section);
          setJobOffset(next.jobOffset);
          setJobNameFilter(next.jobName);
          setHighlightRunId(next.runId);
          setStartDate(next.startDate);
          setEndDate(next.endDate);
        }}
        theme={theme}
        setTheme={setTheme}
        error={error}
      />
    );
  }

  return (
    <AdminShell
      activeSection={activeSection}
      activeLabel={active.label}
      startDate={startDate}
      endDate={endDate}
      theme={theme}
      loading={loading}
      error={error}
      hrefForSection={(section) => sectionHref(section, 0, { startDate, endDate })}
      onDateRangeChange={updateDateRange}
      onLogout={handleLogout}
      onNavigate={navigateToSection}
      onRefresh={handleRefresh}
      onThemeChange={setTheme}
      showDateRange={sectionUsesDateRange(activeSection)}
    >
      {activeSection === "streams" ? (
        <EventStreamsPanel
          onUnauthorized={handleUnauthorized}
          refreshKey={streamRefreshKey}
        />
      ) : activeSection === "operations" ? (
        <OperationsPanel
          page={jobPage}
          statuses={jobStatuses.jobs}
          highlightRunId={highlightRunId}
          onRetry={handleRetry}
          hrefForPage={(nextPage) => sectionHref("operations", (nextPage - 1) * jobPage.limit, undefined, jobNameFilter, highlightRunId)}
          onPageChange={(nextPage) => navigateToJobPage((nextPage - 1) * jobPage.limit)}
        />
      ) : (
        <MetricsDashboard
          series={series}
          metricKeys={active.metrics}
          jobs={jobPage.runs}
          operationsHrefForPage={(nextPage) => sectionHref("operations", (nextPage - 1) * JOB_PAGE_SIZE)}
        />
      )}
    </AdminShell>
  );
}
