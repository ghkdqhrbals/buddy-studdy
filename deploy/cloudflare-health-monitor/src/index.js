const STATE_KEY = "backend-health-state";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      const config = validateEnv(env);
      if (!config.ok) {
        return json({ ok: false, error: "Configuration error.", missingConfig: config.missing }, { status: 500 });
      }
      const state = await readState(env);
      return json({ ok: true, state });
    }
    if (request.method === "POST" && url.pathname === "/check") {
      if (!isAuthorizedManualCheck(request, env)) {
        return json({ ok: false, error: "Unauthorized." }, { status: 401 });
      }
      const config = validateEnv(env);
      if (!config.ok) {
        return json({ ok: false, error: "Configuration error.", missingConfig: config.missing }, { status: 500 });
      }
      const result = await runHealthCheckSafely(env, Date.now());
      if (!result.ok) {
        return json(
          { ok: false, error: "Health monitor execution failed.", message: result.error, state: result.state },
          { status: 500 },
        );
      }
      const state = result.state;
      return json({ ok: state.status === "up", state });
    }
    return json({ ok: false, error: "Not found." }, { status: 404 });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(runHealthCheckSafely(env, event.scheduledTime));
  },
};

async function runHealthCheckSafely(env, scheduledTime) {
  try {
    return { ok: true, state: await runHealthCheck(env, scheduledTime), error: null };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const checkedAt = new Date(scheduledTime || Date.now()).toISOString();
    const state = {
      status: "monitor_error",
      checkedAt,
      lastUpAt: null,
      lastDownAt: null,
      lastAlertAt: null,
      consecutiveFailures: 0,
      httpStatus: null,
      error: message,
      detail: null,
      alertType: null,
      shouldAlert: false,
      alertSent: false,
      slackAlertError: null,
    };
    console.error(
      JSON.stringify({
        message: "health_monitor_execution_failed",
        error: message,
      }),
    );
    return { ok: false, state, error: message };
  }
}

async function runHealthCheck(env, scheduledTime) {
  const checkedAt = new Date(scheduledTime || Date.now()).toISOString();
  const config = validateEnv(env);
  if (!config.ok) {
    const state = {
      status: "config_error",
      checkedAt,
      lastUpAt: null,
      lastDownAt: null,
      lastAlertAt: null,
      consecutiveFailures: 0,
      httpStatus: null,
      error: `Missing monitor configuration: ${config.missing.join(", ")}`,
      detail: null,
      alertType: null,
      shouldAlert: false,
    };
    console.error(
      JSON.stringify({
        message: "health_monitor_configuration_error",
        missingConfig: config.missing,
      }),
    );
    return state;
  }
  const previous = await readState(env);
  const result = await checkHealth(env.HEALTHCHECK_URL, env);
  let next = nextState(previous, result, env, checkedAt);
  let slackAlertError = null;

  if (next.shouldAlert) {
    try {
      await sendSlackAlert(env, next);
      next = { ...next, lastAlertAt: checkedAt, shouldAlert: false, alertSent: true, slackAlertError: null };
    } catch (error) {
      slackAlertError = error instanceof Error ? error.message : String(error);
      next = { ...next, lastAlertAt: previous?.lastAlertAt || null, alertSent: false, slackAlertError };
      console.error(
        JSON.stringify({
          message: "health_monitor_slack_alert_failed",
          status: next.status,
          healthUrl: env.HEALTHCHECK_URL,
          error: slackAlertError,
        }),
      );
    }
  }

  await env.HEALTH_MONITOR_STATE.put(STATE_KEY, JSON.stringify(next));

  console.log(
    JSON.stringify({
      message: "health_monitor_checked",
      status: next.status,
      consecutiveFailures: next.consecutiveFailures,
      healthUrl: env.HEALTHCHECK_URL,
      httpStatus: result.httpStatus,
      error: result.error,
      alertSent: next.alertSent === true,
      slackAlertError: next.slackAlertError || null,
    }),
  );

  return next;
}

async function checkHealth(url, env = {}) {
  const timeoutMs = healthcheckTimeoutMs(env);
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort("healthcheck timeout"), timeoutMs);
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: { "Accept": "application/json" },
      cf: { cacheTtl: 0, cacheEverything: false },
      signal: controller.signal,
    });
    if (response.ok) {
      return { healthy: true, httpStatus: response.status, error: null, detail: null };
    }
    const detail = await responseDetail(response);
    return {
      healthy: false,
      httpStatus: response.status,
      error: `HTTP ${response.status}`,
      detail,
    };
  } catch (error) {
    return {
      healthy: false,
      httpStatus: null,
      error: isAbortError(error) ? `Healthcheck timed out after ${timeoutMs}ms` : error instanceof Error ? error.message : String(error),
      detail: null,
    };
  } finally {
    clearTimeout(timeoutId);
  }
}

function healthcheckTimeoutMs(env) {
  const raw = Number.parseInt(env?.HEALTHCHECK_TIMEOUT_MS || "8000", 10);
  return Number.isFinite(raw) ? Math.min(Math.max(raw, 1000), 25000) : 8000;
}

function isAbortError(error) {
  return error?.name === "AbortError" || String(error).includes("healthcheck timeout");
}

async function responseDetail(response) {
  const contentType = response.headers.get("Content-Type") || "";
  const text = await response.text().catch(() => "");
  if (!text) return null;
  if (!contentType.includes("application/json")) return truncate(text, 900);
  try {
    return summarizeHealthJson(JSON.parse(text));
  } catch (_error) {
    return truncate(text, 900);
  }
}

function summarizeHealthJson(body) {
  if (!body || typeof body !== "object") return null;
  const checks = body.checks && typeof body.checks === "object" ? body.checks : null;
  if (!checks) return truncate(JSON.stringify(body), 900);
  const failed = Object.entries(checks)
    .filter(([, value]) => value && value.ok === false)
    .map(([name, value]) => {
      const message = typeof value.message === "string" && value.message.trim() ? `: ${value.message.trim()}` : "";
      const details = summarizeCheckDetails(value.details);
      return `${name}${message}${details ? ` [${details}]` : ""}`;
    });
  if (failed.length === 0) return truncate(JSON.stringify(body), 900);
  return truncate(failed.join("; "), 900);
}

function summarizeCheckDetails(details) {
  if (!details || typeof details !== "object") return "";
  const parts = [];
  if (Array.isArray(details.missingJobs) && details.missingJobs.length > 0) {
    parts.push(`missingJobs=${details.missingJobs.join(",")}`);
  }
  if (Number.isFinite(details.thresholdSeconds)) {
    parts.push(`threshold=${details.thresholdSeconds}s`);
  }
  if (Number.isFinite(details.startupGraceSeconds)) {
    parts.push(`startupGrace=${details.startupGraceSeconds}s`);
  }
  if (Array.isArray(details.staleJobs) && details.staleJobs.length > 0) {
    const staleJobs = details.staleJobs
      .map((job) => {
        if (!job || typeof job !== "object") return "";
        const name = job.jobName || "unknown";
        const staleFor = Number.isFinite(job.staleForSeconds) ? ` staleFor=${job.staleForSeconds}s` : "";
        return `${name}${staleFor}`;
      })
      .filter(Boolean)
      .join(",");
    if (staleJobs) parts.push(`staleJobs=${staleJobs}`);
  }
  return parts.join(", ");
}

function truncate(value, maxLength) {
  const text = String(value);
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1)}…`;
}

function json(body, init = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
}

function isAuthorizedManualCheck(request, env) {
  if (!env.MANUAL_CHECK_TOKEN) return false;
  const expected = `Bearer ${env.MANUAL_CHECK_TOKEN}`;
  return request.headers.get("Authorization") === expected;
}

function validateEnv(env) {
  const missing = [];
  if (!env.HEALTHCHECK_URL) missing.push("HEALTHCHECK_URL");
  if (!env.SLACK_WEBHOOK_URL) missing.push("SLACK_WEBHOOK_URL");
  if (!env.HEALTH_MONITOR_STATE || typeof env.HEALTH_MONITOR_STATE.get !== "function" || typeof env.HEALTH_MONITOR_STATE.put !== "function") {
    missing.push("HEALTH_MONITOR_STATE");
  }
  return { ok: missing.length === 0, missing };
}

function nextState(previous, result, env, checkedAt) {
  const failureThreshold = Number.parseInt(env.FAILURE_THRESHOLD || "2", 10);
  const repeatSeconds = Number.parseInt(env.ALERT_REPEAT_SECONDS || "3600", 10);
  const previousStatus = previous?.status || "unknown";

  if (result.healthy) {
    return {
      status: "up",
      checkedAt,
      lastUpAt: checkedAt,
      lastDownAt: previous?.lastDownAt || null,
      lastAlertAt: previous?.lastAlertAt || null,
      consecutiveFailures: 0,
      httpStatus: result.httpStatus,
      error: null,
      detail: null,
      alertType: previousStatus === "down" ? "recovered" : null,
      shouldAlert: previousStatus === "down",
    };
  }

  const consecutiveFailures = (previous?.consecutiveFailures || 0) + 1;
  const lastAlertAt = previous?.lastAlertAt || null;
  const repeatDue = !lastAlertAt || Date.parse(checkedAt) - Date.parse(lastAlertAt) >= repeatSeconds * 1000;
  const thresholdReached = consecutiveFailures >= failureThreshold;
  const firstDownAlert = previousStatus !== "down" && thresholdReached;
  const repeatedDownAlert = previousStatus === "down" && thresholdReached && repeatDue;
  const shouldAlert = firstDownAlert || repeatedDownAlert;

  return {
    status: thresholdReached ? "down" : "degraded",
    checkedAt,
    lastUpAt: previous?.lastUpAt || null,
    lastDownAt: thresholdReached ? checkedAt : previous?.lastDownAt || null,
    lastAlertAt,
    consecutiveFailures,
    httpStatus: result.httpStatus,
    error: result.error,
    detail: result.detail || null,
    alertType: shouldAlert ? (firstDownAlert ? "down" : "still_down") : null,
    shouldAlert,
  };
}

async function readState(env) {
  const raw = await env.HEALTH_MONITOR_STATE.get(STATE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_error) {
    return null;
  }
}

async function sendSlackAlert(env, state) {
  if (!env.SLACK_WEBHOOK_URL) {
    console.warn("SLACK_WEBHOOK_URL is not configured; health alert skipped.");
    return;
  }

  const payload = buildSlackPayload(env, state);
  const response = await fetch(env.SLACK_WEBHOOK_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error(`Slack webhook failed with HTTP ${response.status}`);
  }
}

function buildSlackPayload(env, state) {
  const serviceName = env.SERVICE_NAME || "BuddyStudy backend";
  const environmentName = env.ENVIRONMENT_NAME || "production";
  const isRecovery = state.alertType === "recovered";
  const title = isRecovery ? `${serviceName} recovered` : `${serviceName} is down`;
  const emoji = isRecovery ? ":white_check_mark:" : ":rotating_light:";
  const outageDuration = state.lastDownAt ? formatElapsed(state.lastDownAt, state.checkedAt) : "unknown";

  return {
    text: `${emoji} ${title}`,
    blocks: [
      {
        type: "header",
        text: { type: "plain_text", text: title },
      },
      {
        type: "section",
        fields: [
          { type: "mrkdwn", text: `*Environment*\n${environmentName}` },
          { type: "mrkdwn", text: `*Status*\n${state.status}` },
          { type: "mrkdwn", text: `*URL*\n${env.HEALTHCHECK_URL}` },
          { type: "mrkdwn", text: `*Checked at*\n${state.checkedAt}` },
          { type: "mrkdwn", text: `*Failures*\n${state.consecutiveFailures}` },
          { type: "mrkdwn", text: `*Down since*\n${state.lastDownAt || "unknown"}` },
          { type: "mrkdwn", text: `*Last up*\n${state.lastUpAt || "unknown"}` },
          { type: "mrkdwn", text: `*Duration*\n${outageDuration}` },
          { type: "mrkdwn", text: `*Error*\n${state.error || "none"}` },
          { type: "mrkdwn", text: `*Detail*\n${state.detail || "none"}` },
        ],
      },
    ],
  };
}

function formatElapsed(startIso, endIso) {
  const start = Date.parse(startIso);
  const end = Date.parse(endIso);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return "unknown";
  const totalSeconds = Math.floor((end - start) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export const internals = {
  buildSlackPayload,
  checkHealth,
  formatElapsed,
  healthcheckTimeoutMs,
  isAuthorizedManualCheck,
  nextState,
  summarizeHealthJson,
  validateEnv,
};
