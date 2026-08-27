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

const STATUS_PAGE_SIZE = 10;
const RUN_PAGE_SIZE = 20;

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
  const [selectedJob, setSelectedJob] = useState(null);
  const [statusOffset, setStatusOffset] = useState(0);
  const [runCursors, setRunCursors] = useState([null]);
  const [selectedRun, setSelectedRun] = useState(null);
  const jobName = selectedJob?.jobName || "";
  const runCursor = runCursors[runCursors.length - 1];

  const statusesQuery = useQuery({
    queryKey: ["admin", "jobs", "statuses", statusOffset],
    queryFn: () => {
      const params = new URLSearchParams({
        limit: String(STATUS_PAGE_SIZE),
        offset: String(statusOffset),
      });
      return adminFetch(`/jobs/statuses?${params}`);
    },
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
  });
  const runsQuery = useQuery({
    queryKey: ["admin", "jobs", "runs", jobName, runCursor],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(RUN_PAGE_SIZE) });
      if (jobName) params.set("jobName", jobName);
      if (runCursor != null) params.set("cursor", String(runCursor));
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

  const statusJobs = Array.isArray(statusesQuery.data?.jobs) ? statusesQuery.data.jobs : [];
  const statusPageTransitioning = statusesQuery.isPlaceholderData;
  const visibleJobs = statusPageTransitioning ? [] : statusJobs;
  const totalJobs = Number(statusesQuery.data?.totalCount) || statusJobs.length;
  const statusPage = Math.floor(statusOffset / STATUS_PAGE_SIZE) + 1;
  const totalStatusPages = Math.max(1, Math.ceil(totalJobs / STATUS_PAGE_SIZE));
  const runs = Array.isArray(runsQuery.data?.runs) ? runsQuery.data.runs : [];
  const runPageTransitioning = runsQuery.isPlaceholderData;
  const visibleRuns = runPageTransitioning ? [] : runs;
  const page = runCursors.length;
  const hasNextRunPage = Boolean(runsQuery.data?.hasNext && runsQuery.data?.nextCursor);
  const jobsByName = useMemo(() => new Map(statusJobs.map((job) => [job.jobName, job])), [statusJobs]);
  const attentionCount = visibleJobs.filter((job) => job.enabled && (job.stale || job.stuck || job.latestRun?.status === "FAILED")).length;
  const healthyCount = visibleJobs.filter((job) => job.enabled && !job.stale && !job.stuck && job.latestRun?.status === "SUCCESS").length;

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
      render: (run) => run.displayName
        || (selectedJob?.jobName === run.jobName ? selectedJob.displayName : null)
        || jobsByName.get(run.jobName)?.displayName
        || run.jobName,
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
  ], [jobsByName, selectedJob]);

  function selectJob(job) {
    setSelectedJob(job);
    setRunCursors([null]);
    setSelectedRun(null);
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
        <div><span>Registered jobs</span><strong>{totalJobs}</strong></div>
        <div><span>Healthy on page</span><strong>{statusPageTransitioning ? "…" : healthyCount}</strong></div>
        <div><span>Attention on page</span><strong>{statusPageTransitioning ? "…" : attentionCount}</strong></div>
        <div><span>Monitored on page</span><strong>{statusPageTransitioning ? "…" : visibleJobs.filter((job) => job.monitored).length}</strong></div>
      </div>

      <section className="workspace-section">
        <div className="section-heading">
          <div>
            <h2>Job status</h2>
            <p>Select a job to filter its execution history. Monitoring applies only to frequent critical jobs.</p>
          </div>
          {jobName ? <Button variant="ghost" onClick={() => { setSelectedJob(null); setRunCursors([null]); setSelectedRun(null); }}>Show all runs</Button> : null}
        </div>
        <DataTable
          columns={jobColumns}
          rows={visibleJobs}
          rowKey={(job) => job.jobName}
          onRowClick={selectJob}
          emptyText="No batch jobs are registered."
          loading={statusesQuery.isLoading || statusPageTransitioning}
        />
        <Pagination
          ariaLabel="Job status pagination"
          page={statusPage}
          totalPages={totalStatusPages}
          label={statusPageTransitioning
            ? `Loading job status page ${statusPage}…`
            : totalJobs
              ? `${Math.min(statusOffset + 1, totalJobs)}–${Math.min(statusOffset + visibleJobs.length, totalJobs)} of ${totalJobs} jobs`
              : "0 jobs"}
          fetching={statusesQuery.isFetching}
          onPrevious={() => setStatusOffset(Math.max(0, statusOffset - STATUS_PAGE_SIZE))}
          onNext={() => setStatusOffset(statusOffset + STATUS_PAGE_SIZE)}
        />
      </section>

      <section className="workspace-section">
        <div className="section-heading">
          <div>
            <h2>{selectedJob ? `${selectedJob.displayName || selectedJob.jobName} history` : "Execution history"}</h2>
            <p>Newest runs first. Open a row to inspect timing, result, error, and retry lineage.</p>
          </div>
        </div>
        <DataTable
          columns={runColumns}
          rows={visibleRuns}
          rowKey={(run) => run.id}
          onRowClick={setSelectedRun}
          emptyText="No job runs found."
          loading={runsQuery.isLoading || runPageTransitioning}
        />
        <Pagination
          ariaLabel="Execution history pagination"
          page={page}
          hasNext={hasNextRunPage}
          label={runPageTransitioning
            ? `Loading execution history page ${page}…`
            : visibleRuns.length
              ? `${visibleRuns.length} runs on this page`
              : "0 runs"}
          fetching={runsQuery.isFetching}
          onPrevious={() => setRunCursors((current) => current.length > 1 ? current.slice(0, -1) : current)}
          onNext={() => {
            const nextCursor = runsQuery.data?.nextCursor;
            if (nextCursor != null) setRunCursors((current) => [...current, nextCursor]);
          }}
        />
      </section>

      <DetailDrawer
        open={Boolean(selectedRun)}
        title={selectedRun ? `Run #${selectedRun.id}` : ""}
        subtitle={selectedRun
          ? selectedRun.displayName
            || (selectedJob?.jobName === selectedRun.jobName ? selectedJob.displayName : null)
            || jobsByName.get(selectedRun.jobName)?.displayName
            || selectedRun.jobName
          : ""}
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
