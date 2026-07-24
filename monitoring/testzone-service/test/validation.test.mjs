import test from "node:test";
import assert from "node:assert/strict";
import {
  durationToSeconds,
  validateScript,
  validateTargetHost,
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
    allowedTargetHosts: ["ghkdqhrbals.org"],
  });
  assert.equal(validation.valid, true);
  assert.deepEqual(validation.execution, {
    duration: "30s",
    durationSeconds: 30,
    vus: 10,
    maxVus: 10,
    targetRps: 0,
    targetUrl: "https://api.ghkdqhrbals.org",
    name: "Studies API",
  });
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
      allowedTargetHosts: ["ghkdqhrbals.org"],
    }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("export const testConfig")),
  );
  assert.throws(
    () => validateScript(VALID_SCRIPT.replace("api.ghkdqhrbals.org", "example.com"), {
      maxVus: 1000,
      allowedTargetHosts: ["ghkdqhrbals.org"],
    }),
    (error) => error instanceof ValidationError
      && error.details.some((detail) => detail.message.includes("allowlist")),
  );
});

test("target allowlist accepts subdomains and rejects unrelated hosts", () => {
  assert.equal(
    validateTargetHost("https://api.ghkdqhrbals.org/", ["ghkdqhrbals.org"]),
    "https://api.ghkdqhrbals.org",
  );
  assert.throws(() => validateTargetHost("https://example.com", ["ghkdqhrbals.org"]), ValidationError);
});
