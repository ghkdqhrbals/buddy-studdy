const API_ROOT = "/backend/api/v1/admin";
const TOKEN_KEY = "buddystudy.monitoring.admin.token";
const TOKEN_EXPIRY_KEY = "buddystudy.monitoring.admin.expires-at";

function sessionValue(key) {
  return window.sessionStorage.getItem(key) || "";
}

function sessionExpired(expiresAt) {
  return Boolean(expiresAt) && Date.parse(expiresAt) <= Date.now();
}

function apiError(body, status, fallback) {
  const error = new Error(body?.error?.message || body?.message || fallback);
  error.status = status;
  return error;
}

export function getAdminSession() {
  const token = sessionValue(TOKEN_KEY);
  const expiresAt = sessionValue(TOKEN_EXPIRY_KEY);
  if (!token || sessionExpired(expiresAt)) {
    if (token || expiresAt) clearAdminSession();
    return null;
  }
  return { token, expiresAt };
}

export function hasValidAdminSession() {
  return getAdminSession() !== null;
}

export function clearAdminSession() {
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.sessionStorage.removeItem(TOKEN_EXPIRY_KEY);
}

export async function loginAdmin(username, password) {
  const response = await fetch(`${API_ROOT}/login`, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const body = await response.json().catch(() => null);
  const token = body?.adminToken || body?.token;
  if (!response.ok || !token) {
    throw apiError(body, response.status, "Sign in failed");
  }

  const expiresAt = body.expiresAt || "";
  window.sessionStorage.setItem(TOKEN_KEY, token);
  if (expiresAt) {
    window.sessionStorage.setItem(TOKEN_EXPIRY_KEY, expiresAt);
  } else {
    window.sessionStorage.removeItem(TOKEN_EXPIRY_KEY);
  }
  return { token, expiresAt };
}

export async function adminFetch(path, options = {}) {
  const session = getAdminSession();
  if (!session) {
    throw apiError(null, 401, "로그인이 만료되었습니다. 다시 로그인해 주세요.");
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
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401) clearAdminSession();
    throw apiError(body, response.status, `Request failed (${response.status})`);
  }
  return body;
}
