export type AdminLoginResponse = {
  adminToken: string;
  expiresAt: string;
};

export type AdminDailyMetricPoint = {
  date: string;
  metricKey: string;
  dimension?: string | null;
  value: number;
  sampleCount: number;
};

export type AdminMetricSeries = {
  metricKey: string;
  dimension?: string | null;
  points: AdminDailyMetricPoint[];
};

export type AdminMetricsResponse = {
  startDate: string;
  endDate: string;
  series: AdminMetricSeries[];
};

export type JobTriggerType = "SCHEDULED" | "MANUAL" | "RETRY";
export type JobRunStatus = "RUNNING" | "SUCCESS" | "FAILED" | "SKIPPED";

export type ScheduledJobRun = {
  id: number;
  jobName: string;
  triggerType: JobTriggerType;
  status: JobRunStatus;
  startedAt: string;
  finishedAt?: string | null;
  durationMs?: number | null;
  summary?: string | null;
  errorMessage?: string | null;
  retryOfRunId?: number | null;
  createdBy: string;
};

export type AdminApiError = {
  error?: {
    code?: string;
    message?: string;
    status?: number;
    requestId?: string;
    reason?: string;
  };
};
