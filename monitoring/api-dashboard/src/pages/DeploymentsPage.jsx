import {
  keepPreviousData,
  useIsFetching,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { ExternalLink, RefreshCw } from "lucide-react";
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
import { deploymentFetch } from "../lib/deploymentApi.js";
import { formatDateTime, formatDuration, statusTone } from "../lib/format.js";

const PAGE_SIZE = 20;
const ACTIVE_STATUSES = new Set(["QUEUED", "RUNNING"]);

function durationFor(deployment) {
  if (deployment.durationMs != null) return deployment.durationMs;
  if (!deployment.startedAt) return null;
  const end = deployment.finishedAt ? Date.parse(deployment.finishedAt) : Date.now();
  const start = Date.parse(deployment.startedAt);
  return Number.isFinite(start) && Number.isFinite(end) ? Math.max(0, end - start) : null;
}

function shortImage(image) {
  if (!image) return "-";
  const [repository, tag] = image.split(":");
  const name = repository.split("/").at(-1);
  return tag ? `${name}:${tag.slice(0, 12)}` : name;
}

function commitLabel(deployment) {
  return deployment.sourceSha?.slice(0, 8) || "manual";
}

function DeploymentsWorkspace() {
  const [offset, setOffset] = useState(0);
  const [service, setService] = useState("");
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState(null);
  const params = new URLSearchParams({
    limit: String(PAGE_SIZE),
    offset: String(offset),
  });
  if (service) params.set("service", service);
  if (status) params.set("status", status);

  const deploymentsQuery = useQuery({
    queryKey: ["deployments", service, status, offset],
    queryFn: () => deploymentFetch(`/deployments?${params}`),
    refetchInterval: 10_000,
    placeholderData: keepPreviousData,
  });
  const deployments = Array.isArray(deploymentsQuery.data?.items)
    ? deploymentsQuery.data.items
    : [];
  const summary = deploymentsQuery.data?.summary || {};
  const total = Number(deploymentsQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const current = summary.current || null;

  const columns = useMemo(() => [
    {
      key: "startedAt",
      label: "Started",
      render: (deployment) => formatDateTime(deployment.startedAt),
    },
    {
      key: "service",
      label: "Service",
      render: (deployment) => (
        <div className="primary-cell deployment-service-cell">
          <strong>{deployment.service}</strong>
          <span>{deployment.environment}</span>
        </div>
      ),
    },
    {
      key: "source",
      label: "Source",
      render: (deployment) => (
        <div className="primary-cell">
          <strong className="mono">{commitLabel(deployment)}</strong>
          <span>{deployment.actor || "-"}</span>
        </div>
      ),
    },
    {
      key: "artifact",
      label: "Artifact",
      render: (deployment) => (
        <div className="primary-cell">
          <strong>{shortImage(deployment.image)}</strong>
          <span>{deployment.runtime || "runtime not reported"}</span>
        </div>
      ),
    },
    {
      key: "phase",
      label: "Phase",
      render: (deployment) => deployment.phase || "-",
    },
    {
      key: "status",
      label: "Status",
      render: (deployment) => (
        <StatusBadge tone={statusTone(deployment.status)}>{deployment.status}</StatusBadge>
      ),
    },
    {
      key: "duration",
      label: "Duration",
      render: (deployment) => formatDuration(durationFor(deployment)),
    },
  ], []);

  const error = deploymentsQuery.error;
  return (
    <>
      {error ? <InlineNotice tone="danger">{error.message}</InlineNotice> : null}
      <div className="metric-strip deployment-metric-strip">
        <div><span>Active</span><strong>{Number(summary.activeCount) || 0}</strong></div>
        <div><span>Succeeded · 24h</span><strong>{Number(summary.succeeded24h) || 0}</strong></div>
        <div><span>Failed · 24h</span><strong>{Number(summary.failed24h) || 0}</strong></div>
        <div><span>Latest artifact</span><strong title={current?.image || ""}>{shortImage(current?.image)}</strong></div>
      </div>

      {current ? (
        <section className="workspace-section deployment-current">
          <div className="section-heading">
            <div>
              <h2>{ACTIVE_STATUSES.has(current.status) ? "Current deployment" : "Latest deployment"}</h2>
              <p>{current.message || `${current.service} · ${commitLabel(current)} · ${formatDateTime(current.startedAt)}`}</p>
            </div>
            <StatusBadge tone={statusTone(current.status)}>{current.status}</StatusBadge>
          </div>
          <div className="detail-summary">
            <div><span>Service</span><strong>{current.service}</strong></div>
            <div><span>Phase</span><strong>{current.phase || "-"}</strong></div>
            <div><span>Runtime</span><strong>{current.runtime || "-"}</strong></div>
            <div><span>Elapsed</span><strong>{formatDuration(durationFor(current))}</strong></div>
          </div>
        </section>
      ) : null}

      <section className="workspace-section">
        <div className="section-heading deployment-history-heading">
          <div>
            <h2>Deployment history</h2>
            <p>Workflow state is updated from GitHub Actions. Select a row for source and rollout details.</p>
          </div>
          <div className="inline-controls">
            <label className="field compact-field">
              <span>Service</span>
              <select value={service} onChange={(event) => { setService(event.target.value); setOffset(0); }}>
                <option value="">All services</option>
                <option value="backend">Backend</option>
                <option value="monitoring">Monitoring</option>
                <option value="testzone">TestZone</option>
              </select>
            </label>
            <label className="field compact-field">
              <span>Status</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setOffset(0); }}>
                <option value="">All statuses</option>
                <option value="RUNNING">Running</option>
                <option value="SUCCEEDED">Succeeded</option>
                <option value="FAILED">Failed</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </label>
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={deployments}
          rowKey={(deployment) => deployment.id}
          onRowClick={setSelected}
          emptyText="No deployment events have been recorded."
          loading={deploymentsQuery.isLoading}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}` : "0 deployments"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>

      <DetailDrawer
        open={Boolean(selected)}
        title={selected ? `${selected.service} deployment` : ""}
        subtitle={selected ? selected.id : ""}
        onClose={() => setSelected(null)}
      >
        {selected ? (
          <>
            <div className="detail-summary">
              <div><span>Status</span><strong><StatusBadge tone={statusTone(selected.status)}>{selected.status}</StatusBadge></strong></div>
              <div><span>Started</span><strong>{formatDateTime(selected.startedAt)}</strong></div>
              <div><span>Finished</span><strong>{formatDateTime(selected.finishedAt)}</strong></div>
              <div><span>Duration</span><strong>{formatDuration(durationFor(selected))}</strong></div>
            </div>
            <section className="drawer-section deployment-detail-section">
              <h3>Rollout</h3>
              <dl>
                <div><dt>Phase</dt><dd>{selected.phase || "-"}</dd></div>
                <div><dt>Image</dt><dd className="mono">{selected.image || "-"}</dd></div>
                <div><dt>Runtime</dt><dd>{selected.runtime || "-"}</dd></div>
                <div><dt>Source</dt><dd>{selected.sourceRepository || "-"} · {commitLabel(selected)}</dd></div>
                <div><dt>Actor</dt><dd>{selected.actor || "-"}</dd></div>
                <div><dt>Message</dt><dd>{selected.message || "-"}</dd></div>
              </dl>
              {selected.deployUrl ? (
                <a className="button button-secondary deployment-run-link" href={selected.deployUrl} target="_blank" rel="noreferrer">
                  <ExternalLink size={15} />
                  Open GitHub Actions
                </a>
              ) : null}
            </section>
            <ObjectInspector value={selected} title="Deployment event" />
          </>
        ) : null}
      </DetailDrawer>
    </>
  );
}

export function DeploymentsPage() {
  const queryClient = useQueryClient();
  const refreshing = useIsFetching({ queryKey: ["deployments"] }) > 0;
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Deployments"
        description="Track backend and operations rollouts from submission through their final workflow state."
        actions={(
          <Button
            variant="secondary"
            icon={RefreshCw}
            busy={refreshing}
            onClick={() => queryClient.invalidateQueries({ queryKey: ["deployments"] })}
          >
            Refresh
          </Button>
        )}
      />
      <DeploymentsWorkspace />
    </>
  );
}
