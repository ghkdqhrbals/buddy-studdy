const MUTATION_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function classifyAuditEvent(entry) {
  if (Number(entry.status) >= 400) return "failure";
  if (/\/(?:auth|oauth)(?:\/|$)/i.test(entry.path || "")) return "authentication";
  if (MUTATION_METHODS.has(String(entry.method || "").toUpperCase())) return "mutation";
  return "access";
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
    return [entry.method, entry.path, entry.requestId, entry.errorCode]
      .some((value) => String(value || "").toLowerCase().includes(search));
  });
}

export function summarizeAuditEntries(entries) {
  return {
    total: entries.length,
    uniqueIps: new Set(entries.map((entry) => entry.clientIp).filter(Boolean)).size,
    mutations: entries.filter((entry) => (entry.eventType || classifyAuditEvent(entry)) === "mutation").length,
    failures: entries.filter((entry) => (entry.eventType || classifyAuditEvent(entry)) === "failure").length,
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
