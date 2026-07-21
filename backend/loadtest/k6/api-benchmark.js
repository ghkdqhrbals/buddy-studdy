import http from "k6/http";
import { check } from "k6";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:18080";
const scenario = __ENV.SCENARIO || "health";
const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || "30s";

export const options = {
  scenarios: {
    api: {
      executor: "constant-vus",
      vus,
      duration,
      gracefulStop: "5s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
  },
  discardResponseBodies: true,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function requestDefinition() {
  switch (scenario) {
    case "health":
      return { path: "/health", params: { tags: { endpoint: "health" } } };
    case "public-questions":
      return {
        path: "/api/v1/public/questions?limit=20&offset=0&language=ko",
        params: { tags: { endpoint: "public-questions" } },
      };
    case "studies":
      if (!__ENV.ACCESS_TOKEN) {
        throw new Error("ACCESS_TOKEN is required for the studies scenario.");
      }
      return {
        path: "/api/v1/studies?limit=100&offset=0",
        params: {
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
