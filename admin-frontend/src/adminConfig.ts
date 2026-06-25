import type { MetricKind, SectionKey } from "./types";

export type MetricDefinition = {
  key: string;
  label: string;
  shortLabel: string;
  kind: MetricKind;
  color: string;
  description: string;
};

export const metricCatalog: Record<string, MetricDefinition> = {
  daily_active_users: { key: "daily_active_users", label: "Daily Active Users", shortLabel: "DAU", kind: "count", color: "#2563eb", description: "Unique active users for the selected day." },
  weekly_active_learners: { key: "weekly_active_learners", label: "Weekly Active Learners", shortLabel: "WAU", kind: "count", color: "#7c3aed", description: "Users who studied at least once in the trailing week." },
  question_created_count: { key: "question_created_count", label: "Questions Created", shortLabel: "Questions", kind: "count", color: "#2563eb", description: "Questions generated during the selected period." },
  answer_submitted_count: { key: "answer_submitted_count", label: "Answers Submitted", shortLabel: "Answers", kind: "count", color: "#16a34a", description: "Answers submitted by learners." },
  answer_rate: { key: "answer_rate", label: "Answer Rate", shortLabel: "Answer Rate", kind: "rate", color: "#9333ea", description: "Answered questions divided by created questions." },
  push_open_rate: { key: "push_open_rate", label: "Push Open Rate", shortLabel: "Push Open", kind: "rate", color: "#f97316", description: "Push notifications opened by users." },
  question_to_answer_latency: { key: "question_to_answer_latency", label: "Question to Answer", shortLabel: "Latency", kind: "duration", color: "#0ea5e9", description: "Average time from question creation to answer submission." },
  study_streak: { key: "study_streak", label: "Study Streak", shortLabel: "Streak", kind: "days", color: "#22c55e", description: "Average consecutive study days." },
  quota_used_count: { key: "quota_used_count", label: "Quota Used", shortLabel: "Quota", kind: "count", color: "#64748b", description: "Monthly system quota consumed." },
};

export const overviewMetrics = [
  "daily_active_users",
  "weekly_active_learners",
  "question_created_count",
  "answer_submitted_count",
  "answer_rate",
  "push_open_rate",
  "question_to_answer_latency",
  "study_streak",
];

export const overviewTrendMetrics = [
  "daily_active_users",
  "weekly_active_learners",
  "question_created_count",
  "answer_submitted_count",
];

export const sections: Array<{ key: SectionKey; label: string; metrics: string[] }> = [
  { key: "overview", label: "Home", metrics: overviewMetrics },
  { key: "users", label: "Users", metrics: ["daily_active_users", "weekly_active_learners", "study_streak"] },
  { key: "learning", label: "Learning", metrics: ["question_created_count", "answer_submitted_count", "answer_rate", "question_to_answer_latency"] },
  { key: "notifications", label: "Notifications", metrics: ["push_open_rate"] },
  { key: "quota", label: "Quota", metrics: ["quota_used_count"] },
  { key: "operations", label: "Operations", metrics: [] },
];

export const JOB_PAGE_SIZE = 10;

export const sectionPaths: Record<SectionKey, string> = {
  overview: "/home",
  users: "/analytics/users",
  learning: "/analytics/learning",
  notifications: "/analytics/notifications",
  quota: "/analytics/quota",
  operations: "/operations/scheduler-runs",
};
