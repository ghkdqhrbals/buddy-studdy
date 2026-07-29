import { useQuery } from "@tanstack/react-query";
import { Activity, RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import {
  DataTable,
  Pagination, SearchField,
  StatusBadge,
} from "./AdminUI.jsx";
import { Button } from "./Button.jsx";
import { InlineNotice } from "./InlineNotice.jsx";
import {
  formatStreamDuration,
  streamGroupRows,
  streamGroupState,
  streamRetention,
  summarizeStreamHealth,
} from "../lib/streamHealth.js";
import { streamPendingPath } from "../lib/streamPaths.js";

const PENDING_PAGE_SIZE = 20;

function number(value) {
  return Number(value || 0).toLocaleString();
}

function InspectionErrors({ errors }) {
  const items = Array.isArray(errors) ? errors : [];
  if (items.length === 0) return null;
  return (
    <InlineNotice tone="warning">
      {items.map((item) => `${item.operation}: ${item.message}`).join(" · ")}
    </InlineNotice>
  );
}

function ConsumerList({ consumers }) {
  const rows = Array.isArray(consumers) ? consumers : [];
  return (
    <div className="stream-detail-block">
      <div className="stream-detail-heading">
        <div>
          <h3>Consumers</h3>
          <p>Current worker ownership and idle time reported by Redis.</p>
        </div>
      </div>
      <DataTable
        columns={[
          { key: "name", label: "Consumer", className: "mono" },
          { key: "pending", label: "Pending", render: (row) => number(row.pending) },
          { key: "idleMs", label: "Idle", render: (row) => formatStreamDuration(row.idleMs) },
          { key: "inactiveMs", label: "Inactive", render: (row) => formatStreamDuration(row.inactiveMs) },
        ]}
        rows={rows}
        rowKey={(row) => row.name}
        emptyText="No active consumers are registered for this group."
        loading={false}
      />
    </div>
  );
}

function PendingList({ topic, group }) {
  const [cursorStack, setCursorStack] = useState([""]);
  const [pageIndex, setPageIndex] = useState(0);
  const cursor = cursorStack[pageIndex] || "";
  const query = useQuery({
    queryKey: ["admin", "stream-pending", topic, group, cursor],
    queryFn: () => adminFetch(streamPendingPath(topic, group, { cursor, limit: PENDING_PAGE_SIZE })),
    enabled: Boolean(topic && group),
    refetchInterval: 5_000,
  });
  const rows = Array.isArray(query.data?.items) ? query.data.items : [];

  function next() {
    if (!query.data?.nextCursor) return;
    setCursorStack((current) => {
      const nextStack = [...current];
      nextStack[pageIndex + 1] = query.data.nextCursor;
      return nextStack;
    });
    setPageIndex((value) => value + 1);
  }

  return (
    <div className="stream-detail-block">
      <div className="stream-detail-heading">
        <div>
          <h3>Pending deliveries</h3>
          <p>Delivered messages waiting for ACK. Retry count excludes the first delivery.</p>
        </div>
        <Button variant="ghost" icon={RefreshCw} onClick={() => query.refetch()} disabled={query.isFetching}>
          Refresh
        </Button>
      </div>
      {query.error ? <InlineNotice tone="danger">{query.error.message}</InlineNotice> : null}
      <DataTable
        columns={[
          { key: "id", label: "Pending ID", className: "mono" },
          { key: "consumer", label: "Consumer", className: "mono" },
          { key: "idleMs", label: "Idle", render: (row) => formatStreamDuration(row.idleMs) },
          { key: "deliveryCount", label: "Deliveries", render: (row) => number(row.deliveryCount) },
          {
            key: "retryCount",
            label: "Retries",
            render: (row) => (
              <StatusBadge tone={Number(row.retryCount) > 0 ? "danger" : "neutral"}>
                {number(row.retryCount)}
              </StatusBadge>
            ),
          },
        ]}
        rows={rows}
        rowKey={(row) => row.id}
        emptyText="This group has no pending deliveries."
        loading={query.isLoading}
      />
      <Pagination
        page={pageIndex + 1}
        label={`${rows.length} pending deliveries on this page`}
        hasNext={Boolean(query.data?.hasMore && query.data?.nextCursor)}
        onPrevious={() => setPageIndex((value) => Math.max(0, value - 1))}
        onNext={next}
      />
    </div>
  );
}

export function StreamDeliveryDashboard({
  topics,
  loading,
  fetching,
  error,
  onRefresh,
}) {
  const [selectedKey, setSelectedKey] = useState("");
  const [search, setSearch] = useState("");
  const normalizedSearch = search.trim().toLowerCase();
  const rows = useMemo(
    () => streamGroupRows(topics).filter((row) => (
      !normalizedSearch ||
      row.topic.toLowerCase().includes(normalizedSearch) ||
      row.streamKey.toLowerCase().includes(normalizedSearch) ||
      row.name.toLowerCase().includes(normalizedSearch) ||
      (row.consumerDetails || []).some((consumer) =>
        consumer.name.toLowerCase().includes(normalizedSearch))
    )),
    [normalizedSearch, topics],
  );
  const summary = useMemo(() => summarizeStreamHealth(topics), [topics]);
  const selected = rows.find((row) => `${row.topic}:${row.name}` === selectedKey) || null;
  const streamColumns = useMemo(() => [
    {
      key: "topic",
      label: "Stream",
      render: (row) => (
        <span className="primary-cell">
          <strong>{row.topic}</strong>
          <span className="mono">{row.streamKey}</span>
        </span>
      ),
    },
    { key: "length", label: "Entries", render: (row) => number(row.length) },
    { key: "maxLength", label: "MAXLEN", render: (row) => number(row.maxLength) },
    {
      key: "retention",
      label: "Retention",
      render: (row) => {
        const retention = streamRetention(row);
        return (
          <span className="stream-retention" title={`${number(row.length)} of ${number(row.maxLength)} retained entries`}>
            <span><i style={{ width: `${retention.percent || 0}%` }} /></span>
            <strong>{retention.label}</strong>
          </span>
        );
      },
    },
    { key: "groups", label: "Groups", render: (row) => number(row.groups?.length) },
    {
      key: "inspection",
      label: "Inspection",
      render: (row) => (
        <StatusBadge tone={row.inspectionErrors?.length ? "warning" : "success"}>
          {row.inspectionErrors?.length ? "Partial data" : "Healthy"}
        </StatusBadge>
      ),
    },
  ], []);

  const columns = useMemo(() => [
    {
      key: "topic",
      label: "Stream",
      render: (row) => (
        <span className="primary-cell">
          <strong>{row.topic}</strong>
          <span className="mono">{row.streamKey}</span>
        </span>
      ),
    },
    { key: "name", label: "Consumer group", className: "mono" },
    {
      key: "lastDeliveredId",
      label: "Offset",
      className: "mono",
      render: (row) => row.lastDeliveredId || "-",
    },
    { key: "lag", label: "Lag", render: (row) => row.lag == null ? "Unknown" : number(row.lag) },
    { key: "pending", label: "Pending", render: (row) => number(row.pending) },
    {
      key: "maxRetryCount",
      label: "Max retries",
      render: (row) => (
        <span title={row.pendingSampleTruncated ? "Calculated from the first 100 pending deliveries." : ""}>
          {number(row.maxRetryCount)}{row.pendingSampleTruncated ? "+" : ""}
        </span>
      ),
    },
    {
      key: "state",
      label: "State",
      render: (row) => {
        const state = streamGroupState(row);
        return <StatusBadge tone={state.tone}>{state.label}</StatusBadge>;
      },
    },
  ], []);

  return (
    <>
      <div className="stream-live-bar">
        <div>
          <Activity size={16} aria-hidden="true" />
          <span>Live consumer-group state</span>
          <small>Auto refresh every 5 seconds</small>
        </div>
        <Button variant="secondary" icon={RefreshCw} onClick={onRefresh} disabled={fetching}>
          Refresh
        </Button>
      </div>
      <div className="metric-strip stream-metric-strip">
        <div><span>Streams</span><strong>{number(summary.streams)}</strong></div>
        <div><span>Consumer groups</span><strong>{number(summary.groups)}</strong></div>
        <div><span>Total lag</span><strong>{number(summary.lag)}</strong></div>
        <div><span>Pending</span><strong>{number(summary.pending)}</strong></div>
        <div><span>Groups retrying</span><strong>{number(summary.retrying)}</strong></div>
        <div><span>Inspection failures</span><strong>{number(summary.inspectionFailures)}</strong></div>
      </div>
      <div className="stream-legend">
        <span><strong>Offset</strong> last delivered Redis ID</span>
        <span><strong>Lag</strong> not delivered to this group</span>
        <span><strong>Pending</strong> delivered but not ACKed</span>
        <span><strong>Retries</strong> deliveries after the first attempt</span>
      </div>
      {error ? <InlineNotice tone="danger">{error.message}</InlineNotice> : null}
      <section className="stream-overview">
        <div className="stream-section-heading">
          <div>
            <h2>Stream retention</h2>
            <p>Current Redis XLEN against the configured exact MAXLEN.</p>
          </div>
        </div>
        <DataTable
          columns={streamColumns}
          rows={topics}
          rowKey={(row) => row.topic}
          emptyText="No configured Redis streams were found."
          loading={loading}
        />
        {topics.flatMap((topic) => topic.inspectionErrors || []).length > 0 ? (
          <div className="stream-inspection-errors">
            {topics.map((topic) => (
              <InspectionErrors key={topic.topic} errors={topic.inspectionErrors} />
            ))}
          </div>
        ) : null}
      </section>
      <section className="stream-groups">
        <div className="stream-section-heading stream-group-toolbar">
          <div>
            <h2>Consumer groups</h2>
            <p>Search by stream, group, or consumer and inspect delivery state.</p>
          </div>
          <SearchField
            value={search}
            onChange={setSearch}
            onSubmit={() => {}}
            label="Consumer group search"
            placeholder="Stream, group, or consumer"
          />
        </div>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => `${row.topic}:${row.name}`}
        onRowClick={(row) => setSelectedKey(`${row.topic}:${row.name}`)}
        emptyText="No Redis consumer groups were found."
        loading={loading}
      />
      </section>
      {selected ? (
        <section className="stream-group-detail">
          <header>
            <div>
              <span className="page-eyebrow">Selected consumer group</span>
              <h2>{selected.topic} / {selected.name}</h2>
              <p className="mono">{selected.streamKey}</p>
            </div>
            <StatusBadge tone={streamGroupState(selected).tone}>{streamGroupState(selected).label}</StatusBadge>
          </header>
          <div className="detail-summary stream-group-summary">
            <div><span>Offset</span><strong className="mono">{selected.lastDeliveredId || "-"}</strong></div>
            <div><span>Entries read</span><strong>{selected.entriesRead == null ? "Unknown" : number(selected.entriesRead)}</strong></div>
            <div><span>Pending range</span><strong className="mono">{selected.pendingMinId && selected.pendingMaxId ? `${selected.pendingMinId} – ${selected.pendingMaxId}` : "-"}</strong></div>
            <div><span>Oldest pending</span><strong>{formatStreamDuration(selected.oldestPendingIdleMs)}</strong></div>
          </div>
          <InspectionErrors errors={[...(selected.topicInspectionErrors || []), ...(selected.inspectionErrors || [])]} />
          <ConsumerList consumers={selected.consumerDetails} />
          <PendingList key={selectedKey} topic={selected.topic} group={selected.name} />
        </section>
      ) : null}
    </>
  );
}
