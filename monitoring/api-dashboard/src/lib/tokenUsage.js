export const USAGE_QUERY = '{app="buddystudy"} |= "openai_usage "';
export const USAGE_LIMIT = 2000;
export const TOKEN_FIELDS = ["inputTokens", "outputTokens", "totalTokens", "cachedInputTokens", "inputTextTokens", "inputAudioTokens", "outputTextTokens", "outputAudioTokens", "reasoningTokens", "cachedTextTokens", "cachedAudioTokens", "inputImageTokens", "outputImageTokens", "cachedImageTokens"];
const number = (value) => Number.isSafeInteger(value) && value >= 0 ? value : null;
const label = (value) => typeof value === "string" ? value.slice(0, 120) : "unknown";

export function parseUsage([nanoseconds, line]) {
  const marker = line.indexOf("openai_usage ");
  if (marker < 0) return null;
  try {
    const body = JSON.parse(line.slice(marker + "openai_usage ".length));
    if (body.event !== "openai_usage" || body.schemaVersion !== 1 || !/^\d+$/.test(nanoseconds)) return null;
    const row = { nanoseconds, timestampMs: Number(BigInt(nanoseconds) / 1000000n) };
    for (const key of ["eventRef", "model", "operation", "stage", "transport", "granularity", "outcome"]) row[key] = label(body[key]);
    for (const key of [...TOKEN_FIELDS, "durationMs", "attempt", "retryCount"]) row[key] = number(body[key]);
    row.audioSeconds = typeof body.audioSeconds === "number" && Number.isFinite(body.audioSeconds) && body.audioSeconds >= 0 ? body.audioSeconds : null;
    row.id = /^[a-f0-9]{16,64}$/.test(row.eventRef) ? row.eventRef : `${nanoseconds}:${line}`;
    return row;
  } catch { return null; }
}

export function parseUsageStreams(streams) {
  const raw = streams.flatMap((stream) => stream.values || []);
  const seen = new Set();
  const rows = raw.map(parseUsage).filter(Boolean).sort((a, b) => a.nanoseconds === b.nanoseconds ? 0 : BigInt(a.nanoseconds) > BigInt(b.nanoseconds) ? -1 : 1)
    .filter((row) => { if (seen.has(row.id)) return false; seen.add(row.id); return true; });
  return { rows, truncated: raw.length >= USAGE_LIMIT, invalid: raw.filter((value) => !parseUsage(value)).length };
}

export function totalField(rows, field) {
  const known = rows.map((row) => row[field]).filter((value) => value !== null && value !== undefined);
  return { value: known.length ? known.reduce((sum, value) => sum + value, 0) : null, known: known.length, missing: rows.length - known.length };
}

export function summarizeUsage(rows) {
  const fields = Object.fromEntries(TOKEN_FIELDS.map((key) => [key, totalField(rows, key)]));
  // Cached counts are a subset of input, never additional input tokens.
  const pairs = rows.filter((r) => r.inputTokens !== null && r.cachedInputTokens !== null && r.cachedInputTokens <= r.inputTokens);
  const input = pairs.reduce((sum, r) => sum + r.inputTokens, 0);
  return { ...fields, count: rows.length, missing: rows.filter((r) => r.inputTokens === null || r.outputTokens === null).length,
    cacheRate: input ? pairs.reduce((sum, r) => sum + r.cachedInputTokens, 0) / input : null,
    cacheCoverage: pairs.length };
}

export function groupUsage(rows) {
  const groups = new Map();
  for (const row of rows) {
    const key = JSON.stringify([row.model, row.operation, row.stage, row.granularity]);
    if (!groups.has(key)) groups.set(key, { id: key, model: row.model, operation: row.operation, stage: row.stage, granularity: row.granularity, rows: [] });
    groups.get(key).rows.push(row);
  }
  return [...groups.values()].map(({ rows: members, ...group }) => ({ ...group, ...summarizeUsage(members) }))
    .sort((a, b) => ((b.inputTokens.value || 0) + (b.outputTokens.value || 0)) - ((a.inputTokens.value || 0) + (a.outputTokens.value || 0)));
}

export function usageTrend(rows, start, end, count = 24) {
  const width = Math.max(1, (end - start) / count);
  const buckets = Array.from({ length: count }, (_, index) => ({ start: start + index * width, rows: [] }));
  for (const row of rows) if (row.timestampMs >= start && row.timestampMs <= end) buckets[Math.min(count - 1, Math.floor((row.timestampMs - start) / width))].rows.push(row);
  return buckets.map(({ start: time, rows: members }) => ({ start: time, count: members.length, input: totalField(members, "inputTokens").value, output: totalField(members, "outputTokens").value }));
}

export async function loadUsage(range, signal) {
  const end = Date.now(), start = end - Number(range);
  const params = new URLSearchParams({ query: USAGE_QUERY, start: String(BigInt(start) * 1000000n), end: String(BigInt(end) * 1000000n), limit: String(USAGE_LIMIT), direction: "backward" });
  const response = await fetch(`/loki/api/v1/query_range?${params}`, { signal });
  if (!response.ok) { const error = new Error(`토큰 사용 기록을 불러오지 못했습니다 (${response.status}). 새로고침으로 다시 시도해 주세요.`); error.status = response.status; throw error; }
  const payload = await response.json();
  if (payload.status !== "success" || !Array.isArray(payload.data?.result)) throw new Error("로그 서버가 올바른 조회 결과를 반환하지 않았습니다.");
  return { ...parseUsageStreams(payload.data.result), start, end };
}
