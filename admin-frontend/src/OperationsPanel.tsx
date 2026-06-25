import { EmptyState } from "./EmptyState";
import { formatDateTime, formatDurationMs, statusLabel } from "./format";
import { Pagination } from "./Pagination";
import type { ScheduledJobRun, ScheduledJobRunsResponse } from "./types";

type OperationsPanelProps = {
  page: ScheduledJobRunsResponse;
  onRetry: (job: ScheduledJobRun) => void;
  hrefForPage: (page: number) => string;
  onPageChange?: (page: number) => void;
  compact?: boolean;
};

export function OperationsPanel({
  page,
  onRetry,
  hrefForPage,
  onPageChange,
  compact = false,
}: OperationsPanelProps) {
  const jobs = page.runs;
  if (jobs.length === 0) {
    return <EmptyState title="No job runs" message="Scheduled job history will appear here." compact={compact} />;
  }
  const start = page.offset + 1;
  const end = Math.min(page.offset + jobs.length, page.totalCount);
  const currentPage = Math.floor(page.offset / page.limit) + 1;
  const totalPages = Math.max(1, Math.ceil(page.totalCount / page.limit));
  const showActions = !compact && jobs.some((job) => job.status === "FAILED");

  return (
    <section className={compact ? "operations-panel compact-panel" : "operations-panel"}>
      <div className="panel-header">
        <h2>Scheduler runs</h2>
        {compact ? (
          <a className="panel-link" href={hrefForPage(1)}>View all</a>
        ) : (
          <span>{start}-{end} of {page.totalCount}</span>
        )}
      </div>
      <div className="table-wrap horizontal-scroll">
        <table>
          <thead>
            <tr>
              <th className="job-col">Job</th>
              <th className="time-col">Last run</th>
              <th className="status-col">Status</th>
              <th className="result-col">Result</th>
              <th className="duration-col">Duration</th>
              <th className="retry-col">Retry of</th>
              {showActions ? <th className="action-col">Action</th> : null}
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td>
                  <strong>{job.jobName}</strong>
                  {job.errorMessage ? <small>{job.errorMessage}</small> : null}
                </td>
                <td>{formatDateTime(job.startedAt)}</td>
                <td><span className={`status ${job.status.toLowerCase()}`}>{statusLabel(job.status)}</span></td>
                <td className="result-cell" title={job.summary ?? job.status}>{job.summary ?? job.status}</td>
                <td>{formatDurationMs(job.durationMs)}</td>
                <td>{job.retryOfRunId ? `#${job.retryOfRunId}` : "-"}</td>
                {showActions ? (
                  <td className="action-cell">
                    {job.status === "FAILED" ? (
                      <button className="secondary-button compact" onClick={() => onRetry(job)}>Retry</button>
                    ) : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {onPageChange ? (
        <Pagination
          start={start}
          end={end}
          totalCount={page.totalCount}
          currentPage={currentPage}
          totalPages={totalPages}
          pageSize={page.limit}
          hrefForPage={hrefForPage}
          onPageChange={onPageChange}
        />
      ) : null}
    </section>
  );
}
