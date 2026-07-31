import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { RefreshCw, RotateCcw } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
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
import { formatDateTime, formatDuration, statusTone } from "../lib/format.js";

const PAGE_SIZE = 20;

function scheduleLabel(job) {
  if (job.scheduleType === "CRON") return `${job.scheduleValue} · server time`;
  if (job.scheduleType === "FIXED_DELAY") return `Every ${job.scheduleValue} after completion`;
  return job.scheduleValue || job.scheduleType || "-";
}

function effectiveStatus(job) {
  if (!job.enabled) return "DISABLED";
  if (job.stuck) return "STUCK";
  if (job.stale) return "STALE";
  return job.latestRun?.status || "NOT_RUN";
}

function JobsWorkspace() {
  const queryClient = useQueryClient();
  const [jobName, setJobName] = useState("");
  const [offset, setOffset] = useState(0);
  const [selectedRun, setSelectedRun] = useState(null);

  const statusesQuery = useQuery({
    queryKey: ["admin", "jobs", "statuses"],
    queryFn: () => adminFetch("/jobs/statuses"),
    refetchInterval: 30_000,
  });
  const runsQuery = useQuery({
    queryKey: ["admin", "jobs", "runs", jobName, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (jobName) params.set("jobName", jobName);
      return adminFetch(`/jobs/runs?${params}`);
    },
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
  });
  const retryMutation = useMutation({
    mutationFn: (run) => adminFetch(
      `/jobs/${encodeURIComponent(run.jobName)}/retry?runId=${encodeURIComponent(run.id)}`,
      { method: "POST" },
    ),
    onSuccess: (run) => {
      setSelectedRun(run);
      queryClient.invalidateQueries({ queryKey: ["admin", "jobs"] });
    },
  });

  const jobs = Array.isArray(statusesQuery.data?.jobs) ? statusesQuery.data.jobs : [];
  const runs = Array.isArray(runsQuery.data?.runs) ? runsQuery.data.runs : [];
  const total = Number(runsQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const jobsByName = useMemo(() => new Map(jobs.map((job) => [job.jobName, job])), [jobs]);
  const attentionCount = jobs.filter((job) => job.enabled && (job.stale || job.stuck || job.latestRun?.status === "FAILED")).length;
  const healthyCount = jobs.filter((job) => job.enabled && !job.stale && !job.stuck && job.latestRun?.status === "SUCCESS").length;

  const jobColumns = useMemo(() => [
    {
      key: "job",
      label: "Job",
      render: (job) => (
        <div className="primary-cell job-name-cell">
          <strong>{job.displayName || job.jobName}</strong>
          <span>{job.description || job.jobName}</span>
        </div>
      ),
    },
    {
      key: "status",
      label: "Status",
      render: (job) => {
        const status = effectiveStatus(job);
        return <StatusBadge tone={statusTone(status)}>{status}</StatusBadge>;
      },
    },
    { key: "schedule", label: "Schedule", render: scheduleLabel },
    {
      key: "latestResult",
      label: "Latest result",
      render: (job) => job.latestRun?.summary || job.latestRun?.errorMessage || "-",
    },
    {
      key: "lastStarted",
      label: "Last started",
      render: (job) => formatDateTime(job.latestRun?.startedAt),
    },
    {
      key: "duration",
      label: "Duration",
      render: (job) => formatDuration(job.latestRun?.durationMs),
    },
    {
      key: "lastSuccess",
      label: "Last success",
      render: (job) => formatDateTime(job.lastSuccessfulRun?.finishedAt || job.lastSuccessfulRun?.startedAt),
    },
  ], []);

  const runColumns = useMemo(() => [
    { key: "id", label: "Run ID", className: "mono" },
    {
      key: "jobName",
      label: "Job",
      render: (run) => jobsByName.get(run.jobName)?.displayName || run.jobName,
    },
    {
      key: "status",
      label: "Status",
      render: (run) => <StatusBadge tone={statusTone(run.status)}>{run.status}</StatusBadge>,
    },
    { key: "triggerType", label: "Trigger" },
    { key: "startedAt", label: "Started", render: (run) => formatDateTime(run.startedAt) },
    { key: "durationMs", label: "Duration", render: (run) => formatDuration(run.durationMs) },
    { key: "result", label: "Result", render: (run) => run.summary || run.errorMessage || "-" },
    { key: "createdBy", label: "Started by" },
  ], [jobsByName]);

  function selectJob(job) {
    setJobName(job.jobName);
    setOffset(0);
  }

  function retry(run) {
    if (run.status === "RUNNING") return;
    if (window.confirm(`Retry ${run.jobName} from run #${run.id}?`)) retryMutation.mutate(run);
  }

  const error = statusesQuery.error || runsQuery.error;
  return (
    <>
      {error ? <InlineNotice tone="danger">{error.message}</InlineNotice> : null}
      <div className="metric-strip batch-metric-strip">
        <div><span>Registered jobs</span><strong>{jobs.length}</strong></div>
        <div><span>Healthy</span><strong>{healthyCount}</strong></div>
        <div><span>Needs attention</span><strong>{attentionCount}</strong></div>
        <div><span>Monitored</span><strong>{jobs.filter((job) => job.monitored).length}</strong></div>
      </div>

      <section className="workspace-section">
        <div className="section-heading">
          <div>
            <h2>Job status</h2>
            <p>Select a job to filter its execution history. Monitoring applies only to frequent critical jobs.</p>
          </div>
          {jobName ? <Button variant="ghost" onClick={() => { setJobName(""); setOffset(0); }}>Show all runs</Button> : null}
        </div>
        <DataTable
          columns={jobColumns}
          rows={jobs}
          rowKey={(job) => job.jobName}
          onRowClick={selectJob}
          emptyText="No batch jobs are registered."
          loading={statusesQuery.isLoading}
        />
      </section>

      <section className="workspace-section">
        <div className="section-heading">
          <div>
            <h2>{jobName ? `${jobsByName.get(jobName)?.displayName || jobName} history` : "Execution history"}</h2>
            <p>Newest runs first. Open a row to inspect timing, result, error, and retry lineage.</p>
          </div>
        </div>
        <DataTable
          columns={runColumns}
          rows={runs}
          rowKey={(run) => run.id}
          onRowClick={setSelectedRun}
          emptyText="No job runs found."
          loading={runsQuery.isLoading}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}` : "0 runs"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>

      <DetailDrawer
        open={Boolean(selectedRun)}
        title={selectedRun ? `Run #${selectedRun.id}` : ""}
        subtitle={selectedRun ? jobsByName.get(selectedRun.jobName)?.displayName || selectedRun.jobName : ""}
        onClose={() => setSelectedRun(null)}
      >
        {selectedRun ? (
          <>
            <div className="detail-summary">
              <div><span>Status</span><strong><StatusBadge tone={statusTone(selectedRun.status)}>{selectedRun.status}</StatusBadge></strong></div>
              <div><span>Started</span><strong>{formatDateTime(selectedRun.startedAt)}</strong></div>
              <div><span>Finished</span><strong>{formatDateTime(selectedRun.finishedAt)}</strong></div>
              <div><span>Duration</span><strong>{formatDuration(selectedRun.durationMs)}</strong></div>
            </div>
            <section className="drawer-section job-result-section">
              <h3>Execution result</h3>
              <dl>
                <div><dt>Trigger</dt><dd>{selectedRun.triggerType}</dd></div>
                <div><dt>Started by</dt><dd>{selectedRun.createdBy}</dd></div>
                <div><dt>Retry of run</dt><dd>{selectedRun.retryOfRunId ? `#${selectedRun.retryOfRunId}` : "-"}</dd></div>
                <div><dt>Summary</dt><dd>{selectedRun.summary || "-"}</dd></div>
                <div><dt>Error</dt><dd data-error={selectedRun.errorMessage ? "true" : "false"}>{selectedRun.errorMessage || "-"}</dd></div>
              </dl>
              <div className="drawer-form-actions">
                {retryMutation.error ? <InlineNotice tone="danger" compact>{retryMutation.error.message}</InlineNotice> : null}
                <Button
                  variant="secondary"
                  icon={RotateCcw}
                  busy={retryMutation.isPending}
                  disabled={selectedRun.status === "RUNNING"}
                  onClick={() => retry(selectedRun)}
                >
                  Retry job
                </Button>
              </div>
            </section>
            <ObjectInspector value={selectedRun} title="Run object" />
          </>
        ) : null}
      </DetailDrawer>
    </>
  );
}

export function JobsPage() {
  const queryClient = useQueryClient();
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Batch jobs"
        description="Review what each scheduled job does, when it ran, how long it took, and what it produced."
        actions={(
          <Button
            variant="secondary"
            icon={RefreshCw}
            onClick={() => queryClient.invalidateQueries({ queryKey: ["admin", "jobs"] })}
          >
            Refresh
          </Button>
        )}
      />
      <JobsWorkspace />
    </>
  );
}
