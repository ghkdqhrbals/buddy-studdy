import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import {
  DataTable,
  DetailDrawer,
  ExpandableText,
  PageHeader,
  Pagination,
  SearchField,
  StatusBadge,
} from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { ObjectInspector } from "../components/ObjectInspector.jsx";
import { formatDateTime, statusTone } from "../lib/format.js";

function historyPath({ cursor, limit, provider, status, query }) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  if (provider) params.set("provider", provider);
  if (status) params.set("status", status);
  if (query) params.set("query", query);
  return `/external-api-history?${params}`;
}

export function ExternalApiHistoryPage() {
  const [limit, setLimit] = useState(20);
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [cursorStack, setCursorStack] = useState([""]);
  const [pageIndex, setPageIndex] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const cursor = cursorStack[pageIndex] || "";

  const page = useQuery({
    queryKey: ["admin", "external-api-history", cursor, limit, provider, status, query],
    queryFn: () => adminFetch(historyPath({ cursor, limit, provider, status, query })),
    placeholderData: keepPreviousData,
  });
  const detail = useQuery({
    queryKey: ["admin", "external-api-history", selectedId],
    queryFn: () => adminFetch(`/external-api-history/${selectedId}`),
    enabled: Boolean(selectedId),
  });
  const rows = Array.isArray(page.data?.items) ? page.data.items : [];

  function resetPage() {
    setCursorStack([""]);
    setPageIndex(0);
  }

  function nextPage() {
    if (!page.data?.nextCursor) return;
    setCursorStack((current) => {
      const next = [...current];
      next[pageIndex + 1] = page.data.nextCursor;
      return next;
    });
    setPageIndex((current) => current + 1);
  }

  const columns = useMemo(() => [
    { key: "startedAt", label: "Started", render: (row) => formatDateTime(row.startedAt) },
    { key: "provider", label: "Provider" },
    { key: "operation", label: "Operation" },
    { key: "httpMethod", label: "Method", className: "mono" },
    {
      key: "status",
      label: "Status",
      render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.status}</StatusBadge>,
    },
    { key: "responseStatus", label: "HTTP", render: (row) => row.responseStatus ?? "-" },
    { key: "durationMs", label: "Duration", render: (row) => row.durationMs == null ? "-" : `${row.durationMs} ms` },
    {
      key: "errorMessage",
      label: "Error",
      render: (row) => <ExpandableText value={row.errorMessage} label={`External API error ${row.callId}`} />,
    },
  ], []);

  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="External API history"
        description="Inspect every outbound provider request and response. Authentication headers and secret fields are redacted before storage."
      />
      <section className="workspace-section">
        <div className="stream-toolbar">
          <SearchField
            value={search}
            onChange={setSearch}
            onSubmit={() => { setQuery(search.trim()); resetPage(); }}
            label="Search calls"
            placeholder="Call ID, operation, URL, or error"
          />
          <label className="field toolbar-field"><span>Provider</span><input value={provider} onChange={(event) => {
            setProvider(event.target.value.trim().toLowerCase()); resetPage();
          }} placeholder="All providers" /></label>
          <label className="field toolbar-field"><span>Status</span><select value={status} onChange={(event) => {
            setStatus(event.target.value); resetPage();
          }}>
            <option value="">All statuses</option>
            {["STARTED", "SUCCEEDED", "HTTP_ERROR", "FAILED", "CANCELLED"].map((value) => <option key={value}>{value}</option>)}
          </select></label>
          <label className="field compact-field page-size-field"><span>Rows</span><select value={limit} onChange={(event) => {
            setLimit(Number(event.target.value)); resetPage();
          }}>{[20, 50, 100].map((value) => <option key={value}>{value}</option>)}</select></label>
          <Button variant="secondary" icon={RefreshCw} onClick={() => page.refetch()}>Refresh</Button>
        </div>
        {page.error ? <InlineNotice tone="danger">{page.error.message}</InlineNotice> : null}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          onRowClick={(row) => setSelectedId(row.id)}
          emptyText="No external API calls found."
          loading={page.isLoading}
        />
        <Pagination
          page={pageIndex + 1}
          label={`${rows.length} calls on this page`}
          hasNext={Boolean(page.data?.hasMore && page.data?.nextCursor)}
          onPrevious={() => setPageIndex((current) => Math.max(0, current - 1))}
          onNext={nextPage}
        />
      </section>
      <DetailDrawer
        open={Boolean(selectedId)}
        title={detail.data ? `${detail.data.provider} · ${detail.data.operation}` : "External API call"}
        subtitle={detail.data?.callId || "Loading request and response…"}
        onClose={() => setSelectedId(null)}
      >
        {detail.error ? <InlineNotice tone="danger">{detail.error.message}</InlineNotice> : null}
        {detail.data ? <ObjectInspector value={detail.data} title="Request and response" /> : null}
      </DetailDrawer>
    </>
  );
}
