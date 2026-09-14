import test from "node:test";
import assert from "node:assert/strict";
import {
  redactLogPayloadForExternalSharing,
  redactLogTextForExternalSharing,
} from "../scripts/log-output-redactor.mjs";

test("external log sharing recursively redacts credential fields", () => {
  const redacted = redactLogPayloadForExternalSharing({
    headers: {
      authorization: "Bearer access-token-value",
      "x-client-secret": "client-secret-value",
    },
    body: {
      password: "password-value",
      nested: [{ accessToken: "response-token-value" }],
      ordinary: "visible-value",
    },
  });

  assert.equal(redacted.headers.authorization, "[REDACTED]");
  assert.equal(redacted.headers["x-client-secret"], "[REDACTED]");
  assert.equal(redacted.body.password, "[REDACTED]");
  assert.equal(redacted.body.nested[0].accessToken, "[REDACTED]");
  assert.equal(redacted.body.ordinary, "visible-value");
});

test("external log sharing redacts credentials embedded in diagnostic text", () => {
  const redacted = redactLogTextForExternalSharing(
    'authorization="Basic dXNlcjpwYXNz" x-client-secret="client secret value" verificationCode="123 456" cookie="session=one; second=two" token=token-value',
  );

  assert.doesNotMatch(redacted, /dXNlcjpwYXNz/);
  assert.doesNotMatch(redacted, /client secret value/);
  assert.doesNotMatch(redacted, /123 456/);
  assert.doesNotMatch(redacted, /session=one/);
  assert.doesNotMatch(redacted, /second=two/);
  assert.doesNotMatch(redacted, /token-value/);
  assert.match(redacted, /REDACTED/);
});
