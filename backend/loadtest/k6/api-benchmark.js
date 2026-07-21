import http from "k6/http";
import { check } from "k6";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:18080";
const scenario = __ENV.SCENARIO || "health";
const vus = Number(__ENV.VUS || 50);
const targetRps = Number(__ENV.TARGET_RPS || 0);
const duration = __ENV.DURATION || "30s";
const requestTimeout = __ENV.REQUEST_TIMEOUT || "5s";
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
  discardResponseBodies: true,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function requestDefinition() {
  const commonParams = {
    timeout: requestTimeout,
  };
  switch (scenario) {
    case "health":
      return {
        path: "/health",
        params: { ...commonParams, tags: { endpoint: "health" } },
      };
    case "public-questions":
      return {
        path: "/api/v1/public/questions?limit=20&offset=0&language=ko",
        params: {
          ...commonParams,
          tags: { endpoint: "public-questions" },
        },
      };
    case "studies":
      if (!__ENV.ACCESS_TOKEN) {
        throw new Error("ACCESS_TOKEN is required for the studies scenario.");
      }
      return {
        path: "/api/v1/studies?limit=100&offset=0",
        params: {
          ...commonParams,
          headers: { Authorization: `Bearer ${__ENV.ACCESS_TOKEN}` },
          tags: { endpoint: "studies" },
        },
      };
    default:
      throw new Error(`Unsupported SCENARIO: ${scenario}`);
  }
}

const request = requestDefinition();

export default function () {
  const response = http.get(`${baseUrl}${request.path}`, request.params);
  check(response, {
    [`${scenario} status is 200`]: (result) => result.status === 200,
  });
}

export function handleSummary(data) {
  const output = __ENV.SUMMARY_PATH || `results/${scenario}.json`;
  return {
    [output]: JSON.stringify(data, null, 2),
  };
}
