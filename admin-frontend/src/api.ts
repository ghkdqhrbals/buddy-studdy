import type {
  AdminApiError,
  AdminLoginResponse,
  AdminMetricsResponse,
  ScheduledJobRun,
  ScheduledJobRunsResponse,
  ScheduledJobStatusResponse,
} from "./types";

const API_BASE_URL = import.meta.env.VITE_ADMIN_API_BASE_URL ?? "";
const TOKEN_KEY = "buddystudy.adminToken";
const TOKEN_EXP_KEY = "buddystudy.adminTokenExpiresAt";

export type UnauthorizedHandler = () => void;

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function storeToken(response: AdminLoginResponse) {
  localStorage.setItem(TOKEN_KEY, response.adminToken);
  localStorage.setItem(TOKEN_EXP_KEY, response.expiresAt);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXP_KEY);
}

export function getTokenExpiry(): string | null {
  return localStorage.getItem(TOKEN_EXP_KEY);
}

async function request<T>(path: string, options: RequestInit, onUnauthorized: UnauthorizedHandler): Promise<T> {
  const token = getStoredToken();
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });
  if (response.status === 401) {
    clearToken();
    onUnauthorized();
    throw new Error("Session expired");
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as AdminApiError | null;
    throw new Error(payload?.error?.message ?? payload?.error?.code ?? `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export async function login(username: string, password: string): Promise<AdminLoginResponse> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/login`, {
    method: "POST",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as AdminApiError | null;
    throw new Error(payload?.error?.message ?? "Login failed");
  }
  return response.json() as Promise<AdminLoginResponse>;
}

export function fetchMetrics(
  startDate: string,
  endDate: string,
  metricKeys: string[],
  onUnauthorized: UnauthorizedHandler,
): Promise<AdminMetricsResponse> {
  const params = new URLSearchParams({ startDate, endDate });
  metricKeys.forEach((metricKey) => params.append("metricKey", metricKey));
  return request(`/api/v1/admin/analytics/metrics?${params}`, { method: "GET" }, onUnauthorized);
}

export function refreshMetrics(
  startDate: string,
  endDate: string,
  onUnauthorized: UnauthorizedHandler,
): Promise<AdminMetricsResponse> {
  const params = new URLSearchParams({ startDate, endDate });
  return request(`/api/v1/admin/analytics/refresh?${params}`, { method: "POST" }, onUnauthorized);
}

export async function fetchJobRuns(
  onUnauthorized: UnauthorizedHandler,
  limit = 20,
  offset = 0,
  jobName: string | null = null,
  runId: number | null = null,
): Promise<ScheduledJobRunsResponse> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (jobName?.trim()) {
    params.set("jobName", jobName.trim());
  }
  if (runId !== null) {
    params.set("runId", String(runId));
  }
  const response = await request<ScheduledJobRunsResponse | ScheduledJobRun[]>(
    `/api/v1/admin/jobs/runs?${params}`,
    { method: "GET" },
    onUnauthorized,
  );
  if (Array.isArray(response)) {
    return {
      runs: response,
      totalCount: offset + response.length,
      limit,
      offset,
    };
  }
  return response;
}

export function fetchJobStatuses(onUnauthorized: UnauthorizedHandler): Promise<ScheduledJobStatusResponse> {
  return request("/api/v1/admin/jobs/statuses", { method: "GET" }, onUnauthorized);
}

export function retryJob(jobName: string, runId: number | null, onUnauthorized: UnauthorizedHandler): Promise<ScheduledJobRun> {
  const params = new URLSearchParams();
  if (runId !== null) {
    params.set("runId", String(runId));
  }
  const suffix = params.toString() ? `?${params}` : "";
  return request(`/api/v1/admin/jobs/${encodeURIComponent(jobName)}/retry${suffix}`, { method: "POST" }, onUnauthorized);
}
