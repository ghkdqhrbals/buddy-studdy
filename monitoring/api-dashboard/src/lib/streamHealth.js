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

function validDuration(value) {
  if (value == null) return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

export function latestConsumerActivity(group) {
  const consumers = Array.isArray(group?.consumerDetails) ? group.consumerDetails : [];
  const supportsInactive = consumers.some((consumer) => consumer.inactiveMs != null);
  const successful = consumers
    .map((consumer) => validDuration(consumer.inactiveMs))
    .filter((value) => value != null);
  if (successful.length > 0) {
    return { milliseconds: Math.min(...successful), source: "successful" };
  }
  if (supportsInactive) {
    return { milliseconds: null, source: "successful" };
  }
  const legacyIdle = consumers
    .map((consumer) => validDuration(consumer.idleMs))
    .filter((value) => value != null);
  return {
    milliseconds: legacyIdle.length > 0 ? Math.min(...legacyIdle) : null,
    source: legacyIdle.length > 0 ? "legacy-idle" : "unavailable",
  };
}

export function streamLatestConsumerActivity(topic) {
  const activities = (Array.isArray(topic?.groups) ? topic.groups : [])
    .map(latestConsumerActivity)
    .filter((activity) => activity.milliseconds != null);
  if (activities.length === 0) return { milliseconds: null, source: "unavailable" };
  return activities.reduce(
    (latest, activity) => activity.milliseconds < latest.milliseconds ? activity : latest,
  );
}

export function streamEntryAge(streamId, now = Date.now()) {
  const timestamp = Number(String(streamId || "").split("-")[0]);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return null;
  return Math.max(0, now - timestamp);
}

export function streamOperationalState(topic) {
  const groups = Array.isArray(topic?.groups) ? topic.groups : [];
  const groupErrors = groups.reduce(
    (total, group) => total + (Array.isArray(group.inspectionErrors) ? group.inspectionErrors.length : 0),
    0,
  );
  if ((topic?.inspectionErrors?.length || 0) + groupErrors > 0) {
    return { label: "Partial data", tone: "warning" };
  }
  if (groups.some((group) => Number(group.maxRetryCount || 0) > 0)) {
    return { label: "Retrying", tone: "danger" };
  }
  if (groups.some((group) => Number(group.pending || 0) > 0)) {
    return { label: "Pending", tone: "warning" };
  }
  if (groups.some((group) => Number(group.lag || 0) > 0)) {
    return { label: "Lagging", tone: "warning" };
  }
  if (groups.length === 0) return { label: "No groups", tone: "neutral" };
  return { label: "Healthy", tone: "success" };
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
  if (milliseconds == null || !Number.isFinite(Number(milliseconds)) || Number(milliseconds) < 0) return "-";
  const value = Number(milliseconds);
  if (value < 1_000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)} s`;
  if (value < 3_600_000) return `${(value / 60_000).toFixed(1)} min`;
  if (value < 86_400_000) return `${(value / 3_600_000).toFixed(1)} hr`;
  return `${(value / 86_400_000).toFixed(1)} d`;
}

export function formatStreamActivity(milliseconds) {
  if (milliseconds == null || !Number.isFinite(Number(milliseconds)) || Number(milliseconds) < 0) {
    return "Not recorded";
  }
  if (Number(milliseconds) < 1_000) return "Just now";
  return `${formatStreamDuration(milliseconds)} ago`;
}
