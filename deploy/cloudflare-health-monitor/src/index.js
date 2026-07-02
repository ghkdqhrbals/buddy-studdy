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
  const result = await checkHealth(env.HEALTHCHECK_URL);
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

async function checkHealth(url) {
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: { "Accept": "application/json" },
      cf: { cacheTtl: 0, cacheEverything: false },
    });
    if (response.ok) {
      return { healthy: true, httpStatus: response.status, error: null };
    }
    return {
      healthy: false,
      httpStatus: response.status,
      error: `HTTP ${response.status}`,
    };
  } catch (error) {
    return {
      healthy: false,
      httpStatus: null,
      error: error instanceof Error ? error.message : String(error),
    };
  }
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
        ],
      },
    ],
  };
}

export const internals = {
  buildSlackPayload,
  isAuthorizedManualCheck,
  nextState,
};
