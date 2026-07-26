export type SectionKey = "overview" | "users" | "learning" | "notifications" | "quota" | "operations" | "streams";
export type Theme = "light" | "dark";
export type MetricKind = "count" | "rate" | "duration" | "days";

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

export type ScheduledJobRunsResponse = {
  runs: ScheduledJobRun[];
  totalCount: number;
  limit: number;
  offset: number;
};

export type ScheduledJobStatus = {
  jobName: string;
  enabled: boolean;
  scheduleType: string;
  scheduleValue: string;
  latestRun?: ScheduledJobRun | null;
  stale: boolean;
  staleThresholdMinutes: number;
  timeoutSeconds: number;
  stuck: boolean;
};

export type ScheduledJobStatusResponse = {
  jobs: ScheduledJobStatus[];
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

export type AdminCursorPage<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore: boolean;
  limit: number;
};

export type AdminStreamGroupSummary = {
  name: string;
  consumers: number;
  pending: number;
  lastDeliveredId?: string | null;
};

export type AdminStreamTopicSummary = {
  topic: string;
  streamKey: string;
  maxLength: number;
  length: number;
  firstEntryId?: string | null;
  lastEntryId?: string | null;
  groups: AdminStreamGroupSummary[];
};

export type AdminStreamEntry = {
  id: string;
  eventType?: string | null;
  eventId?: string | null;
  recordId?: string | null;
  userId?: string | null;
  deviceId?: string | null;
  fields: Record<string, string>;
};

export type AdminRedisEventOutboxEntry = {
  id: number;
  eventId: string;
  eventType: string;
  payloadVersion: number;
  payloadJson: string;
  status: string;
  attempts: number;
  nextAttemptAt: string;
  claimedAt?: string | null;
  publishedAt?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type AdminPushOutboxEntry = {
  id: number;
  recordId: number;
  deviceId: string;
  userId?: number | null;
  studyId?: number | null;
  topic: string;
  status: string;
  attempts: number;
  nextAttemptAt: string;
  publishedAt?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
};
