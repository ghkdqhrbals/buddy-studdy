export type SectionKey = "overview" | "users" | "learning" | "notifications" | "quota" | "advertising" | "app_updates" | "operations";
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
  displayName?: string | null;
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
  displayName?: string | null;
  description?: string | null;
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
  totalCount: number;
  limit: number;
  offset: number;
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

export type AppUpdateMode = "FORCE" | "OPTIONAL";

export type AppUpdateCampaignSummary = {
  id: number;
  platform: string;
  targetVersion: string;
  targetBuild: string;
  mode: AppUpdateMode;
  status: string;
  appStoreUrl: string;
  createdBy: string;
  activatedAt: string;
  endedAt?: string | null;
  checkedUserCount: number;
  promptedUserCount: number;
  openedUserCount: number;
  convertedUserCount: number;
  conversionRate: number;
  remoteConfigStatus: "PENDING" | "PUBLISHED" | "FAILED" | "DISABLED";
  remoteConfigRevision?: number | null;
  remoteConfigPublishedAt?: string | null;
  remoteConfigError?: string | null;
};

export type AppUpdateCampaignPage = {
  campaigns: AppUpdateCampaignSummary[];
  totalCount: number;
  limit: number;
  offset: number;
};

export type AppUpdateUserSummary = {
  userId: number;
  email: string;
  displayName: string;
  deviceId: string;
  firstVersion: string;
  firstBuild: string;
  currentVersion: string;
  currentBuild: string;
  firstCheckedAt: string;
  lastCheckedAt: string;
  promptedAt?: string | null;
  dismissedAt?: string | null;
  appStoreOpenedAt?: string | null;
  convertedAt?: string | null;
  status: string;
};

export type AppUpdateUserPage = {
  users: AppUpdateUserSummary[];
  totalCount: number;
  limit: number;
  offset: number;
};

export type CreateAppUpdateCampaignInput = {
  platform: "ios";
  targetVersion: string;
  targetBuild: string;
  mode: AppUpdateMode;
  titleKo: string;
  titleEn: string;
  titleJa: string;
  messageKo: string;
  messageEn: string;
  messageJa: string;
  appStoreUrl: string;
};

export type NativeAdvertisementAudience = "ALL" | "AUTHENTICATED" | "ANONYMOUS";

export type NativeAdvertisementCampaignStatusFilter = "" | "ACTIVE" | "PAUSED" | "SCHEDULED" | "ENDED";
export type NativeAdvertisementCampaignAudienceFilter = "" | NativeAdvertisementAudience;

export type NativeAdvertisementCampaignFilters = {
  query?: string;
  status?: NativeAdvertisementCampaignStatusFilter;
  audience?: NativeAdvertisementCampaignAudienceFilter;
};

export type NativeAdvertisementCampaignInput = {
  campaignKey: string;
  audience: NativeAdvertisementAudience;
  disclosureKo: string;
  disclosureEn: string;
  disclosureJa: string;
  titleKo: string;
  titleEn: string;
  titleJa: string;
  bodyKo: string | null;
  bodyEn: string | null;
  bodyJa: string | null;
  destinationUrl: string;
  basePriority: number;
  authenticatedRelevance: number;
  anonymousRelevance: number;
  dailySelectionCap: number;
  minimumSecondsBetweenSelections: number;
  postViewCooldownSeconds: number;
  minimumFeedItemCount: number;
  earliestPosition: number;
  latestPosition: number;
  active: boolean;
  startsAt: string | null;
  endsAt: string | null;
};

export type NativeAdvertisementCampaignSummary = NativeAdvertisementCampaignInput & {
  id: number;
  placement: string;
  performanceSelections: number;
  performanceViews: number;
  performanceViewRate: number;
  createdAt: string;
  updatedAt: string;
};

export type NativeAdvertisementRankingPolicy = {
  performanceWindowDays: number;
  exploitationPercent: number;
  explorationPercent: number;
  selectionPoolSize: number;
  basePriorityWeight: number;
  relevanceWeight: number;
  smoothedViewRateWeight: number;
  explorationWeight: number;
  freshnessWeight: number;
  dailySelectionPenalty: number;
};

export type NativeAdvertisementCampaignPage = {
  campaigns: NativeAdvertisementCampaignSummary[];
  totalCount: number;
  limit: number;
  offset: number;
  rankingPolicy: NativeAdvertisementRankingPolicy;
};

export type NativeAdvertisementUserStatusFilter = "" | "OPENED" | "NOT_OPENED";

export type NativeAdvertisementUserSummary = {
  userId: number;
  accountStatus: "ACTIVE" | "ANONYMOUS" | "PENDING_TERMS" | "WITHDRAWN";
  email?: string | null;
  displayName?: string | null;
  selectionCount: number;
  destinationOpenCount: number;
  openRate: number;
  distinctDeviceCount: number;
  firstSelectedAt: string;
  lastSelectedAt: string;
  lastViewedAt?: string | null;
};

export type NativeAdvertisementUserPage = {
  users: NativeAdvertisementUserSummary[];
  totalCount: number;
  limit: number;
  offset: number;
};
