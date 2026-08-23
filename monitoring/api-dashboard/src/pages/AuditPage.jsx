import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import {
  DataTable,
  DetailDrawer,
  PageHeader,
  Pagination,
  StatusBadge,
} from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { ObjectInspector } from "../components/ObjectInspector.jsx";
import {
  filterAuditEntries,
  parseMonitoringAccessLog,
  summarizeAuditEntries,
} from "../lib/auditModel.js";
import { formatDateTime, formatDuration, statusTone } from "../lib/format.js";

const ACCESS_QUERY = '{job="monitoring-access"} |= "monitoring_access"';
const PAGE_SIZE_KEY = "buddystudy.monitoring.audit.pageSize";

function nanoseconds(milliseconds) {
  return (BigInt(milliseconds) * 1_000_000n).toString();
}

async function loadAuditEntries(range) {
  const end = Date.now();
  const params = new URLSearchParams({
    query: ACCESS_QUERY,
    start: nanoseconds(end - Number(range)),
    end: nanoseconds(end),
    limit: "2000",
    direction: "backward",
  });
  const response = await fetch(`/loki/api/v1/query_range?${params}`);
  if (!response.ok) throw new Error(`Loki query failed (${response.status})`);
  const payload = await response.json();
  return (payload.data?.result ?? [])
    .flatMap((stream) => stream.values ?? [])
    .map((value) => {
      try { return parseMonitoringAccessLog(value); } catch { return null; }
    })
    .filter(Boolean)
    .sort((left, right) => left.timestampMs > right.timestampMs ? -1 : 1);
}

export function AuditPage() {
  const [range, setRange] = useState(() => window.localStorage.getItem("buddystudy.monitoring.audit.range") || "3600000");
  const [eventType, setEventType] = useState("all");
  const [ip, setIp] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState(null);
  const pageSize = Number(window.localStorage.getItem(PAGE_SIZE_KEY)) || 50;
  const query = useQuery({
    queryKey: ["monitoring-audit", range],
    queryFn: () => loadAuditEntries(range),
    refetchInterval: Number(window.localStorage.getItem("buddystudy.monitoring.audit.refreshSeconds")) * 1000 || false,
  });
  const filtered = useMemo(
    () => filterAuditEntries(query.data || [], { eventType, ip, search }),
    [eventType, ip, query.data, search],
  );
  const summary = summarizeAuditEntries(filtered);
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const rows = filtered.slice((safePage - 1) * pageSize, safePage * pageSize);
  const columns = [
    { key: "timestampMs", label: "Time", render: (row) => formatDateTime(row.timestampMs) },
    { key: "eventType", label: "Event", render: (row) => <StatusBadge tone={row.eventType === "denied" ? "danger" : row.eventType === "action" ? "warning" : "neutral"}>{row.eventType}</StatusBadge> },
    { key: "user", label: "User" },
    { key: "method", label: "Method", className: "mono" },
    { key: "path", label: "Path", className: "path-cell" },
    { key: "status", label: "Status", render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.status}</StatusBadge> },
    { key: "durationMs", label: "Duration", render: (row) => formatDuration(row.durationMs) },
    { key: "clientIp", label: "Client IP", className: "mono" },
    { key: "requestId", label: "Request ID", className: "mono" },
  ];

  function updateRange(value) {
    setRange(value);
    setPage(1);
    window.localStorage.setItem("buddystudy.monitoring.audit.range", value);
  }

  return (
    <>
      <PageHeader
        eyebrow="Observe"
        title="Access & audit"
        description="Review access to this monitoring console, including page views, administrative actions, and denied requests."
        actions={<Button variant="secondary" icon={RefreshCw} busy={query.isFetching} onClick={() => query.refetch()}>Refresh</Button>}
      />
      <div className="metric-strip">
        <div><span>Matching events</span><strong>{summary.total.toLocaleString()}</strong></div>
        <div><span>Unique client IPs</span><strong>{summary.uniqueIps.toLocaleString()}</strong></div>
        <div><span>Page views</span><strong>{summary.pageViews.toLocaleString()}</strong></div>
        <div><span>Denied</span><strong>{summary.denied.toLocaleString()}</strong></div>
      </div>
      <section className="workspace-section">
        <div className="audit-toolbar">
          <label className="field compact-field"><span>Time range</span><select value={range} onChange={(event) => updateRange(event.target.value)}><option value="3600000">Last hour</option><option value="21600000">Last 6 hours</option><option value="86400000">Last 24 hours</option><option value="604800000">Last 7 days</option></select></label>
          <label className="field compact-field"><span>Event</span><select value={eventType} onChange={(event) => { setEventType(event.target.value); setPage(1); }}><option value="all">All events</option><option value="page">Page views</option><option value="action">Actions</option><option value="denied">Denied</option></select></label>
          <label className="field compact-field"><span>Client IP</span><input value={ip} onChange={(event) => { setIp(event.target.value); setPage(1); }} placeholder="Filter IP" /></label>
          <label className="field compact-field wide-field"><span>Request search</span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(1); }} placeholder="Path, request ID, user, or agent" /></label>
        </div>
        {query.error ? <InlineNotice tone="danger">{query.error.message}</InlineNotice> : null}
        <DataTable columns={columns} rows={rows} rowKey={(row) => row.nanoseconds} onRowClick={setSelected} emptyText="No audit events match these filters." loading={query.isLoading} />
        <Pagination page={safePage} totalPages={totalPages} label={`${filtered.length.toLocaleString()} matching events`} onPrevious={() => setPage(Math.max(1, safePage - 1))} onNext={() => setPage(Math.min(totalPages, safePage + 1))} />
      </section>
      <DetailDrawer open={Boolean(selected)} title={selected ? `${selected.method} ${selected.path}` : ""} subtitle={selected ? formatDateTime(selected.timestampMs) : ""} onClose={() => setSelected(null)}>
        {selected ? <ObjectInspector value={selected} title="Audit event" /> : null}
      </DetailDrawer>
    </>
  );
}
