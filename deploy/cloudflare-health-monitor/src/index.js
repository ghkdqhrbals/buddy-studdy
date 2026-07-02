const STATE_KEY = "backend-health-state";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      const state = await readState(env);
      return json({ ok: true, state });
    }
    if (request.method === "POST" && url.pathname === "/check") {
      if (!isAuthorizedManualCheck(request, env)) {
        return json({ ok: false, error: "Unauthorized." }, { status: 401 });
      }
      const state = await runHealthCheck(env, Date.now());
      return json({ ok: state.status === "up", state });
    }
    return json({ ok: false, error: "Not found." }, { status: 404 });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(runHealthCheck(env, event.scheduledTime));
  },
};

async function runHealthCheck(env, scheduledTime) {
  const checkedAt = new Date(scheduledTime || Date.now()).toISOString();
  const previous = await readState(env);
  const result = await checkHealth(env.HEALTHCHECK_URL, env);
  let next = nextState(previous, result, env, checkedAt);
  let slackAlertError = null;

  if (next.shouldAlert) {
    try {
      await sendSlackAlert(env, next);
      next = { ...next, lastAlertAt: checkedAt };
    } catch (error) {
      slackAlertError = error instanceof Error ? error.message : String(error);
      next = { ...next, lastAlertAt: previous?.lastAlertAt || null };
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
      alertSent: next.shouldAlert && !slackAlertError,
      slackAlertError,
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
      return `${name}${message}`;
    });
  if (failed.length === 0) return truncate(JSON.stringify(body), 900);
  return truncate(failed.join("; "), 900);
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
          { type: "mrkdwn", text: `*Error*\n${state.error || "none"}` },
          { type: "mrkdwn", text: `*Detail*\n${state.detail || "none"}` },
        ],
      },
    ],
  };
}

export const internals = {
  buildSlackPayload,
  checkHealth,
  healthcheckTimeoutMs,
  isAuthorizedManualCheck,
  nextState,
  summarizeHealthJson,
};
