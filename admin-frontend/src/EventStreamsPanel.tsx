import { useEffect, useMemo, useState } from "react";
import {
  fetchEventOutbox,
  fetchPushOutbox,
  fetchStreamEntries,
  fetchStreamTopics,
  type UnauthorizedHandler,
} from "./api";
import type {
  AdminCursorPage,
  AdminPushOutboxEntry,
  AdminRedisEventOutboxEntry,
  AdminStreamEntry,
  AdminStreamTopicSummary,
} from "./types";

type Source = "stream" | "event-outbox" | "push-outbox";
type Entry = AdminStreamEntry | AdminRedisEventOutboxEntry | AdminPushOutboxEntry;

const EMPTY_PAGE: AdminCursorPage<Entry> = { items: [], nextCursor: null, hasMore: false, limit: 20 };

export function EventStreamsPanel({
  onUnauthorized,
  refreshKey,
}: {
  onUnauthorized: UnauthorizedHandler;
  refreshKey: number;
}) {
  const [source, setSource] = useState<Source>("stream");
  const [topics, setTopics] = useState<AdminStreamTopicSummary[]>([]);
  const [topic, setTopic] = useState("domain-events");
  const [eventType, setEventType] = useState("");
  const [status, setStatus] = useState("");
  const [limit, setLimit] = useState(20);
  const [cursorStack, setCursorStack] = useState<Array<string | null>>([null]);
  const [page, setPage] = useState<AdminCursorPage<Entry>>(EMPTY_PAGE);
  const [selected, setSelected] = useState<Entry | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cursor = cursorStack[cursorStack.length - 1] ?? null;

  useEffect(() => {
    void loadTopics();
  }, [refreshKey]);

  useEffect(() => {
    void loadPage();
  }, [source, topic, eventType, status, limit, cursor, refreshKey]);

  const activeTopic = useMemo(
    () => topics.find((candidate) => candidate.topic === topic) ?? topics[0],
    [topic, topics],
  );

  async function loadTopics() {
    try {
      const response = await fetchStreamTopics(onUnauthorized);
      setTopics(response);
      if (response.length && !response.some((candidate) => candidate.topic === topic)) {
        setTopic(response[0].topic);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Failed to inspect Redis Stream topics");
    }
  }

  async function loadPage() {
    setLoading(true);
    setError(null);
    try {
      const response = source === "stream"
        ? await fetchStreamEntries(topic, cursor, limit, eventType, onUnauthorized)
        : source === "event-outbox"
          ? await fetchEventOutbox(cursor, limit, status, eventType, onUnauthorized)
          : await fetchPushOutbox(cursor, limit, status, onUnauthorized);
      setPage(response);
      setSelected(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Failed to load event data");
      setPage({ ...EMPTY_PAGE, limit });
    } finally {
      setLoading(false);
    }
  }

  function changeSource(next: Source) {
    setSource(next);
    setCursorStack([null]);
    setPage({ ...EMPTY_PAGE, limit });
    setSelected(null);
  }

  function resetCursor() {
    setCursorStack([null]);
  }

  return (
    <section className="stream-workspace">
      <div className="stream-source-tabs" role="tablist" aria-label="Event data source">
        <button className={source === "stream" ? "active" : ""} onClick={() => changeSource("stream")}>Redis Stream</button>
        <button className={source === "event-outbox" ? "active" : ""} onClick={() => changeSource("event-outbox")}>Event outbox</button>
        <button className={source === "push-outbox" ? "active" : ""} onClick={() => changeSource("push-outbox")}>Push outbox</button>
      </div>

      {source === "stream" && activeTopic ? (
        <div className="stream-summary" aria-label="Redis Stream summary">
          <Summary label="Length" value={`${activeTopic.length} / ${activeTopic.maxLength}`} />
          <Summary label="First ID" value={activeTopic.firstEntryId ?? "-"} mono />
          <Summary label="Last ID" value={activeTopic.lastEntryId ?? "-"} mono />
          <Summary
            label="Consumer groups"
            value={activeTopic.groups.length
              ? activeTopic.groups.map((group) => `${group.name}: ${group.consumers} workers, ${group.pending} pending`).join(" | ")
              : "No groups"}
          />
        </div>
      ) : null}

      <div className="stream-toolbar">
        {source === "stream" ? (
          <label>
            <span>Topic</span>
            <select value={topic} onChange={(event) => { setTopic(event.target.value); resetCursor(); }}>
              {topics.map((candidate) => <option key={candidate.topic} value={candidate.topic}>{candidate.topic}</option>)}
            </select>
          </label>
        ) : null}
        {source !== "push-outbox" ? (
          <label className="grow">
            <span>Event type</span>
            <input
              value={eventType}
              placeholder="All event types"
              onChange={(event) => { setEventType(event.target.value); resetCursor(); }}
            />
          </label>
        ) : null}
        {source !== "stream" ? (
          <label>
            <span>Status</span>
            <select value={status} onChange={(event) => { setStatus(event.target.value); resetCursor(); }}>
              <option value="">All</option>
              <option value="PENDING">Pending</option>
              <option value="PROCESSING">Processing</option>
              <option value="PUBLISHED">Published</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>
        ) : null}
        <label>
          <span>Rows</span>
          <select value={limit} onChange={(event) => { setLimit(Number(event.target.value)); resetCursor(); }}>
            <option value={20}>20</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </label>
      </div>

      {error ? <div className="stream-error" role="alert">{error}</div> : null}
      <div className="stream-table-wrap" aria-busy={loading}>
        {loading ? <div className="table-loading">Loading event data...</div> : null}
        <table className="stream-table">
          <thead>{tableHead(source)}</thead>
          <tbody>
            {page.items.map((entry) => (
              <tr
                key={entryKey(entry)}
                className={selected === entry ? "selected" : ""}
                onClick={() => setSelected(entry)}
              >
                {tableCells(source, entry)}
              </tr>
            ))}
            {!loading && page.items.length === 0 ? (
              <tr><td colSpan={7} className="empty-table-cell">No entries match the current filters.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <div className="cursor-bar">
        <span>Page {cursorStack.length} · cursor <code>{cursor ?? "latest"}</code></span>
        <div>
          <button
            className="secondary-button compact"
            disabled={cursorStack.length === 1 || loading}
            onClick={() => setCursorStack((current) => current.slice(0, -1))}
          >
            Previous
          </button>
          <button
            className="secondary-button compact"
            disabled={!page.hasMore || !page.nextCursor || loading}
            onClick={() => setCursorStack((current) => [...current, page.nextCursor ?? null])}
          >
            Next
          </button>
        </div>
      </div>

      {selected ? (
        <div className="stream-detail">
          <div>
            <strong>Entry detail</strong>
            <button className="ghost-button compact" onClick={() => setSelected(null)}>Close</button>
          </div>
          <pre>{JSON.stringify(detailValue(selected), null, 2)}</pre>
        </div>
      ) : null}
    </section>
  );
}

function Summary({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return <div><span>{label}</span><strong className={mono ? "mono" : ""}>{value}</strong></div>;
}

function tableHead(source: Source) {
  if (source === "stream") {
    return <tr><th>ID</th><th>Event type</th><th>Event ID</th><th>Record</th><th>User</th><th>Device</th></tr>;
  }
  if (source === "event-outbox") {
    return <tr><th>ID</th><th>Event type</th><th>Event ID</th><th>Status</th><th>Attempts</th><th>Created</th><th>Published</th></tr>;
  }
  return <tr><th>ID</th><th>Record</th><th>Topic</th><th>Status</th><th>Attempts</th><th>User</th><th>Created</th></tr>;
}

function tableCells(source: Source, entry: Entry) {
  if (source === "stream") {
    const value = entry as AdminStreamEntry;
    return <><td className="mono">{value.id}</td><td>{value.eventType ?? "-"}</td><td className="mono">{value.eventId ?? "-"}</td><td>{value.recordId ?? "-"}</td><td>{value.userId ?? "-"}</td><td className="mono">{value.deviceId ?? "-"}</td></>;
  }
  if (source === "event-outbox") {
    const value = entry as AdminRedisEventOutboxEntry;
    return <><td>{value.id}</td><td>{value.eventType}</td><td className="mono">{value.eventId}</td><td><Status value={value.status} /></td><td>{value.attempts}</td><td>{formatTime(value.createdAt)}</td><td>{formatTime(value.publishedAt)}</td></>;
  }
  const value = entry as AdminPushOutboxEntry;
  return <><td>{value.id}</td><td>{value.recordId}</td><td>{value.topic}</td><td><Status value={value.status} /></td><td>{value.attempts}</td><td>{value.userId ?? "-"}</td><td>{formatTime(value.createdAt)}</td></>;
}

function Status({ value }: { value?: string | null }) {
  const normalized = value?.trim() || "UNKNOWN";
  return <span className={`stream-status ${normalized.toLowerCase()}`}>{normalized}</span>;
}

function entryKey(entry: Entry): string {
  return "fields" in entry ? `stream-${entry.id}` : `outbox-${entry.id}`;
}

function detailValue(entry: Entry) {
  if ("fields" in entry) return entry.fields;
  if ("payloadJson" in entry) {
    return { ...entry, payloadJson: parseJson(entry.payloadJson) };
  }
  return entry;
}

function parseJson(value: string) {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function formatTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "-";
}
