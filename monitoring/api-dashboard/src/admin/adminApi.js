const API_ROOT = "/backend/api/v1/admin";
const TOKEN_KEY = "buddystudy.monitoring.admin.token";
const TOKEN_EXPIRY_KEY = "buddystudy.monitoring.admin.expires-at";

export class AdminApiError extends Error {
  constructor(message, status = 0, body = null) {
    super(message);
    this.name = "AdminApiError";
    this.status = status;
    this.body = body;
  }
}

function stored(key) {
  return window.sessionStorage.getItem(key) || "";
}

export function readAdminSession() {
  const token = stored(TOKEN_KEY);
  const expiresAt = stored(TOKEN_EXPIRY_KEY);
  if (!token || (expiresAt && Date.parse(expiresAt) <= Date.now())) {
    clearAdminSession();
    return null;
  }
  return { token, expiresAt };
}

export function storeAdminSession({ token, expiresAt = "" }) {
  window.sessionStorage.setItem(TOKEN_KEY, token);
  if (expiresAt) window.sessionStorage.setItem(TOKEN_EXPIRY_KEY, expiresAt);
  else window.sessionStorage.removeItem(TOKEN_EXPIRY_KEY);
}

export function clearAdminSession() {
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.sessionStorage.removeItem(TOKEN_EXPIRY_KEY);
}

function notifySessionExpired() {
  window.dispatchEvent(new CustomEvent("monitoring:admin-session-expired"));
}

function errorMessage(body, fallback) {
  return body?.error?.message || body?.message || fallback;
}

async function jsonBody(response) {
  if (response.status === 204) return null;
  return response.json().catch(() => null);
}

export async function loginAdmin(username, password, signal) {
  const response = await fetch(`${API_ROOT}/login`, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    signal,
  });
  const body = await jsonBody(response);
  const token = body?.adminToken || body?.token;
  if (!response.ok || !token) {
    throw new AdminApiError(errorMessage(body, "Sign in failed"), response.status, body);
  }
  const session = { token, expiresAt: body.expiresAt || "" };
  storeAdminSession(session);
  return session;
}

export async function adminFetch(path, options = {}) {
  const session = readAdminSession();
  if (!session) {
    notifySessionExpired();
    throw new AdminApiError("로그인이 만료되었습니다. 다시 로그인해 주세요.", 401);
  }

  const response = await fetch(`${API_ROOT}${path}`, {
    ...options,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${session.token}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers,
    },
  });
  const body = await jsonBody(response);
  if (!response.ok) {
    if (response.status === 401) {
      clearAdminSession();
      notifySessionExpired();
    }
    throw new AdminApiError(
      errorMessage(body, `Request failed (${response.status})`),
      response.status,
      body,
    );
  }
  return body;
}
