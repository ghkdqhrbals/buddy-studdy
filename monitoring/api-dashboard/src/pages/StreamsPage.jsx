import { useQuery } from "@tanstack/react-query";
import { RefreshCw, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import {
  DataTable,
  DetailDrawer,
  PageHeader,
  Pagination,
  SearchField,
  SegmentedTabs,
  StatusBadge,
} from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { ObjectInspector } from "../components/ObjectInspector.jsx";
import { StreamDeliveryDashboard } from "../components/StreamDeliveryDashboard.jsx";
import { formatDateTime, statusTone } from "../lib/format.js";
import {
  cursorPath,
  isRedisStreamId,
  streamEntriesPath,
  streamEntryPath,
} from "../lib/streamPaths.js";

const MODES = [
  { value: "delivery", label: "Delivery status" },
  { value: "streams", label: "Stream entries" },
  { value: "events", label: "Event outbox" },
  { value: "pushes", label: "Push outbox" },
];

function useCursorPage(queryKey, pathBuilder, dependencies, enabled = true) {
  const [cursorStack, setCursorStack] = useState([""]);
  const [pageIndex, setPageIndex] = useState(0);
  const cursor = cursorStack[pageIndex] || "";
  const query = useQuery({
    queryKey: [...queryKey, cursor, ...dependencies],
    queryFn: () => adminFetch(pathBuilder(cursor)),
    enabled,
  });
  function reset() {
    setCursorStack([""]);
    setPageIndex(0);
  }
  function next() {
    if (!query.data?.nextCursor) return;
    setCursorStack((current) => {
      const nextStack = [...current];
      nextStack[pageIndex + 1] = query.data.nextCursor;
      return nextStack;
    });
    setPageIndex((value) => value + 1);
  }
  return {
    query,
    page: pageIndex + 1,
    previous: () => setPageIndex((value) => Math.max(0, value - 1)),
    next,
    reset,
  };
}

function StreamsWorkspace() {
  const [mode, setMode] = useState("delivery");
  const [limit, setLimit] = useState(20);
  const [status, setStatus] = useState("");
  const [eventType, setEventType] = useState("");
  const [topicSearch, setTopicSearch] = useState("");
  const [topicQuery, setTopicQuery] = useState("");
  const [topic, setTopic] = useState("");
  const [entryId, setEntryId] = useState("");
  const [exactEntryId, setExactEntryId] = useState("");
  const [selected, setSelected] = useState(null);

  const topicsQuery = useQuery({
    queryKey: ["admin", "stream-topics", topicQuery],
    queryFn: () => adminFetch(`/event-streams/topics${topicQuery ? `?query=${encodeURIComponent(topicQuery)}` : ""}`),
    enabled: mode === "streams" || mode === "delivery",
    refetchInterval: mode === "delivery" ? 5_000 : false,
  });
  const topics = Array.isArray(topicsQuery.data) ? topicsQuery.data : [];
  const selectedTopic = topics.find((item) => item.topic === topic) || topics[0] || null;
  const activeTopic = selectedTopic?.topic || "";

  const streamPage = useCursorPage(
    ["admin", "stream-entries", activeTopic],
    (cursor) => streamEntriesPath(activeTopic, { cursor, limit, eventType }),
    [limit, eventType],
    mode === "streams" && Boolean(activeTopic) && !exactEntryId,
  );
  const eventPage = useCursorPage(
    ["admin", "event-outbox"],
    (cursor) => cursorPath("/event-streams/outboxes/events", { cursor, limit, status, eventType }),
    [limit, status, eventType],
    mode === "events",
  );
  const pushPage = useCursorPage(
    ["admin", "push-outbox"],
    (cursor) => cursorPath("/event-streams/outboxes/pushes", { cursor, limit, status }),
    [limit, status],
    mode === "pushes",
  );

  const exactQuery = useQuery({
    queryKey: ["admin", "stream-entry", activeTopic, exactEntryId],
    queryFn: () => adminFetch(streamEntryPath(activeTopic, exactEntryId)),
    enabled: mode === "streams" && Boolean(activeTopic && exactEntryId),
  });
  const currentPage = mode === "streams" ? streamPage : mode === "events" ? eventPage : pushPage;
  const currentQuery = mode === "delivery"
    ? topicsQuery
    : exactEntryId
      ? exactQuery
      : currentPage.query;
  const rows = exactEntryId
    ? (exactQuery.data ? [exactQuery.data] : [])
    : (Array.isArray(currentQuery.data?.items) ? currentQuery.data.items : []);

  const columns = useMemo(() => ({
    streams: [
      { key: "id", label: "Stream ID", className: "mono" },
      { key: "eventType", label: "Event type" },
      { key: "eventId", label: "Event ID", className: "mono" },
      { key: "recordId", label: "Record ID" },
      { key: "userId", label: "User ID" },
      { key: "deviceId", label: "Device ID", className: "mono" },
    ],
    events: [
      { key: "id", label: "ID", className: "mono" },
      { key: "eventType", label: "Event type" },
      { key: "streamKey", label: "Published stream", className: "mono" },
      { key: "eventId", label: "Event ID", className: "mono" },
      { key: "status", label: "Status", render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.status}</StatusBadge> },
      { key: "attempts", label: "Attempts" },
      { key: "nextAttemptAt", label: "Next attempt", render: (row) => formatDateTime(row.nextAttemptAt) },
      { key: "updatedAt", label: "Updated", render: (row) => formatDateTime(row.updatedAt) },
    ],
    pushes: [
      { key: "id", label: "ID", className: "mono" },
      { key: "topic", label: "Topic" },
      { key: "streamKey", label: "Published stream", className: "mono" },
      { key: "recordId", label: "Record ID" },
      { key: "userId", label: "User ID" },
      { key: "deviceId", label: "Device ID", className: "mono" },
      { key: "status", label: "Status", render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.status}</StatusBadge> },
      { key: "attempts", label: "Attempts" },
      { key: "updatedAt", label: "Updated", render: (row) => formatDateTime(row.updatedAt) },
    ],
  }), []);

  function changeMode(nextMode) {
    setMode(nextMode);
    setSelected(null);
    setExactEntryId("");
  }

  function findEntry() {
    const value = entryId.trim();
    if (!isRedisStreamId(value)) return;
    setExactEntryId(value);
  }

  const error = topicsQuery.error || currentQuery.error;
  return (
    <>
      <section className="workspace-section">
        <div className="section-heading mode-heading">
          <SegmentedTabs value={mode} onChange={changeMode} items={MODES} ariaLabel="Redis data source" />
          {mode !== "delivery" ? (
            <div className="inline-controls">
              {mode !== "pushes" ? (
                <label className="field compact-field"><span>Event type</span><input value={eventType} onChange={(event) => {
                  setEventType(event.target.value);
                  streamPage.reset();
                  eventPage.reset();
                }} placeholder="All types" /></label>
              ) : null}
              {mode !== "streams" ? (
                <label className="field compact-field"><span>Status</span><input value={status} onChange={(event) => {
                  setStatus(event.target.value);
                  eventPage.reset();
                  pushPage.reset();
                }} placeholder="All statuses" /></label>
              ) : null}
              <label className="field compact-field page-size-field">
                <span>Rows</span>
                <select value={limit} onChange={(event) => {
                  setLimit(Number(event.target.value));
                  streamPage.reset();
                  eventPage.reset();
                  pushPage.reset();
                }}>
                  {[20, 50, 100].map((value) => <option key={value}>{value}</option>)}
                </select>
              </label>
              <Button variant="secondary" icon={RefreshCw} onClick={() => currentQuery.refetch()}>Refresh</Button>
            </div>
          ) : null}
        </div>

        {mode === "streams" ? (
          <>
            <div className="stream-toolbar">
              <SearchField
                value={topicSearch}
                onChange={setTopicSearch}
                onSubmit={() => { setTopicQuery(topicSearch.trim()); setTopic(""); streamPage.reset(); }}
                label="Stream search"
                placeholder="Search stream topics"
              />
              <label className="field toolbar-field">
                <span>Topic</span>
                <select value={activeTopic} onChange={(event) => { setTopic(event.target.value); streamPage.reset(); }}>
                  {topics.map((item) => <option key={item.topic}>{item.topic}</option>)}
                </select>
              </label>
              <form className="exact-id-form" onSubmit={(event) => { event.preventDefault(); findEntry(); }}>
                <label className="field toolbar-field"><span>Exact stream ID</span><input value={entryId} onChange={(event) => setEntryId(event.target.value)} placeholder="1785000998000-0" /></label>
                <Button variant="secondary" icon={Search} type="submit" disabled={!isRedisStreamId(entryId)}>Find</Button>
                {exactEntryId ? <Button variant="ghost" onClick={() => { setEntryId(""); setExactEntryId(""); }}>Clear</Button> : null}
              </form>
            </div>
            {selectedTopic ? (
              <div className="detail-summary stream-summary">
                <div><span>Stream key</span><strong className="mono">{selectedTopic.streamKey}</strong></div>
                <div><span>Length</span><strong>{Number(selectedTopic.length).toLocaleString()} / {Number(selectedTopic.maxLength).toLocaleString()}</strong></div>
                <div><span>First entry</span><strong className="mono">{selectedTopic.firstEntryId || "-"}</strong></div>
                <div><span>Last entry</span><strong className="mono">{selectedTopic.lastEntryId || "-"}</strong></div>
              </div>
            ) : null}
          </>
        ) : null}

        {mode === "delivery" ? (
          <StreamDeliveryDashboard
            topics={topics}
            loading={topicsQuery.isLoading}
            fetching={topicsQuery.isFetching}
            error={topicsQuery.error}
            onRefresh={() => topicsQuery.refetch()}
          />
        ) : (
          <>
            {error ? <InlineNotice tone="danger">{error.message}</InlineNotice> : null}
            {mode === "streams" && exactEntryId && !isRedisStreamId(exactEntryId) ? <InlineNotice tone="danger">Use a Redis Stream ID such as 1785000998000-0.</InlineNotice> : null}
            <DataTable
              columns={columns[mode]}
              rows={rows}
              rowKey={(row) => `${mode}-${row.id}`}
              onRowClick={setSelected}
              emptyText={mode === "streams" ? "No stream entries found." : "No outbox entries found."}
              loading={currentQuery.isLoading || currentQuery.isFetching}
            />
            {!exactEntryId ? (
              <Pagination
                page={currentPage.page}
                label={`${rows.length} entries on this page`}
                hasNext={Boolean(currentPage.query.data?.hasMore && currentPage.query.data?.nextCursor)}
                onPrevious={currentPage.previous}
                onNext={currentPage.next}
              />
            ) : null}
          </>
        )}
      </section>
      <DetailDrawer
        open={Boolean(selected)}
        title={selected ? `${mode === "streams" ? "Stream entry" : "Outbox entry"} ${selected.id}` : ""}
        subtitle={selected?.eventType || selected?.topic || selected?.status}
        onClose={() => setSelected(null)}
      >
        {selected ? <ObjectInspector value={selected} title={mode === "streams" ? "Stream object" : "Outbox object"} /> : null}
      </DetailDrawer>
    </>
  );
}

export function StreamsPage() {
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Redis event inspection"
        description="Monitor consumer-group offsets, lag, pending deliveries, retries, stream entries, and delivery outboxes."
      />
      <StreamsWorkspace />
    </>
  );
}
