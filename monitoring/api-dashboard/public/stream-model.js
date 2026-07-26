export const REDIS_STREAM_ID_PATTERN = /^\d+-\d+$/;

export function isRedisStreamId(value) {
  return REDIS_STREAM_ID_PATTERN.test(String(value || "").trim());
}

export function buildStreamEntriesPath(topic, { cursor = "", limit = 20, eventType = "" } = {}) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  if (eventType.trim()) params.set("eventType", eventType.trim());
  return `/event-streams/topics/${encodeURIComponent(topic)}/entries?${params}`;
}

export function buildStreamEntryPath(topic, entryId) {
  return `/event-streams/topics/${encodeURIComponent(topic)}/entries/${encodeURIComponent(entryId.trim())}`;
}

export function summarizeGroups(groups) {
  if (!Array.isArray(groups) || groups.length === 0) return "None";
  return groups
    .map((group) => `${group.name}: ${Number(group.pending) || 0} pending / ${Number(group.consumers) || 0} consumers`)
    .join(", ");
}
