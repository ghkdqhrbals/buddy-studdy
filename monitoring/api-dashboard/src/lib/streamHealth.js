export function streamGroupRows(topics = []) {
  return topics.flatMap((topic) =>
    (Array.isArray(topic.groups) ? topic.groups : []).map((group) => ({
      ...group,
      topic: topic.topic,
      streamKey: topic.streamKey,
      streamLength: Number(topic.length || 0),
      streamMaxLength: Number(topic.maxLength || 0),
      streamLastEntryId: topic.lastEntryId || null,
      topicInspectionErrors: Array.isArray(topic.inspectionErrors) ? topic.inspectionErrors : [],
    })),
  );
}

export function streamRetention(topic) {
  const length = Number(topic?.length || 0);
  const maxLength = Number(topic?.maxLength || 0);
  if (maxLength <= 0) return { percent: null, label: "Not configured" };
  const percent = Math.min(100, Math.max(0, (length / maxLength) * 100));
  return {
    percent,
    label: `${percent.toFixed(percent < 10 ? 1 : 0)}%`,
  };
}

export function summarizeStreamHealth(topics = []) {
  const groups = streamGroupRows(topics);
  return {
    streams: topics.length,
    groups: groups.length,
    lag: groups.reduce((total, group) => total + Number(group.lag || 0), 0),
    pending: groups.reduce((total, group) => total + Number(group.pending || 0), 0),
    retrying: groups.filter((group) => Number(group.maxRetryCount || 0) > 0).length,
    inspectionFailures: topics.reduce(
      (total, topic) => total +
        (Array.isArray(topic.inspectionErrors) ? topic.inspectionErrors.length : 0) +
        (Array.isArray(topic.groups)
          ? topic.groups.reduce(
            (groupTotal, group) => groupTotal +
              (Array.isArray(group.inspectionErrors) ? group.inspectionErrors.length : 0),
            0,
          )
          : 0),
      0,
    ),
  };
}

export function streamGroupState(group) {
  if ((group.inspectionErrors?.length || 0) + (group.topicInspectionErrors?.length || 0) > 0) {
    return { label: "Partial data", tone: "warning" };
  }
  if (Number(group.maxRetryCount || 0) > 0) return { label: "Retrying", tone: "danger" };
  if (Number(group.pending || 0) > 0) return { label: "Pending", tone: "warning" };
  if (Number(group.lag || 0) > 0) return { label: "Lagging", tone: "warning" };
  return { label: "Healthy", tone: "success" };
}

export function formatStreamDuration(milliseconds) {
  if (milliseconds == null || !Number.isFinite(Number(milliseconds))) return "-";
  const value = Number(milliseconds);
  if (value < 1_000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)} s`;
  if (value < 3_600_000) return `${(value / 60_000).toFixed(1)} min`;
  return `${(value / 3_600_000).toFixed(1)} hr`;
}
