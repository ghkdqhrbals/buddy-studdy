import test from "node:test";
import assert from "node:assert/strict";
import {
  durationToSeconds,
  normalizeRunOptions,
  validateScript,
  validateTargetHost,
  ValidationError,
} from "../src/validation.mjs";

const VALID_SCRIPT = `
import http from "k6/http";
export const options = { vus: 10 };
export default function () {
  http.get(\`\${__ENV.BASE_URL}/api/v1/studies\`);
}
`;

test("durationToSeconds parses supported k6 durations", () => {
  assert.equal(durationToSeconds("30s"), 30);
  assert.equal(durationToSeconds("5m"), 300);
  assert.equal(durationToSeconds("1h"), 3600);
  assert.equal(durationToSeconds("bad"), null);
});

test("validateScript accepts a bounded k6 script", () => {
  assert.equal(validateScript(VALID_SCRIPT, { maxVus: 1000 }).valid, true);
});

test("validateScript rejects remote imports and excess VUs", () => {
  assert.throws(
    () => validateScript(`${VALID_SCRIPT}\nimport helper from "https://example.com/helper.js";\nexport const extra = { maxVUs: 1001 };`, { maxVus: 1000 }),
    (error) => error instanceof ValidationError && error.details.length >= 2,
  );
});

test("target allowlist accepts subdomains and rejects unrelated hosts", () => {
  assert.equal(
    validateTargetHost("https://api.ghkdqhrbals.org/", ["ghkdqhrbals.org"]),
    "https://api.ghkdqhrbals.org",
  );
  assert.throws(() => validateTargetHost("https://example.com", ["ghkdqhrbals.org"]), ValidationError);
});

test("run options enforce the 1000 VU ceiling", () => {
  const config = { maxVus: 1000, maxTargetRps: 3000, maxDurationSeconds: 3600 };
  assert.deepEqual(
    normalizeRunOptions({ duration: "1m", vus: 100, maxVus: 1000, targetRps: 2000 }, config),
    { duration: "1m", durationSeconds: 60, vus: 100, maxVus: 1000, targetRps: 2000 },
  );
  assert.throws(() => normalizeRunOptions({ vus: 1001, maxVus: 1001 }, config), ValidationError);
  assert.throws(
    () => normalizeRunOptions({ vus: 100, maxVus: 1000, targetRps: 3001 }, config),
    ValidationError,
  );
});
