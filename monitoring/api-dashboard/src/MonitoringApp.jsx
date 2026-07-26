import { AppShell } from "./app/AppShell.jsx";
import { AuditPage } from "./pages/AuditPage.jsx";
import { SettingsPage } from "./pages/SettingsPage.jsx";
import { StreamsPage } from "./pages/StreamsPage.jsx";
import { UsersPage } from "./pages/UsersPage.jsx";

export function MonitoringApp() {
  const Page = {
    "/users.html": UsersPage,
    "/streams.html": StreamsPage,
    "/audit.html": AuditPage,
    "/settings.html": SettingsPage,
  }[window.location.pathname] || UsersPage;
  return (
    <AppShell>
      <Page />
    </AppShell>
  );
}
