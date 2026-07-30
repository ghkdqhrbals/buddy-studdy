import { AppShell } from "./app/AppShell.jsx";
import { AuditPage } from "./pages/AuditPage.jsx";
import { ApiLogsPage, ApiPerformancePage, TestZonePage } from "./pages/ObservePages.jsx";
import { SettingsPage } from "./pages/SettingsPage.jsx";
import { ServiceStatusPage } from "./pages/ServiceStatusPage.jsx";
import { StreamsPage } from "./pages/StreamsPage.jsx";
import { JobsPage } from "./pages/JobsPage.jsx";
import { UsersPage } from "./pages/UsersPage.jsx";
import { FeedbackPage } from "./pages/FeedbackPage.jsx";

export function MonitoringApp() {
  const route = {
    "/": { Page: ApiLogsPage },
    "/index.html": { Page: ApiLogsPage },
    "/performance.html": { Page: ApiPerformancePage },
    "/testzone.html": { Page: TestZonePage, contentClassName: "react-content-workspace" },
    "/users.html": UsersPage,
    "/feedback.html": FeedbackPage,
    "/jobs.html": JobsPage,
    "/streams.html": StreamsPage,
    "/audit.html": AuditPage,
    "/settings.html": SettingsPage,
    "/service-status.html": ServiceStatusPage,
  }[window.location.pathname];
  const normalizedRoute = typeof route === "function" ? { Page: route } : route;
  const { Page, contentClassName = "" } = normalizedRoute || { Page: ApiLogsPage };
  return (
    <AppShell contentClassName={contentClassName}>
      <Page />
    </AppShell>
  );
}
