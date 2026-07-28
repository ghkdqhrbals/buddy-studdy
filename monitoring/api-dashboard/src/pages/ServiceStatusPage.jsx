import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock, Power, RefreshCw, XCircle } from "lucide-react";
import { useMemo, useState } from "react";
import { monitoringStatusFetch } from "../admin/adminApi.js";
import {
  DataTable,
  PageHeader,
  Pagination,
  SegmentedTabs,
  StatusBadge,
} from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { formatDateTime } from "../lib/format.js";

const PAGE_SIZE = 20;

function localInputValue(date) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function stateFor(window, now = Date.now()) {
  const startsAt = Date.parse(window.startsAt);
  const endsAt = window.endsAt ? Date.parse(window.endsAt) : null;
  const terminatedAt = window.terminatedAt ? Date.parse(window.terminatedAt) : null;
  if (terminatedAt && terminatedAt < startsAt) return "CANCELLED";
  if (terminatedAt) return "COMPLETED";
  if (startsAt > now) return "SCHEDULED";
  if (endsAt && endsAt <= now) return "COMPLETED";
  return "ACTIVE";
}

function stateTone(state) {
  if (state === "ACTIVE") return "danger";
  if (state === "SCHEDULED") return "warning";
  if (state === "COMPLETED") return "success";
  return "neutral";
}

function MaintenanceForm({ onSaved }) {
  const [mode, setMode] = useState("now");
  const [startsAt, setStartsAt] = useState(() => localInputValue(new Date(Date.now() + 10 * 60_000)));
  const [endsAt, setEndsAt] = useState("");
  const [content, setContent] = useState({
    titleKo: "서비스 점검 중입니다",
    titleEn: "Service maintenance",
    titleJa: "サービスメンテナンス中です",
    messageKo: "더 안정적인 서비스를 위해 점검을 진행하고 있습니다. 잠시 후 다시 확인해 주세요.",
    messageEn: "BuddyStudy is undergoing maintenance for improved reliability. Please try again shortly.",
    messageJa: "より安定したサービスのため、メンテナンスを実施しています。しばらくしてからもう一度お試しください。",
  });
  const mutation = useMutation({
    mutationFn: () => {
      const start = mode === "now" ? new Date() : new Date(startsAt);
      const end = endsAt ? new Date(endsAt) : null;
      return monitoringStatusFetch("/service-maintenance", {
        method: "POST",
        body: JSON.stringify({
          ...content,
          startsAt: start.toISOString(),
          endsAt: end?.toISOString() || null,
        }),
      });
    },
    onSuccess: (created) => {
      onSaved(created);
      setEndsAt("");
      if (mode === "schedule") {
        setStartsAt(localInputValue(new Date(Date.now() + 10 * 60_000)));
      }
    },
  });

  const update = (key, value) => setContent((current) => ({ ...current, [key]: value }));

  return (
    <section className="workspace-section maintenance-form-section">
      <div className="section-heading mode-heading">
        <div>
          <h2>Maintenance window</h2>
          <p>Start immediately or schedule a future full-service maintenance window.</p>
        </div>
        <SegmentedTabs
          value={mode}
          onChange={setMode}
          ariaLabel="Maintenance start mode"
          items={[
            { value: "now", label: "Start now" },
            { value: "schedule", label: "Schedule" },
          ]}
        />
      </div>
      <div className="maintenance-form-body">
        <div className="form-grid">
          {mode === "schedule" ? (
            <label className="field">
              <span>Starts at</span>
              <input type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} required />
            </label>
          ) : (
            <div className="maintenance-now-note">
              <Power size={18} />
              <div><strong>Immediate activation</strong><span>Customer apps show the maintenance screen as soon as they refresh this status.</span></div>
            </div>
          )}
          <label className="field">
            <span>Ends at (optional)</span>
            <input type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} />
          </label>
        </div>

        <div className="maintenance-language-grid">
          {[
            ["Korean", "titleKo", "messageKo"],
            ["English", "titleEn", "messageEn"],
            ["Japanese", "titleJa", "messageJa"],
          ].map(([label, titleKey, messageKey]) => (
            <fieldset className="maintenance-language" key={label}>
              <legend>{label}</legend>
              <label className="field">
                <span>Title</span>
                <input maxLength={120} value={content[titleKey]} onChange={(event) => update(titleKey, event.target.value)} required />
              </label>
              <label className="field">
                <span>Message</span>
                <textarea maxLength={1000} rows={4} value={content[messageKey]} onChange={(event) => update(messageKey, event.target.value)} required />
              </label>
            </fieldset>
          ))}
        </div>
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        <div className="maintenance-form-actions">
          <Button
            icon={mode === "now" ? Power : CalendarClock}
            busy={mutation.isPending}
            onClick={() => mutation.mutate()}
            disabled={
              (mode === "schedule" && !startsAt) ||
              Object.values(content).some((value) => !value.trim())
            }
          >
            {mode === "now" ? "Start maintenance" : "Schedule maintenance"}
          </Button>
        </div>
      </div>
    </section>
  );
}

function ActiveWindows({ overview, onChanged }) {
  const windows = [overview?.current, ...(overview?.upcoming || [])].filter(Boolean);
  const mutation = useMutation({
    mutationFn: (item) => monitoringStatusFetch(`/service-maintenance/${item.id}/terminate`, { method: "POST" }),
    onSuccess: onChanged,
  });

  if (windows.length === 0) {
    return (
      <section className="workspace-section">
        <div className="maintenance-operational">
          <span className="operational-dot" />
          <div><h2>Service operational</h2><p>No active or scheduled maintenance windows.</p></div>
        </div>
      </section>
    );
  }

  return (
    <section className="workspace-section">
      <div className="section-heading">
        <div><h2>Active and scheduled</h2><p>Customer apps read this status directly from monitoring.</p></div>
      </div>
      {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
      <div className="maintenance-window-list">
        {windows.map((item) => {
          const state = stateFor(item);
          return (
            <article className="maintenance-window-row" key={item.id}>
              <div>
                <StatusBadge tone={stateTone(state)}>{state}</StatusBadge>
                <strong>{item.content.titleKo}</strong>
                <span>{formatDateTime(item.startsAt)} – {item.endsAt ? formatDateTime(item.endsAt) : "Until manually ended"}</span>
              </div>
              <Button
                variant="secondary"
                icon={state === "ACTIVE" ? Power : XCircle}
                busy={mutation.isPending && mutation.variables?.id === item.id}
                onClick={() => {
                  const action = state === "ACTIVE" ? "End this maintenance window now?" : "Cancel this scheduled maintenance window?";
                  if (globalThis.confirm(action)) mutation.mutate(item);
                }}
              >
                {state === "ACTIVE" ? "End maintenance" : "Cancel schedule"}
              </Button>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function ServiceStatusWorkspace() {
  const queryClient = useQueryClient();
  const [offset, setOffset] = useState(0);
  const overviewQuery = useQuery({
    queryKey: ["admin", "service-maintenance", "overview"],
    queryFn: () => monitoringStatusFetch("/service-maintenance"),
    refetchInterval: 10_000,
  });
  const historyQuery = useQuery({
    queryKey: ["admin", "service-maintenance", "history", offset],
    queryFn: () => monitoringStatusFetch(`/service-maintenance/history?limit=${PAGE_SIZE}&offset=${offset}`),
    refetchInterval: 10_000,
  });
  const items = Array.isArray(historyQuery.data?.items) ? historyQuery.data.items : [];
  const total = Number(historyQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    { key: "id", label: "ID", className: "mono" },
    { key: "state", label: "State", render: (row) => {
      const state = stateFor(row);
      return <StatusBadge tone={stateTone(state)}>{state}</StatusBadge>;
    } },
    { key: "title", label: "Title", render: (row) => <div className="primary-cell"><strong>{row.content.titleKo}</strong><span>{row.content.titleEn}</span></div> },
    { key: "startsAt", label: "Start", render: (row) => formatDateTime(row.startsAt) },
    { key: "endsAt", label: "Planned end", render: (row) => row.endsAt ? formatDateTime(row.endsAt) : "Manual" },
    { key: "terminatedAt", label: "Actual end", render: (row) => row.terminatedAt ? formatDateTime(row.terminatedAt) : "-" },
    { key: "createdBy", label: "Created by" },
  ], []);

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["admin", "service-maintenance"] });
  }

  return (
    <>
      {(overviewQuery.error || historyQuery.error) ? (
        <InlineNotice tone="danger">{overviewQuery.error?.message || historyQuery.error?.message}</InlineNotice>
      ) : null}
      <ActiveWindows overview={overviewQuery.data} onChanged={refresh} />
      <MaintenanceForm onSaved={refresh} />
      <section className="workspace-section">
        <div className="section-heading">
          <div><h2>Maintenance history</h2><p>Scheduled, completed, and cancelled windows are retained for audit.</p></div>
        </div>
        <DataTable columns={columns} rows={items} rowKey={(row) => row.id} emptyText="No maintenance history." loading={historyQuery.isLoading} />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={`${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}`}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>
    </>
  );
}

export function ServiceStatusPage() {
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Service status"
        description="Publish customer-facing maintenance status from monitoring and review its history."
        actions={
          <Button variant="secondary" icon={RefreshCw} onClick={() => window.location.reload()}>
            Refresh
          </Button>
        }
      />
      <ServiceStatusWorkspace />
    </>
  );
}
