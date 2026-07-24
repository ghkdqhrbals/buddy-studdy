import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const manifest = JSON.parse(open("../scenarios.json"));
const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:18080";
const scenario = __ENV.SCENARIO || "public-questions";
const vus = Number(__ENV.VUS || 50);
const targetRps = Number(__ENV.TARGET_RPS || 0);
const duration = __ENV.DURATION || "30s";
const requestTimeout = __ENV.REQUEST_TIMEOUT || "5s";
const studiesLimit = Number(__ENV.STUDIES_LIMIT || 100);
const validateBody = (__ENV.VALIDATE_BODY || "false") === "true";
const strictValidation = (__ENV.STRICT_VALIDATION || "false") === "true";
const preAllocatedVUs = Number(
  __ENV.PRE_ALLOCATED_VUS || Math.max(100, Math.ceil(targetRps * 0.25)),
);
const maxVUs = Number(
  __ENV.MAX_VUS || Math.max(preAllocatedVUs * 2, targetRps),
);

const execution = targetRps > 0
  ? {
      executor: "constant-arrival-rate",
      rate: targetRps,
      timeUnit: "1s",
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: "5s",
    }
  : {
      executor: "constant-vus",
      vus,
      duration,
      gracefulStop: "5s",
    };

export const options = {
  scenarios: {
    api: execution,
  },
  discardResponseBodies: !validateBody,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: strictValidation
    ? { response_validation_failed: ["rate==0"] }
    : {},
};

const responseValidationFailed = new Rate("response_validation_failed");
const successfulRequestDuration = new Trend("successful_request_duration", true);
const successfulRequestCount = new Counter("successful_request_count");
const requestSucceeded = new Rate("request_succeeded");
const requestTimedOut = new Rate("request_timed_out");
const requestTimeoutCount = new Counter("request_timeout_count");

const scenarioDefinition = manifest.scenarios[scenario];
if (!scenarioDefinition) {
  throw new Error(`Unsupported SCENARIO: ${scenario}`);
}
if (
  scenarioDefinition.requests.some((request) => request.authenticated)
  && !__ENV.ACCESS_TOKEN
) {
  throw new Error(`ACCESS_TOKEN is required for the ${scenario} scenario.`);
}

function expandPath(path) {
  return path.replace("${STUDIES_LIMIT}", String(studiesLimit));
}

function chooseRequest() {
  if (scenarioDefinition.requests.length === 1) {
    return scenarioDefinition.requests[0];
  }
  const selected = Math.random() * 100;
  let cumulative = 0;
  for (const request of scenarioDefinition.requests) {
    cumulative += request.weight;
    if (selected < cumulative) {
      return request;
    }
  }
  return scenarioDefinition.requests[scenarioDefinition.requests.length - 1];
}

function readJsonPath(value, path) {
  return path.split(".").reduce(
    (current, key) => (current !== null && current !== undefined ? current[key] : undefined),
    value,
  );
}

export default function () {
  const request = chooseRequest();
  const headers = request.authenticated
    ? { Authorization: `Bearer ${__ENV.ACCESS_TOKEN}` }
    : {};
  const params = {
    timeout: requestTimeout,
    headers,
    tags: { scenario, endpoint: request.name },
    responseType: validateBody ? "text" : "none",
  };
  const response = http.request(
    request.method,
    `${baseUrl}${expandPath(request.path)}`,
    null,
    params,
  );
  const checks = {
    [`${request.name} status is ${request.expectedStatus}`]:
      (result) => result.status === request.expectedStatus,
  };
  if (validateBody) {
    let body;
    try {
      body = response.json();
    } catch (_error) {
      body = null;
    }
    checks[`${request.name} response is JSON`] = () => body !== null;
    for (const path of request.requiredJsonPaths || []) {
      checks[`${request.name} contains ${path}`] = () => readJsonPath(body, path) !== undefined;
    }
    for (const path of request.nonEmptyJsonPaths || []) {
      checks[`${request.name} ${path} is non-empty`] = () => {
        const value = readJsonPath(body, path);
        return Array.isArray(value) ? value.length > 0 : Boolean(value);
      };
    }
  }
  const valid = check(response, checks);
  const timedOut = response.error_code === 1050;
  const succeeded = valid && !timedOut;
  responseValidationFailed.add(!valid);
  requestSucceeded.add(succeeded);
  requestTimedOut.add(timedOut);
  if (timedOut) {
    requestTimeoutCount.add(1);
  }
  if (succeeded) {
    successfulRequestCount.add(1);
    successfulRequestDuration.add(response.timings.duration, {
      scenario,
      endpoint: request.name,
    });
  }
}

export function handleSummary(data) {
  const output = __ENV.SUMMARY_PATH || `results/${scenario}.json`;
  return {
    [output]: JSON.stringify(data, null, 2),
  };
}
