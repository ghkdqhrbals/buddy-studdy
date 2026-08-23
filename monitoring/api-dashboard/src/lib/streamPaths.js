export const REDIS_STREAM_ID_PATTERN = /^\d+-\d+$/;

export function isRedisStreamId(value) {
  return REDIS_STREAM_ID_PATTERN.test(String(value || "").trim());
}

export function cursorPath(path, { cursor = "", limit = 20, status = "", eventType = "" } = {}) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  if (status.trim()) params.set("status", status.trim());
  if (eventType.trim()) params.set("eventType", eventType.trim());
  return `${path}?${params}`;
}

export function streamEntriesPath(topic, filters) {
  return cursorPath(`/event-streams/topics/${encodeURIComponent(topic)}/entries`, filters);
}

export function streamEntryPath(topic, entryId) {
  return `/event-streams/topics/${encodeURIComponent(topic)}/entries/${encodeURIComponent(entryId.trim())}`;
}

export function streamPendingPath(topic, group, { cursor = "", limit = 20 } = {}) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return `/event-streams/topics/${encodeURIComponent(topic)}/groups/${encodeURIComponent(group)}/pending?${params}`;
}

export function streamInboxAttemptsPath({
  cursor = "",
  limit = 20,
  consumerGroup = "",
  status = "",
  query = "",
} = {}) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  if (consumerGroup.trim()) params.set("consumerGroup", consumerGroup.trim());
  if (status.trim()) params.set("status", status.trim());
  if (query.trim()) params.set("query", query.trim());
  return `/event-streams/inbox/attempts?${params}`;
}
