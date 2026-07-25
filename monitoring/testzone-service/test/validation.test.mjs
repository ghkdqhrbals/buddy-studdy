import test from "node:test";
import assert from "node:assert/strict";
import {
  durationToSeconds,
  extractScenarioDefinitions,
  validateScript,
  ValidationError,
} from "../src/validation.mjs";

const VALID_SCRIPT = `
import http from "k6/http";
export const testConfig = {
  name: "Studies API",
  targetUrl: "https://api.ghkdqhrbals.org",
};
export const options = { vus: 10, duration: "30s" };
export default function () {
  http.get(\`\${testConfig.targetUrl}/api/v1/studies\`);
}
`;

test("durationToSeconds parses supported k6 durations", () => {
  assert.equal(durationToSeconds("30s"), 30);
  assert.equal(durationToSeconds("5m"), 300);
  assert.equal(durationToSeconds("1h"), 3600);
  assert.equal(durationToSeconds("bad"), null);
});

test("validateScript accepts a bounded k6 script", () => {
  const validation = validateScript(VALID_SCRIPT, {
    maxVus: 1000,
  });
  assert.equal(validation.valid, true);
  assert.deepEqual(validation.execution, {
    duration: "30s",
    durationSeconds: 30,
    vus: 10,
    maxVus: 10,
    targetRps: 0,
    scenarios: [{
      name: "default",
      executor: "constant-vus",
      exec: "default",
      rate: 0,
      timeUnit: "1s",
      targetRps: 0,
      duration: "30s",
      durationSeconds: 30,
      startTime: "0s",
      startTimeSeconds: 0,
      preAllocatedVUs: 10,
      maxVus: 10,
      vus: 10,
    }],
    targetUrl: "https://api.ghkdqhrbals.org",
    name: "Studies API",
  });
});

test("validateScript preserves concurrent k6 scenario plans", () => {
  const code = VALID_SCRIPT.replace(
    'export const options = { vus: 10, duration: "30s" };',
    `export const options = {
      scenarios: {
        publicQuestions: {
          executor: "constant-arrival-rate",
          exec: "readPublicQuestions",
          rate: 50,
          timeUnit: "1s",
          duration: "30s",
          preAllocatedVUs: 10,
          maxVUs: 30,
        },
        studySearch: {
          executor: "constant-arrival-rate",
          exec: "searchStudies",
          rate: 20,
          timeUnit: "1s",
          duration: "30s",
          preAllocatedVUs: 5,
          maxVUs: 20,
        },
      },
    };`,
  );
  const validation = validateScript(code, {
    maxVus: 1000,
    maxTargetRps: 3000,
  });

  assert.equal(validation.execution.duration, "30s");
  assert.equal(validation.execution.durationSeconds, 30);
  assert.equal(validation.execution.targetRps, 70);
  assert.equal(validation.execution.vus, 15);
  assert.equal(validation.execution.maxVus, 50);
  assert.deepEqual(
    extractScenarioDefinitions(code).map(({ name, exec, targetRps, maxVus }) =>
      ({ name, exec, targetRps, maxVus })),
    [
      { name: "publicQuestions", exec: "readPublicQuestions", targetRps: 50, maxVus: 30 },
      { name: "studySearch", exec: "searchStudies", targetRps: 20, maxVus: 20 },
    ],
  );
});

test("scenario capacity uses the peak overlap instead of summing sequential schedules", () => {
  const code = VALID_SCRIPT.replace(
    'export const options = { vus: 10, duration: "30s" };',
    `export const options = {
      scenarios: {
        first: {
          executor: "constant-arrival-rate",
          rate: 200,
          timeUnit: "1s",
          duration: "30s",
          preAllocatedVUs: 300,
          maxVUs: 800,
        },
        second: {
          executor: "constant-arrival-rate",
          rate: 200,
          timeUnit: "1s",
          startTime: "30s",
          duration: "30s",
          preAllocatedVUs: 300,
          maxVUs: 800,
        },
      },
    };`,
  );

  const validation = validateScript(code, {
    maxVus: 1000,
    maxTargetRps: 3000,
  });

  assert.equal(validation.execution.durationSeconds, 60);
  assert.equal(validation.execution.targetRps, 200);
  assert.equal(validation.execution.vus, 300);
  assert.equal(validation.execution.maxVus, 800);
});

test("validateScript rejects remote imports and excess VUs", () => {
  assert.throws(
    () => validateScript(`${VALID_SCRIPT}\nimport helper from "https://example.com/helper.js";\nexport const extra = { maxVUs: 1001 };`, { maxVus: 1000 }),
    (error) => error instanceof ValidationError && error.details.length >= 2,
  );
});

test("validateScript requires bounded script-owned options and enforces rate limits", () => {
  assert.throws(
    () => validateScript("export default function () {}", { maxVus: 1000 }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("export const options")),
  );
  assert.throws(
    () => validateScript(VALID_SCRIPT.replace("vus: 10", "rate: 3001, vus: 10"), {
      maxVus: 1000,
      maxTargetRps: 3000,
    }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("3001 RPS")),
  );
  assert.throws(
    () => validateScript(VALID_SCRIPT.replace('duration: "30s"', 'duration: "0s"'), {
      maxVus: 1000,
      maxTargetRps: 3000,
    }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("greater than zero")),
  );
});

test("validateScript requires a script-owned target URL", () => {
  assert.throws(
    () => validateScript(VALID_SCRIPT.replace(/export const testConfig[\s\S]+?};\n/, ""), {
      maxVus: 1000,
    }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("export const testConfig")),
  );
});

test("validateScript accepts any absolute HTTP or HTTPS target", () => {
  const validation = validateScript(
    VALID_SCRIPT.replaceAll("api.ghkdqhrbals.org", "www.google.com"),
    { maxVus: 1000 },
  );
  assert.equal(validation.execution.targetUrl, "https://www.google.com");
});
