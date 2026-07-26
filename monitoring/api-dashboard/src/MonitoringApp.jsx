import { AppShell } from "./app/AppShell.jsx";
import { AuditPage } from "./pages/AuditPage.jsx";
import { ApiLogsPage, ApiPerformancePage, TestZonePage } from "./pages/ObservePages.jsx";
import { SettingsPage } from "./pages/SettingsPage.jsx";
import { StreamsPage } from "./pages/StreamsPage.jsx";
import { UsersPage } from "./pages/UsersPage.jsx";

export function MonitoringApp() {
  const route = {
    "/": { Page: ApiLogsPage },
    "/index.html": { Page: ApiLogsPage },
    "/performance.html": { Page: ApiPerformancePage },
    "/testzone.html": { Page: TestZonePage, contentClassName: "react-content-workspace" },
    "/users.html": UsersPage,
    "/streams.html": StreamsPage,
    "/audit.html": AuditPage,
    "/settings.html": SettingsPage,
  }[window.location.pathname];
  const normalizedRoute = typeof route === "function" ? { Page: route } : route;
  const { Page, contentClassName = "" } = normalizedRoute || { Page: ApiLogsPage };
  return (
    <AppShell contentClassName={contentClassName}>
      <Page />
    </AppShell>
  );
}
