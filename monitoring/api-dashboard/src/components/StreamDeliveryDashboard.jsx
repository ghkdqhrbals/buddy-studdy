import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ChevronDown,
  ChevronRight,
  Database,
  RefreshCw,
} from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import {
  DataTable,
  Pagination,
  SearchField,
  StatusBadge,
} from "./AdminUI.jsx";
import { Button } from "./Button.jsx";
import { InlineNotice } from "./InlineNotice.jsx";
import {
  formatStreamActivity,
  formatStreamDuration,
  latestConsumerActivity,
  streamEntryAge,
  streamGroupState,
  streamLatestConsumerActivity,
  streamOperationalState,
  streamRetention,
  summarizeStreamHealth,
} from "../lib/streamHealth.js";
import { streamPendingPath } from "../lib/streamPaths.js";

const PENDING_PAGE_SIZE = 20;

function number(value) {
  return Number(value || 0).toLocaleString();
}

function totalFor(groups, field) {
  return groups.reduce((total, group) => total + Number(group[field] || 0), 0);
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

function Retention({ topic }) {
  const retention = streamRetention(topic);
  return (
    <span
      className="stream-retention"
      title={`${number(topic.length)} of ${number(topic.maxLength)} retained entries`}
    >
      <span><i style={{ width: `${retention.percent || 0}%` }} /></span>
      <strong>{number(topic.length)} / {number(topic.maxLength)}</strong>
    </span>
  );
}

function ConsumerList({ consumers }) {
  const rows = Array.isArray(consumers) ? consumers : [];
  return (
    <section className="stream-subsection">
      <div className="stream-subsection-heading">
        <div>
          <h4>Consumers</h4>
          <p>Worker ownership, unacknowledged messages, and Redis-reported activity.</p>
        </div>
        <span>{number(rows.length)} registered</span>
      </div>
      <DataTable
        columns={[
          { key: "name", label: "Consumer", className: "mono" },
          {
            key: "pending",
            label: "Pending",
            render: (row) => (
              <StatusBadge tone={Number(row.pending) > 0 ? "warning" : "neutral"}>
                {number(row.pending)}
              </StatusBadge>
            ),
          },
          {
            key: "inactiveMs",
            label: "Last successful delivery",
            render: (row) => (
              <span title="Time since the consumer last successfully read or claimed an entry.">
                {formatStreamActivity(row.inactiveMs)}
              </span>
            ),
          },
          {
            key: "idleMs",
            label: "Last attempt",
            render: (row) => (
              <span title="Time since the consumer last attempted XREADGROUP, XCLAIM, or XAUTOCLAIM.">
                {formatStreamActivity(row.idleMs)}
              </span>
            ),
          },
        ]}
        rows={rows}
        rowKey={(row) => row.name}
        emptyText="No consumers are registered for this group."
        loading={false}
      />
    </section>
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
    <section className="stream-subsection">
      <div className="stream-subsection-heading">
        <div>
          <h4>Pending deliveries</h4>
          <p>Messages delivered to a consumer but still waiting for ACK.</p>
        </div>
        <Button
          variant="ghost"
          icon={RefreshCw}
          onClick={() => query.refetch()}
          disabled={query.isFetching}
        >
          Refresh
        </Button>
      </div>
      {query.error ? <InlineNotice tone="danger">{query.error.message}</InlineNotice> : null}
      <DataTable
        columns={[
          { key: "id", label: "Pending ID", className: "mono" },
          { key: "consumer", label: "Consumer", className: "mono" },
          { key: "idleMs", label: "Unacked for", render: (row) => formatStreamDuration(row.idleMs) },
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
    </section>
  );
}

function GroupDetails({ topic, group }) {
  const activity = latestConsumerActivity(group);
  const errors = [
    ...(Array.isArray(topic.inspectionErrors) ? topic.inspectionErrors : []),
    ...(Array.isArray(group.inspectionErrors) ? group.inspectionErrors : []),
  ];
  return (
    <div className="stream-group-expanded">
      <div className="stream-group-facts">
        <div>
          <span>Entries read</span>
          <strong>{group.entriesRead == null ? "Unknown" : number(group.entriesRead)}</strong>
        </div>
        <div>
          <span>Pending range</span>
          <strong className="mono">
            {group.pendingMinId && group.pendingMaxId
              ? `${group.pendingMinId} – ${group.pendingMaxId}`
              : "-"}
          </strong>
        </div>
        <div>
          <span>Oldest pending</span>
          <strong>{formatStreamDuration(group.oldestPendingIdleMs)}</strong>
        </div>
        <div>
          <span>Last consumption</span>
          <strong>{formatStreamActivity(activity.milliseconds)}</strong>
          <small>
            {activity.source === "successful"
              ? "Last successful Redis consumer interaction"
              : activity.source === "legacy-idle"
                ? "Legacy Redis idle fallback"
                : "No successful delivery recorded"}
          </small>
        </div>
      </div>
      <InspectionErrors errors={errors} />
      <ConsumerList consumers={group.consumerDetails} />
      {Number(group.pending || 0) > 0 ? (
        <PendingList key={`${topic.topic}:${group.name}`} topic={topic.topic} group={group.name} />
      ) : (
        <div className="stream-empty-pending">No messages are waiting for ACK in this group.</div>
      )}
    </div>
  );
}

function StreamDetails({ topic, expandedGroups, onToggleGroup }) {
  const groups = Array.isArray(topic.groups) ? topic.groups : [];
  return (
    <div className="stream-expanded">
      <div className="stream-facts">
        <div>
          <span>Redis key</span>
          <strong className="mono">{topic.streamKey}</strong>
        </div>
        <div>
          <span>ID range</span>
          <strong className="mono">
            {topic.firstEntryId && topic.lastEntryId
              ? `${topic.firstEntryId} – ${topic.lastEntryId}`
              : "-"}
          </strong>
        </div>
        <div>
          <span>Latest entry</span>
          <strong>{formatStreamActivity(streamEntryAge(topic.lastEntryId))}</strong>
        </div>
        <div>
          <span>Retention</span>
          <strong>Exact MAXLEN {number(topic.maxLength)}</strong>
        </div>
      </div>
      <InspectionErrors errors={topic.inspectionErrors} />
      <div className="stream-group-heading">
        <div>
          <h3>Consumer groups</h3>
          <p>Expand a group to inspect workers and pending delivery ownership.</p>
        </div>
        <span>{number(groups.length)} groups</span>
      </div>
      <div className="table-frame">
        <table className="data-table stream-group-table">
          <thead>
            <tr>
              <th aria-label="Expand group" />
              <th>Consumer group</th>
              <th>State</th>
              <th>Offset</th>
              <th>Lag</th>
              <th>Pending</th>
              <th>Max retries</th>
              <th>Last consumed</th>
              <th>Consumers</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((group) => {
              const key = `${topic.topic}:${group.name}`;
              const expanded = expandedGroups.has(key);
              const state = streamGroupState({
                ...group,
                topicInspectionErrors: topic.inspectionErrors,
              });
              const activity = latestConsumerActivity(group);
              return [
                <tr
                  key={key}
                  className="stream-group-row"
                  data-expanded={expanded}
                  onClick={() => onToggleGroup(key)}
                >
                  <td>
                    <button
                      type="button"
                      className="stream-expand-button"
                      aria-expanded={expanded}
                      aria-label={`${expanded ? "Collapse" : "Expand"} ${group.name}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        onToggleGroup(key);
                      }}
                    >
                      {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                    </button>
                  </td>
                  <td className="mono"><strong>{group.name}</strong></td>
                  <td><StatusBadge tone={state.tone}>{state.label}</StatusBadge></td>
                  <td className="mono">{group.lastDeliveredId || "-"}</td>
                  <td>{group.lag == null ? "Unknown" : number(group.lag)}</td>
                  <td>{number(group.pending)}</td>
                  <td>
                    <span title={group.pendingSampleTruncated ? "Calculated from the first 100 pending deliveries." : ""}>
                      {number(group.maxRetryCount)}{group.pendingSampleTruncated ? "+" : ""}
                    </span>
                  </td>
                  <td>
                    <span title="Time since the most recent successful consumer interaction in this group.">
                      {formatStreamActivity(activity.milliseconds)}
                    </span>
                  </td>
                  <td>{number(group.consumers)}</td>
                </tr>,
                expanded ? (
                  <tr key={`${key}:details`} className="stream-group-detail-row">
                    <td colSpan={9}><GroupDetails topic={topic} group={group} /></td>
                  </tr>
                ) : null,
              ];
            })}
          </tbody>
        </table>
        {groups.length === 0 ? (
          <div className="table-state"><Database size={18} /> No consumer groups are registered.</div>
        ) : null}
      </div>
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
  const [expandedStreams, setExpandedStreams] = useState(new Set());
  const [expandedGroups, setExpandedGroups] = useState(new Set());
  const [search, setSearch] = useState("");
  const normalizedSearch = search.trim().toLowerCase();
  const filteredTopics = useMemo(
    () => topics.filter((topic) => (
      !normalizedSearch ||
      topic.topic.toLowerCase().includes(normalizedSearch) ||
      topic.streamKey.toLowerCase().includes(normalizedSearch) ||
      (topic.groups || []).some((group) =>
        group.name.toLowerCase().includes(normalizedSearch) ||
        (group.consumerDetails || []).some((consumer) =>
          consumer.name.toLowerCase().includes(normalizedSearch)))
    )),
    [normalizedSearch, topics],
  );
  const summary = useMemo(() => summarizeStreamHealth(topics), [topics]);

  function toggle(setter, key) {
    setter((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  return (
    <>
      <div className="stream-live-bar">
        <div>
          <Activity size={16} aria-hidden="true" />
          <span>Redis Stream operations</span>
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
        <span><strong>Lag</strong> not delivered to a group</span>
        <span><strong>Pending</strong> delivered but not ACKed</span>
        <span><strong>Last consumed</strong> most recent successful consumer interaction</span>
        <span><strong>Last attempt</strong> latest read or claim attempt, including empty reads</span>
      </div>
      {error ? <InlineNotice tone="danger">{error.message}</InlineNotice> : null}
      <section className="stream-browser">
        <div className="stream-section-heading stream-browser-toolbar">
          <div>
            <h2>Streams</h2>
            <p>Select a stream, then drill into its groups, consumers, lag, pending entries, and retries.</p>
          </div>
          <SearchField
            value={search}
            onChange={setSearch}
            onSubmit={() => {}}
            label="Stream topology search"
            placeholder="Stream, group, or consumer"
          />
        </div>
        <div className="table-frame">
          <table className="data-table stream-master-table">
            <thead>
              <tr>
                <th aria-label="Expand stream" />
                <th>Stream</th>
                <th>State</th>
                <th>Entries / MAXLEN</th>
                <th>Groups</th>
                <th>Total lag</th>
                <th>Pending</th>
                <th>Last consumed</th>
              </tr>
            </thead>
            <tbody>
              {!loading && filteredTopics.map((topic) => {
                const expanded = expandedStreams.has(topic.topic);
                const groups = Array.isArray(topic.groups) ? topic.groups : [];
                const state = streamOperationalState(topic);
                const activity = streamLatestConsumerActivity(topic);
                return [
                  <tr
                    key={topic.topic}
                    className="stream-master-row"
                    data-expanded={expanded}
                    onClick={() => toggle(setExpandedStreams, topic.topic)}
                  >
                    <td>
                      <button
                        type="button"
                        className="stream-expand-button"
                        aria-expanded={expanded}
                        aria-label={`${expanded ? "Collapse" : "Expand"} ${topic.topic}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          toggle(setExpandedStreams, topic.topic);
                        }}
                      >
                        {expanded ? <ChevronDown size={17} /> : <ChevronRight size={17} />}
                      </button>
                    </td>
                    <td>
                      <span className="primary-cell">
                        <strong>{topic.topic}</strong>
                        <span className="mono">{topic.streamKey}</span>
                      </span>
                    </td>
                    <td><StatusBadge tone={state.tone}>{state.label}</StatusBadge></td>
                    <td><Retention topic={topic} /></td>
                    <td>{number(groups.length)}</td>
                    <td>{number(totalFor(groups, "lag"))}</td>
                    <td>{number(totalFor(groups, "pending"))}</td>
                    <td>
                      <span title="Time since the most recent successful consumer interaction for this stream.">
                        {formatStreamActivity(activity.milliseconds)}
                      </span>
                    </td>
                  </tr>,
                  expanded ? (
                    <tr key={`${topic.topic}:details`} className="stream-master-detail-row">
                      <td colSpan={8}>
                        <StreamDetails
                          topic={topic}
                          expandedGroups={expandedGroups}
                          onToggleGroup={(key) => toggle(setExpandedGroups, key)}
                        />
                      </td>
                    </tr>
                  ) : null,
                ];
              })}
            </tbody>
          </table>
          {loading ? (
            <div className="table-state"><RefreshCw className="spin" size={18} /> Loading stream topology...</div>
          ) : null}
          {!loading && filteredTopics.length === 0 ? (
            <div className="table-state"><Database size={18} /> No matching Redis streams were found.</div>
          ) : null}
        </div>
      </section>
    </>
  );
}
