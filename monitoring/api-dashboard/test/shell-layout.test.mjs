import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const publicRoot = new URL("../public/", import.meta.url);
const sourceRoot = new URL("../src/", import.meta.url);

async function text(file) {
  return readFile(new URL(file, publicRoot), "utf8");
}

async function source(file) {
  return readFile(new URL(file, sourceRoot), "utf8");
}

test("all monitoring pages load the shared React application", async () => {
  for (const page of [
    "index.html",
    "performance.html",
    "testzone.html",
    "audit.html",
    "settings.html",
    "users.html",
    "administrators.html",
    "feedback.html",
    "advertising.html",
    "jobs.html",
    "orders.html",
    "streams.html",
    "deployments.html",
    "service-status.html",
    "external-api-history.html",
    "login.html",
  ]) {
    const html = await text(page);
    assert.match(html, /id="monitoring-react-root"/);
    assert.match(html, /src="\/react\/manage\.js\?/);
    assert.match(html, /href="\/react\/manage\.css\?/);
    assert.match(html, /src="\/nav-bootstrap\.js\?/);
    assert.doesNotMatch(html, /src="\/shell\.js\?/);
  }
  const app = await source("MonitoringApp.jsx");
  assert.match(app, /ApiLogsPage/);
  assert.match(app, /ApiPerformancePage/);
  assert.match(app, /TestZonePage/);
  const navigation = await source("app/navigation.js");
  assert.match(navigation, /Access & Audit/);
  assert.match(navigation, /Users & Quotas/);
  assert.match(navigation, /Administrators/);
  assert.match(navigation, /User Feedback/);
  assert.match(navigation, /Advertising/);
  assert.match(navigation, /Batch Jobs/);
  assert.match(navigation, /Orders & Billing/);
  assert.match(navigation, /Redis Streams/);
  assert.match(navigation, /Deployments/);
  assert.match(navigation, /GitPullRequest/);
  assert.doesNotMatch(navigation, /Layers3/);
  assert.match(navigation, /Service Status/);
  assert.match(navigation, /External APIs/);
});

test("advertising administration manages Coupang campaigns and explains server ranking", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/AdvertisingPage.jsx");
  assert.match(app, /advertising\.html/);
  assert.match(page, /\/native-ad-campaigns\?/);
  assert.match(page, /\/native-ad-campaigns\/\$\{campaign\.id\}\/users\?/);
  assert.match(page, /method:\s*campaign \? "PUT" : "POST"/);
  assert.match(page, /Coupang destination URL/);
  assert.match(page, /link\.coupang\.com/);
  assert.match(page, /Advertising campaigns/);
  assert.match(page, /How the server ranks advertisements/);
  assert.match(page, /smoothed open rate/);
  assert.match(page, /DataTable/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /Pagination/);
});

test("external API history is cursor paginated and loads full request and response on demand", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/ExternalApiHistoryPage.jsx");
  assert.match(app, /external-api-history\.html/);
  assert.match(page, /\/external-api-history\?/);
  assert.match(page, /\/external-api-history\/\$\{selectedId\}/);
  assert.match(page, /cursorStack/);
  assert.match(page, /Pagination/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /ObjectInspector/);
  assert.match(page, /Authentication headers and secret fields are redacted/);
});

test("order administration exposes the invoice ledger and audited Apple actions", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/OrdersPage.jsx");
  const css = await source("styles/manage.css");
  assert.match(app, /orders\.html/);
  assert.match(page, /\/billing\/invoices\?/);
  assert.match(page, /Invoice event ledger/);
  assert.match(page, /Payment history/);
  assert.match(page, /refund-requests/);
  assert.match(page, /cancellation-requests/);
  assert.match(page, /Apple confirms the final cancellation or refund/);
  assert.match(page, /crypto\.randomUUID/);
  assert.match(page, /Pagination/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /\/billing\/processing-failures\?/);
  assert.match(page, /Billing processing failures/);
  assert.match(page, /REVENUECAT_EVENT/);
  assert.match(page, /SUBSCRIPTION_RECONCILIATION/);
  assert.match(page, /EXHAUSTED/);
  assert.match(css, /\.drawer-content\s*\{[\s\S]*min-height:\s*0;[\s\S]*flex:\s*1 1 auto;[\s\S]*grid-auto-rows:\s*max-content/);
  assert.match(css, /\.detail-drawer > header\s*\{[\s\S]*flex:\s*0 0 auto/);
});

test("deployment administration shows auto-refreshed workflow history and rollout details", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/DeploymentsPage.jsx");
  const api = await source("lib/deploymentApi.js");
  assert.match(app, /deployments\.html/);
  assert.match(page, /refetchInterval:\s*10_000/);
  assert.match(page, /\/deployments\?/);
  assert.match(page, /summary\.current/);
  assert.match(page, /placeholderData:\s*keepPreviousData/);
  assert.doesNotMatch(page, /recent-summary/);
  assert.doesNotMatch(page, /limit=100/);
  assert.doesNotMatch(page, /window\.location\.reload/);
  assert.doesNotMatch(page, /isLoading\s*\|\|\s*deploymentsQuery\.isFetching/);
  assert.match(page, /Deployment history/);
  assert.match(page, /Open GitHub Actions/);
  assert.match(page, /Pagination/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /ObjectInspector/);
  assert.doesNotMatch(page, /AdminGate/);
  assert.match(api, /\/testzone\/api/);
});

test("data tables preserve rendered rows while background refreshes are running", async () => {
  const table = await source("components/AdminUI.jsx");
  assert.match(table, /const initialLoading = loading && rows\.length === 0/);
  assert.match(table, /\{rows\.map\(\(row\) =>/);
  assert.doesNotMatch(table, /!loading && rows\.map/);
});

test("feedback administration reviews submissions and sends deep-linked user notifications", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/FeedbackPage.jsx");
  const users = await source("pages/UsersPage.jsx");
  const composer = await source("components/AdminNotificationComposer.jsx");
  assert.match(app, /feedback\.html/);
  assert.match(page, /\/feedback\?/);
  assert.match(page, /\/feedback\/\$\{selected\.id\}\/review/);
  assert.match(page, /\/feedback\/\$\{selected\.id\}\/notifications/);
  assert.match(page, /Pagination/);
  assert.match(page, /NEW/);
  assert.match(page, /REVIEWED/);
  assert.match(page, /REPLIED/);
  assert.match(users, /AdminNotificationComposer/);
  assert.match(composer, /buddystudy:\/\/home\/message/);
  assert.match(composer, /buddystudy:\/\/statistics/);
  assert.match(composer, /Custom app deep link/);
  assert.doesNotMatch(composer, /https?:\/\//);
});

test("batch jobs show operator metadata, timing, results, paginated history, and retry controls", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/JobsPage.jsx");
  const adminUi = await source("components/AdminUI.jsx");
  assert.match(app, /jobs\.html/);
  assert.match(page, /\/jobs\/statuses\?\$\{params\}/);
  assert.match(page, /limit:\s*String\(STATUS_PAGE_SIZE\)/);
  assert.match(page, /offset:\s*String\(statusOffset\)/);
  assert.match(page, /queryKey:\s*\["admin",\s*"jobs",\s*"statuses",\s*statusOffset\]/);
  assert.match(page, /\/jobs\/runs/);
  assert.match(page, /displayName/);
  assert.match(page, /description/);
  assert.match(page, /Last started/);
  assert.match(page, /Duration/);
  assert.match(page, /Latest result/);
  assert.match(page, /Pagination/);
  assert.equal((page.match(/<Pagination/g) || []).length, 2);
  assert.match(page, /Registered jobs/);
  assert.match(page, /Healthy on page/);
  assert.match(page, /const statusPageTransitioning = statusesQuery\.isPlaceholderData/);
  assert.match(page, /const visibleJobs = statusPageTransitioning \? \[\] : statusJobs/);
  assert.match(page, /const visibleRuns = runPageTransitioning \? \[\] : runs/);
  assert.match(page, /loading=\{statusesQuery\.isLoading \|\| statusPageTransitioning\}/);
  assert.match(page, /loading=\{runsQuery\.isLoading \|\| runPageTransitioning\}/);
  assert.match(page, /ariaLabel="Job status pagination"/);
  assert.match(page, /ariaLabel="Execution history pagination"/);
  assert.match(page, /fetching=\{statusesQuery\.isFetching\}/);
  assert.match(page, /fetching=\{runsQuery\.isFetching\}/);
  assert.match(page, /run\.displayName\s*\|\|/);
  assert.match(page, /selectedRun\.displayName/);
  assert.match(adminUi, /<nav className="pagination" aria-label=\{ariaLabel\} aria-busy=\{fetching\}>/);
  assert.match(adminUi, /disabled=\{fetching \|\| page <= 1\}/);
  assert.match(adminUi, /disabled=\{fetching \|\| hasNext === false/);
  assert.match(page, /Retry job/);
  assert.match(page, /retryOfRunId/);
});

test("app control administration publishes update campaigns and manages maintenance windows", async () => {
  const app = await source("MonitoringApp.jsx");
  const page = await source("pages/ServiceStatusPage.jsx");
  const updates = await source("components/AppUpdatesWorkspace.jsx");
  const adminApi = await source("admin/adminApi.js");
  assert.match(app, /service-status\.html/);
  assert.match(page, /App updates/);
  assert.match(page, /Maintenance/);
  assert.match(page, /Translation providers/);
  assert.match(page, /provider-health\/translation\/check/);
  assert.match(page, /Check providers/);
  assert.doesNotMatch(page, /AdminGate/);
  assert.match(page, /Start maintenance/);
  assert.match(page, /Schedule maintenance/);
  assert.match(page, /End maintenance/);
  assert.match(page, /Maintenance history/);
  assert.match(page, /app-updates\/maintenance\/history/);
  assert.match(page, /adminFetch/);
  assert.doesNotMatch(page, /monitoringStatusFetch/);
  assert.doesNotMatch(adminApi, /monitoringStatusFetch/);
  assert.match(page, /titleKo/);
  assert.match(page, /titleEn/);
  assert.match(page, /titleJa/);
  assert.match(updates, /Publish recommended update/);
  assert.match(updates, /Publish required update/);
  assert.match(updates, /mode:\s*"FORCE"/);
  assert.match(updates, /iOS does not let an App Store app install its own update/);
  assert.match(updates, /app-updates\/remote-config\/publish/);
  assert.match(updates, /app-updates\/\$\{campaign\.id\}\/users/);
  assert.match(updates, /End campaign/);
  assert.match(updates, /remoteConfigStatus/);
  assert.match(updates, /targetVersion/);
  assert.match(updates, /targetBuild/);
  assert.match(page, /Maintenance was published to Firebase Remote Config/);
});

test("user administration is searchable, paginated, and supports period quota operations", async () => {
  const page = await source("pages/UsersPage.jsx");
  const adminApi = await source("admin/adminApi.js");
  assert.match(page, /const PAGE_SIZE = 20/);
  assert.match(page, /SearchField/);
  assert.match(page, /Pagination/);
  assert.match(page, /DetailDrawer/);
  assert.match(page, /ObjectInspector/);
  assert.match(page, /\/quota-adjustments/);
  assert.match(page, /bonusDelta/);
  assert.match(page, /idempotencyKey/);
  assert.match(page, /periodStartedAt/);
  assert.match(page, /Current limit/);
  assert.match(page, /Used/);
  assert.match(page, /Remaining/);
  assert.match(page, /Apply bonus until reset/);
  assert.match(page, /LIMIT_PRESETS/);
  assert.match(adminApi, /sessionStorage/);
  assert.match(adminApi, /Authorization: `Bearer \$\{session\.token\}`/);
});

test("monitoring uses one full-page administrator session and manages database accounts", async () => {
  const boundary = await source("admin/AdminAuthBoundary.jsx");
  const login = await source("pages/LoginPage.jsx");
  const operators = await source("pages/AdministratorsPage.jsx");
  const api = await source("admin/adminApi.js");
  const shell = await source("app/AppShell.jsx");
  assert.match(boundary, /const LOGIN_PATH = "\/login\.html"/);
  assert.match(boundary, /\$\{LOGIN_PATH\}\?next=\$\{next\}/);
  assert.match(boundary, /Checking administrator session/);
  assert.match(login, /Administrator sign in/);
  assert.match(login, /safeNextPath/);
  assert.match(operators, /Add administrator/);
  assert.match(operators, /\/operators/);
  assert.match(operators, /const PAGE_SIZE = 20/);
  assert.match(operators, /BCrypt hashes/);
  assert.match(api, /\/session/);
  assert.match(api, /installAuthenticatedFetch/);
  assert.match(api, /`Sign in failed \(\$\{response\.status\}\)`/);
  assert.match(shell, /nav-session-button/);
  assert.match(shell, /window\.location\.replace\("\/login\.html"\)/);
  for (const page of [
    "pages/UsersPage.jsx",
    "pages/FeedbackPage.jsx",
    "pages/JobsPage.jsx",
    "pages/StreamsPage.jsx",
    "pages/DeploymentsPage.jsx",
    "pages/ServiceStatusPage.jsx",
    "pages/ExternalApiHistoryPage.jsx",
  ]) {
    assert.doesNotMatch(await source(page), /AdminGate/);
  }
});

test("Redis Stream administration lives in monitoring Manage with bounded cursor navigation", async () => {
  const page = await source("pages/StreamsPage.jsx");
  const paths = await source("lib/streamPaths.js");
  assert.match(page, /Stream entries/);
  assert.match(page, /Event outbox/);
  assert.doesNotMatch(page, /Push outbox/);
  assert.doesNotMatch(page, /outboxes\/pushes/);
  assert.match(page, /cursorStack/);
  assert.match(page, /ObjectInspector/);
  assert.match(page, /Last error/);
  assert.match(page, /ExpandableText/);
  assert.match(paths, /streamEntriesPath/);
  assert.match(paths, /streamEntryPath/);
  const delivery = await source("components/StreamDeliveryDashboard.jsx");
  assert.match(delivery, /Inbox processing history/);
  assert.match(delivery, /streamInboxAttemptsPath/);
  assert.match(delivery, /RETRY_SCHEDULED/);
  assert.match(delivery, /LEASE_EXPIRED/);
  assert.match(delivery, /ExpandableText/);
  const ui = await source("components/AdminUI.jsx");
  assert.match(ui, /aria-expanded/);
  assert.match(ui, /title=\{value\}/);
  assert.match(ui, /Show full/);
  assert.doesNotMatch(page, /innerHTML/);
});

test("navigation is fixed, collapsible, and keeps its version at the bottom", async () => {
  const css = await source("styles/manage.css");
  const shell = await source("app/AppShell.jsx");
  assert.match(css, /\.react-side-nav\s*\{[\s\S]*position:\s*fixed/);
  assert.match(css, /background:\s*var\(--nav-bg\)/);
  assert.match(css, /--nav-bg:\s*#f8fafc/);
  assert.match(css, /\.react-nav-link\[data-current="true"\]\s*\{[\s\S]*background:\s*#eaf1ff/);
  assert.match(css, /\.react-side-nav\[data-collapsed="true"\]/);
  assert.match(css, /\.react-nav-footer\s*\{[\s\S]*position:\s*absolute/);
  assert.match(css, /\.react-nav-backdrop\[data-visible="true"\]/);
  assert.match(shell, /PanelLeftOpen/);
  assert.match(shell, /PanelLeftClose/);
  assert.match(shell, /react-brand-mark/);
  assert.match(shell, /compactVersion/);
  assert.match(shell, /monitoring:nav-mode-change/);
  assert.match(shell, /navigationGroups\.map/);
});

test("settings control navigation and access journal browser preferences", async () => {
  const page = await source("pages/SettingsPage.jsx");
  assert.match(page, /buddystudy\.monitoring\.nav\.mode/);
  assert.match(page, /buddystudy\.monitoring\.audit\.range/);
  assert.match(page, /buddystudy\.monitoring\.audit\.refreshSeconds/);
  assert.match(page, /buddystudy\.monitoring\.audit\.pageSize/);
  assert.match(page, /monitoring:nav-mode-change/);
});

test("access audit reads the monitoring gateway journal instead of backend API logs", async () => {
  const page = await source("pages/AuditPage.jsx");
  assert.match(page, /\{job="monitoring-access"\}/);
  assert.match(page, /parseMonitoringAccessLog/);
  assert.match(page, /DetailDrawer/);
  assert.doesNotMatch(page, /api_exchange/);
  assert.doesNotMatch(page, /parseApiExchange/);
});

test("server dashboard navigation and legacy URL open the detailed Grafana dashboard", async () => {
  const html = await text("system.html");
  const navigation = await source("app/navigation.js");
  assert.match(navigation, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.match(html, /http-equiv="refresh"/);
  assert.match(html, /window\.location\.replace/);
  assert.match(html, /buddystudy-server-runtime\/buddystudy-server-dashboard/);
  assert.doesNotMatch(html, /system\.js/);
});
