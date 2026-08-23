const API_ROOT = "/testzone/api";

export class DeploymentApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "DeploymentApiError";
    this.status = status;
  }
}

export async function deploymentFetch(path, options = {}) {
  const response = await fetch(`${API_ROOT}${path}`, {
    ...options,
    headers: {
      Accept: "application/json",
      ...options.headers,
    },
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new DeploymentApiError(
      body?.error || `Deployment history request failed (${response.status})`,
      response.status,
    );
  }
  return body;
}
