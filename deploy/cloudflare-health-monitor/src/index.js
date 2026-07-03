const STATE_KEY = "backend-health-state";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      const config = validateEnv(env);
      if (!config.ok) {
        return json({ ok: false, error: "Configuration error.", missingConfig: config.missing }, { status: 500 });
      }
      const stateResult = await readStateSafely(env);
      if (!stateResult.ok) {
        return json(
          { ok: false, error: "Health monitor state read failed.", message: stateResult.error },
          { status: 500 },
        );
      }
      const state = rootVisibleState(stateResult.state, env);
      return json({ ok: state?.status === "up", monitor: monitorMetadata(env), state }, { status: rootStatusCode(state) });
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
      return json({ ok: state.status === "up", monitor: monitorMetadata(env), state });
    }
    return json({ ok: false, error: "Not found." }, { status: 404 });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(runHealthCheckSafely(env, event.scheduledTime));
  },
};

async function readStateSafely(env) {
  try {
    return { ok: true, state: await readState(env), error: null };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(
      JSON.stringify({
        message: "health_monitor_state_read_failed",
        error: message,
      }),
    );
    return { ok: false, state: null, error: message };
  }
}

function monitorMetadata(env) {
  return {
    service: env.SERVICE_NAME || "BuddyStudy backend",
    environment: env.ENVIRONMENT_NAME || "production",
    healthcheckUrl: env.HEALTHCHECK_URL,
    observabilityUrl: nonBlankString(env.OBSERVABILITY_URL) ? env.OBSERVABILITY_URL.trim() : null,
  };
}

async function runHealthCheckSafely(env, scheduledTime) {
  try {
    return { ok: true, state: await runHealthCheck(env, scheduledTime), error: null };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const checkedAt = new Date(scheduledTime || Date.now()).toISOString();
    let state = {
      status: "monitor_error",
      checkedAt,
      lastUpAt: null,
      lastDownAt: null,
      lastAlertAt: null,
      consecutiveFailures: 0,
      httpStatus: null,
      error: message,
      detail: null,
      alertType: "monitor_error",
      shouldAlert: false,
      alertSent: false,
      slackAlertError: null,
    };
    if (env.SLACK_WEBHOOK_URL) {
      try {
        await sendSlackAlert(env, { ...state, alertType: "monitor_error" });
        state = { ...state, alertSent: true, slackAlertError: null };
      } catch (slackError) {
        const slackAlertError = slackError instanceof Error ? slackError.message : String(slackError);
        state = { ...state, alertSent: false, slackAlertError };
        console.error(
          JSON.stringify({
            message: "health_monitor_error_slack_alert_failed",
            healthUrl: env.HEALTHCHECK_URL,
            error: slackAlertError,
          }),
        );
      }
    }
    console.error(
      JSON.stringify({
        message: "health_monitor_execution_failed",
        error: message,
      }),
    );
    await writeStateIfAvailable(env, state);
    return { ok: false, state, error: message };
  }
}

async function runHealthCheck(env, scheduledTime) {
  const checkedAt = new Date(scheduledTime || Date.now()).toISOString();
  const config = validateEnv(env);
  if (!config.ok) {
    const previous = await readState(env);
    const shouldAlert = Boolean(env.SLACK_WEBHOOK_URL) && shouldAlertMonitorError(previous, env, checkedAt);
    let state = {
      status: "config_error",
      checkedAt,
      lastUpAt: null,
      lastDownAt: null,
      lastAlertAt: previous?.lastAlertAt || null,
      consecutiveFailures: 0,
      httpStatus: null,
      error: `Missing monitor configuration: ${config.missing.join(", ")}`,
      detail: null,
      alertType: env.SLACK_WEBHOOK_URL ? "monitor_error" : null,
      shouldAlert,
      alertSent: false,
      slackAlertError: null,
    };
    if (shouldAlert) {
      try {
        await sendSlackAlert(env, state);
        state = { ...state, lastAlertAt: checkedAt, shouldAlert: false, alertSent: true, slackAlertError: null };
      } catch (slackError) {
        const slackAlertError = slackError instanceof Error ? slackError.message : String(slackError);
        state = { ...state, alertSent: false, slackAlertError };
        console.error(
          JSON.stringify({
            message: "health_monitor_config_error_slack_alert_failed",
            healthUrl: env.HEALTHCHECK_URL,
            error: slackAlertError,
          }),
        );
      }
    }
    console.error(
      JSON.stringify({
        message: "health_monitor_configuration_error",
        missingConfig: config.missing,
      }),
    );
    await writeStateIfAvailable(env, state);
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

  try {
    await env.HEALTH_MONITOR_STATE.put(STATE_KEY, JSON.stringify(next));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(
      JSON.stringify({
        message: "health_monitor_state_write_failed",
        error: message,
      }),
    );
    if (next.alertSent === true) {
      next = { ...next, stateWriteError: message };
    } else {
      throw error;
    }
  }

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

function shouldAlertMonitorError(previous, env, checkedAt) {
  if (!previous || (previous.status !== "config_error" && previous.status !== "monitor_error")) return true;
  if (!previous.lastAlertAt) return true;
  const checkedAtMs = Date.parse(checkedAt);
  const lastAlertAtMs = Date.parse(previous.lastAlertAt);
  if (!Number.isFinite(checkedAtMs) || !Number.isFinite(lastAlertAtMs)) return true;
  const repeatSeconds = boundedInteger(env.ALERT_REPEAT_SECONDS, 3600, 300, 86_400);
  return checkedAtMs - lastAlertAtMs >= repeatSeconds * 1000;
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
      const readiness = await successfulReadinessResult(response);
      if (!readiness.valid) {
        return {
          healthy: false,
          httpStatus: response.status,
          error: readiness.error,
          detail: readiness.detail,
        };
      }
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

async function successfulReadinessResult(response) {
  const contentType = response.headers.get("Content-Type") || "";
  if (!contentType.includes("application/json")) {
    return {
      valid: false,
      error: "Readiness response contract invalid",
      detail: "Expected JSON readiness body with ok:true.",
    };
  }
  const text = await response.text().catch(() => "");
  if (!text) {
    return {
      valid: false,
      error: "Readiness response contract invalid",
      detail: "Expected JSON readiness body with ok:true.",
    };
  }
  try {
    const body = JSON.parse(text);
    if (body?.ok === true) return { valid: true, error: null, detail: null };
    return {
      valid: false,
      error: body?.ok === false ? "Readiness reported ok=false" : "Readiness response contract invalid",
      detail: body?.ok === false ? summarizeHealthJson(body) : "Expected JSON readiness body with ok:true.",
    };
  } catch (_error) {
    return {
      valid: false,
      error: "Readiness response contract invalid",
      detail: "Expected JSON readiness body with ok:true.",
    };
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
      const details = summarizeCheckDetails(value);
      return `${name}${message}${details ? ` [${details}]` : ""}`;
    });
  if (failed.length === 0) return truncate(JSON.stringify(body), 900);
  return truncate(failed.join("; "), 900);
}

function summarizeCheckDetails(check) {
  const details = check?.details;
  const durationMs = Number.isFinite(check?.durationMs) ? check.durationMs : null;
  if (durationMs == null && (!details || typeof details !== "object")) return "";
  if (!details || typeof details !== "object") {
    return durationMs == null ? "" : `duration=${durationMs}ms`;
  }
  const parts = [];
  if (durationMs != null) {
    parts.push(`duration=${durationMs}ms`);
  }
  if (Array.isArray(details.missingJobs) && details.missingJobs.length > 0) {
    parts.push(`missingJobs=${details.missingJobs.join(",")}`);
  }
  if (Array.isArray(details.disabledJobs) && details.disabledJobs.length > 0) {
    parts.push(`disabledJobs=${details.disabledJobs.join(",")}`);
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
        const runId = job.latestRunId != null ? ` runId=${job.latestRunId}` : "";
        const staleFor = Number.isFinite(job.staleForSeconds) ? ` staleFor=${job.staleForSeconds}s` : "";
        return `${name}${runId}${staleFor}`;
      })
      .filter(Boolean)
      .join(",");
    if (staleJobs) parts.push(`staleJobs=${staleJobs}`);
  }
  if (Array.isArray(details.failedJobs) && details.failedJobs.length > 0) {
    const failedJobs = details.failedJobs
      .map((job) => {
        if (!job || typeof job !== "object") return "";
        const name = job.jobName || "unknown";
        const runId = job.latestRunId != null ? ` runId=${job.latestRunId}` : "";
        const error = typeof job.latestErrorMessage === "string" && job.latestErrorMessage.trim() ? ` error=${job.latestErrorMessage.trim()}` : "";
        return `${name}${runId}${error}`;
      })
      .filter(Boolean)
      .join(",");
    if (failedJobs) parts.push(`failedJobs=${failedJobs}`);
  }
  if (Array.isArray(details.stuckJobs) && details.stuckJobs.length > 0) {
    const stuckJobs = details.stuckJobs
      .map((job) => {
        if (!job || typeof job !== "object") return "";
        const name = job.jobName || "unknown";
        const runId = job.latestRunId != null ? ` runId=${job.latestRunId}` : "";
        const runningFor = Number.isFinite(job.runningForSeconds) ? ` runningFor=${job.runningForSeconds}s` : "";
        const timeout = Number.isFinite(job.timeoutSeconds) ? ` timeout=${job.timeoutSeconds}s` : "";
        return `${name}${runId}${runningFor}${timeout}`;
      })
      .filter(Boolean)
      .join(",");
    if (stuckJobs) parts.push(`stuckJobs=${stuckJobs}`);
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
      "Cache-Control": "no-store",
      ...(init.headers || {}),
    },
  });
}

function rootStatusCode(state) {
  if (!state) return 503;
  if (state.status === "up" || state.status === "degraded") return 200;
  return 503;
}

function rootVisibleState(state, env, nowMs = Date.now()) {
  if (!state?.checkedAt) return state;
  if (state.status !== "up" && state.status !== "degraded") return state;
  const checkedAtMs = Date.parse(state.checkedAt);
  if (!Number.isFinite(checkedAtMs)) return state;
  const staleAfterSeconds = statusStaleAfterSeconds(env);
  const staleForSeconds = Math.floor((nowMs - checkedAtMs) / 1000);
  if (staleForSeconds <= staleAfterSeconds) return state;
  return {
    ...state,
    status: "stale",
    storedStatus: state.status,
    staleForSeconds,
    staleAfterSeconds,
  };
}

function statusStaleAfterSeconds(env) {
  return boundedInteger(env?.STATUS_STALE_AFTER_SECONDS, 180, 60, 3600);
}

function isAuthorizedManualCheck(request, env) {
  if (!env.MANUAL_CHECK_TOKEN) return false;
  const expected = `Bearer ${env.MANUAL_CHECK_TOKEN}`;
  return request.headers.get("Authorization") === expected;
}

function validateEnv(env) {
  const missing = [];
  if (!nonBlankString(env.HEALTHCHECK_URL)) {
    missing.push("HEALTHCHECK_URL");
  } else if (!isHttpsUrl(env.HEALTHCHECK_URL)) {
    missing.push("HEALTHCHECK_URL_HTTPS");
  } else if (!isSchedulerReadinessUrl(env.HEALTHCHECK_URL)) {
    missing.push("HEALTHCHECK_URL_READINESS_PATH");
  } else if (environmentName(env) === "production" && healthcheckHost(env.HEALTHCHECK_URL) !== "api.ghkdqhrbals.org") {
    missing.push("HEALTHCHECK_URL_PRODUCTION_HOST");
  }
  if (!nonBlankString(env.SERVICE_NAME)) missing.push("SERVICE_NAME");
  if (!nonBlankString(env.ENVIRONMENT_NAME)) missing.push("ENVIRONMENT_NAME");
  if (!nonBlankString(env.SLACK_WEBHOOK_URL)) missing.push("SLACK_WEBHOOK_URL");
  if (!env.HEALTH_MONITOR_STATE || typeof env.HEALTH_MONITOR_STATE.get !== "function" || typeof env.HEALTH_MONITOR_STATE.put !== "function") {
    missing.push("HEALTH_MONITOR_STATE");
  }
  return { ok: missing.length === 0, missing };
}

function nonBlankString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function isSchedulerReadinessUrl(value) {
  try {
    return new URL(value).pathname === "/api/v1/health/readiness";
  } catch (_error) {
    return false;
  }
}

function isHttpsUrl(value) {
  try {
    return new URL(value).protocol === "https:";
  } catch (_error) {
    return false;
  }
}

function healthcheckHost(value) {
  try {
    return new URL(value).hostname;
  } catch (_error) {
    return "";
  }
}

function environmentName(env) {
  return typeof env.ENVIRONMENT_NAME === "string" ? env.ENVIRONMENT_NAME.trim().toLowerCase() : "";
}

function nextState(previous, result, env, checkedAt) {
  const failureThreshold = boundedInteger(env.FAILURE_THRESHOLD, 2, 1, 2);
  const repeatSeconds = boundedInteger(env.ALERT_REPEAT_SECONDS, 3600, 300, 86_400);
  const previousStatus = previous?.status || "unknown";

  if (result.healthy) {
    const retryRecoveryAlert = previous?.shouldAlert === true && previous?.alertType === "recovered";
    const recoveredFromDown = previousStatus === "down";
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
      alertType: recoveredFromDown || retryRecoveryAlert ? "recovered" : null,
      shouldAlert: recoveredFromDown || retryRecoveryAlert,
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
    lastDownAt: thresholdReached ? previous?.lastDownAt || checkedAt : previous?.lastDownAt || null,
    lastAlertAt,
    consecutiveFailures,
    httpStatus: result.httpStatus,
    error: result.error,
    detail: result.detail || null,
    alertType: shouldAlert ? (firstDownAlert ? "down" : "still_down") : null,
    shouldAlert,
  };
}

function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(Math.max(parsed, min), max);
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

async function writeStateIfAvailable(env, state) {
  if (!env.HEALTH_MONITOR_STATE || typeof env.HEALTH_MONITOR_STATE.put !== "function") return;
  try {
    await env.HEALTH_MONITOR_STATE.put(STATE_KEY, JSON.stringify(state));
  } catch (error) {
    console.error(
      JSON.stringify({
        message: "health_monitor_state_write_failed",
        error: error instanceof Error ? error.message : String(error),
      }),
    );
  }
}

async function sendSlackAlert(env, state) {
  if (!env.SLACK_WEBHOOK_URL) {
    console.warn("SLACK_WEBHOOK_URL is not configured; health alert skipped.");
    return;
  }

  const payload = buildSlackPayload(env, state);
  const timeoutMs = slackTimeoutMs(env);
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort("slack timeout"), timeoutMs);
  try {
    const response = await fetch(env.SLACK_WEBHOOK_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`Slack webhook failed with HTTP ${response.status}`);
    }
  } catch (error) {
    if (isAbortError(error)) {
      throw new Error(`Slack alert timed out after ${timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

function slackTimeoutMs(env) {
  const raw = Number.parseInt(env?.SLACK_TIMEOUT_MS || "5000", 10);
  return Number.isFinite(raw) ? Math.min(Math.max(raw, 1000), 15000) : 5000;
}

function buildSlackPayload(env, state) {
  const serviceName = env.SERVICE_NAME || "BuddyStudy backend";
  const environmentName = env.ENVIRONMENT_NAME || "production";
  const isRecovery = state.alertType === "recovered";
  const isMonitorError = state.alertType === "monitor_error";
  const title = isRecovery
    ? `${serviceName} recovered`
    : isMonitorError
      ? `${serviceName} health monitor error`
      : `${serviceName} is down`;
  const emoji = isRecovery ? ":white_check_mark:" : isMonitorError ? ":warning:" : ":rotating_light:";
  const outageDuration = state.lastDownAt ? formatElapsed(state.lastDownAt, state.checkedAt) : "unknown";
  const observabilityUrl = nonBlankString(env.OBSERVABILITY_URL) ? env.OBSERVABILITY_URL.trim() : null;
  const fields = [
    slackField("Environment", environmentName),
    slackField("Status", state.status),
    slackField("URL", env.HEALTHCHECK_URL),
    slackField("Checked at", state.checkedAt),
    slackField("HTTP status", state.httpStatus || "unknown"),
    slackField("Failures", state.consecutiveFailures),
    slackField("Down since", state.lastDownAt || "unknown"),
    slackField("Last up", state.lastUpAt || "unknown"),
    slackField("Duration", outageDuration),
    slackField("Error", state.error || "none"),
    slackField("Detail", state.detail || "none"),
  ];
  if (observabilityUrl) {
    fields.push(slackField("Observability", observabilityUrl));
  }
  const actionElements = [
    slackButton("Open health", env.HEALTHCHECK_URL),
    slackButton("Open observability", observabilityUrl),
  ].filter(Boolean);
  const blocks = [
    {
      type: "header",
      text: { type: "plain_text", text: title },
    },
    ...chunkFields(fields, 10).map((chunk) => ({
      type: "section",
      fields: chunk,
    })),
  ];
  if (actionElements.length > 0) {
    blocks.push({
      type: "actions",
      elements: actionElements,
    });
  }

  return {
    text: `${emoji} ${title}`,
    blocks,
  };
}

function slackButton(text, url) {
  if (!isHttpsUrl(url)) return null;
  return {
    type: "button",
    text: { type: "plain_text", text },
    url: url.trim(),
  };
}

function slackField(label, value) {
  const prefix = `*${label}*\n`;
  return {
    type: "mrkdwn",
    text: `${prefix}${truncate(value, 2_000 - prefix.length)}`,
  };
}

function chunkFields(fields, size) {
  const chunks = [];
  for (let index = 0; index < fields.length; index += size) {
    chunks.push(fields.slice(index, index + size));
  }
  return chunks;
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
  rootStatusCode,
  slackTimeoutMs,
  summarizeHealthJson,
  validateEnv,
};
