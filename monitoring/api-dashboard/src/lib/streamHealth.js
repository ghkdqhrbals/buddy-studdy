export function streamGroupRows(topics = []) {
  return topics.flatMap((topic) =>
    (Array.isArray(topic.groups) ? topic.groups : []).map((group) => ({
      ...group,
      topic: topic.topic,
      streamKey: topic.streamKey,
      streamLength: Number(topic.length || 0),
      streamLastEntryId: topic.lastEntryId || null,
    })),
  );
}

export function summarizeStreamHealth(topics = []) {
  const groups = streamGroupRows(topics);
  return {
    streams: topics.length,
    groups: groups.length,
    lag: groups.reduce((total, group) => total + Number(group.lag || 0), 0),
    pending: groups.reduce((total, group) => total + Number(group.pending || 0), 0),
    retrying: groups.filter((group) => Number(group.maxRetryCount || 0) > 0).length,
  };
}

export function streamGroupState(group) {
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
