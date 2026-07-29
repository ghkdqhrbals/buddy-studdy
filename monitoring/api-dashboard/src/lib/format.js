export function formatDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "Asia/Seoul",
  }).format(date);
}

export function formatDuration(milliseconds) {
  if (milliseconds == null || milliseconds === "") return "-";
  const value = Number(milliseconds);
  if (!Number.isFinite(value)) return "-";
  if (value < 1) return `${Math.round(value * 1000)}µs`;
  if (value < 1000) return `${value.toFixed(value < 10 ? 2 : 1)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

export function statusTone(value) {
  const status = String(value || "").toUpperCase();
  if (["ACTIVE", "READY", "PUBLISHED", "COMPLETED", "SUCCESS", "SUCCEEDED", "200"].includes(status)) return "success";
  if (["FAILED", "DEAD", "BLOCKED", "ERROR", "STUCK", "STALE"].includes(status) || Number(status) >= 500) return "danger";
  if (["PENDING", "RETRY", "RETRY_SCHEDULED", "LEASE_EXPIRED", "PROCESSING", "CLAIMED", "ANONYMOUS", "RUNNING", "SKIPPED", "DISABLED"].includes(status)) return "warning";
  return "neutral";
}
