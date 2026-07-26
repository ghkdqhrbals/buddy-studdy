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
