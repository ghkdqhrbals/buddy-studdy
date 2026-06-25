import { useEffect, useState } from "react";
import { clearToken, fetchJobRuns, fetchMetrics, getStoredToken, refreshMetrics, retryJob } from "./api";
import { AdminShell } from "./AdminShell";
import { JOB_PAGE_SIZE, sectionPaths, sections } from "./adminConfig";
import { LoginScreen } from "./LoginScreen";
import { MetricsDashboard } from "./MetricsDashboard";
import { OperationsPanel } from "./OperationsPanel";
import type { AdminMetricSeries, ScheduledJobRun, ScheduledJobRunsResponse, SectionKey, Theme } from "./types";

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

function isIsoDate(value: string | null): value is string {
  return Boolean(value && /^\d{4}-\d{2}-\d{2}$/.test(value));
}

function routeState(): { section: SectionKey; jobOffset: number; startDate: string; endDate: string } {
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

function sectionHref(section: SectionKey, offset = 0, range?: { startDate: string; endDate: string }): string {
  const params = new URLSearchParams();
  if (section !== "operations") {
    if (range) {
      params.set("startDate", range.startDate);
      params.set("endDate", range.endDate);
    }
    const query = params.toString();
    return `${withBasePath(sectionPaths[section])}${query ? `?${query}` : ""}`;
  }
  const page = Math.floor(Math.max(0, offset) / JOB_PAGE_SIZE) + 1;
  return page <= 1 ? withBasePath(sectionPaths.operations) : withBasePath(`${sectionPaths.operations}/page/${page}`);
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
    >
      {activeSection === "operations" ? (
        <OperationsPanel
          page={jobPage}
          onRetry={handleRetry}
          hrefForPage={(nextPage) => sectionHref("operations", (nextPage - 1) * jobPage.limit)}
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
