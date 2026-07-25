const MUTATION_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function classifyAuditEvent(entry) {
  if ([401, 403].includes(Number(entry.status))) return "denied";
  if (MUTATION_METHODS.has(String(entry.method || "").toUpperCase())) return "action";
  return "page";
}

export function parseMonitoringAccessLog(value) {
  if (!Array.isArray(value) || value.length < 2) return null;
  const [nanoseconds, line] = value;
  const payload = JSON.parse(line);
  if (payload.event !== "monitoring_access") return null;
  const query = String(payload.query || "");
  const path = `${payload.path || "/"}${query ? `?${query}` : ""}`;
  const entry = {
    nanoseconds: String(nanoseconds),
    timestampMs: Number(BigInt(nanoseconds) / 1_000_000n),
    requestId: String(payload.requestId || ""),
    clientIp: String(payload.clientIp || ""),
    forwardedFor: String(payload.forwardedFor || ""),
    user: String(payload.user || ""),
    method: String(payload.method || "GET").toUpperCase(),
    path,
    status: Number(payload.status) || 0,
    durationMs: Number(payload.durationSeconds) * 1000 || 0,
    userAgent: String(payload.userAgent || ""),
    referer: String(payload.referer || ""),
  };
  return { ...entry, eventType: classifyAuditEvent(entry) };
}

export function filterAuditEntries(entries, filters = {}) {
  const eventType = String(filters.eventType || "all");
  const ip = String(filters.ip || "").trim().toLowerCase();
  const search = String(filters.search || "").trim().toLowerCase();
  return entries.filter((entry) => {
    const event = entry.eventType || classifyAuditEvent(entry);
    if (eventType !== "all" && event !== eventType) return false;
    if (ip && !String(entry.clientIp || "").toLowerCase().includes(ip)) return false;
    if (!search) return true;
    return [entry.method, entry.path, entry.requestId, entry.user, entry.userAgent]
      .some((value) => String(value || "").toLowerCase().includes(search));
  });
}

export function summarizeAuditEntries(entries) {
  return {
    total: entries.length,
    uniqueIps: new Set(entries.map((entry) => entry.clientIp).filter(Boolean)).size,
    pageViews: entries.filter((entry) => (entry.eventType || classifyAuditEvent(entry)) === "page").length,
    denied: entries.filter((entry) => (entry.eventType || classifyAuditEvent(entry)) === "denied").length,
  };
}

export function paginateAuditEntries(entries, page, pageSize) {
  const totalPages = Math.max(1, Math.ceil(entries.length / pageSize));
  const currentPage = Math.max(1, Math.min(Number(page) || 1, totalPages));
  const start = (currentPage - 1) * pageSize;
  return {
    items: entries.slice(start, start + pageSize),
    page: currentPage,
    totalPages,
  };
}
