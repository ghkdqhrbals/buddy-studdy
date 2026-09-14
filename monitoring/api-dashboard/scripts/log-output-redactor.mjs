const EXTERNAL_SHARING_KEY_PARTS = [
  "authorization",
  "password",
  "passwd",
  "secret",
  "token",
  "privatekey",
  "apikey",
  "verificationcode",
  "cookie",
];

export function redactLogPayloadForExternalSharing(value) {
  if (Array.isArray(value)) {
    return value.map(redactLogPayloadForExternalSharing);
  }
  if (!value || typeof value !== "object") {
    return value;
  }
  return Object.fromEntries(Object.entries(value).map(([key, nestedValue]) => [
    key,
    isExternalSharingCredentialKey(key) ? "[REDACTED]" : redactLogPayloadForExternalSharing(nestedValue),
  ]));
}

export function redactLogTextForExternalSharing(value) {
  return String(value ?? "")
    .replace(/\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{12,}\b/g, "[REDACTED_OPENAI_KEY]")
    .replace(quotedExternalFieldValuePattern('"'), '$1"[REDACTED]"')
    .replace(quotedExternalFieldValuePattern("'"), "$1'[REDACTED]'")
    .replace(
      /((?:authorization|proxy[_-]?authorization|password|passwd|token|secret|client[_-]?secret|x[_-]?client[_-]?secret|api[_-]?key|verification[_-]?code|cookie|set[_-]?cookie)["']?\s*[:=]\s*)([^\s,}\]]+)/gi,
      "$1[REDACTED]",
    )
    .replace(/\b(Bearer|Basic)\s+[^\s"',}\]]+/gi, "$1 [REDACTED]")
    .replace(/\b[0-9a-f]{64}\b/gi, "[REDACTED_64_HEX]");
}

function isExternalSharingCredentialKey(key) {
  const normalized = String(key).replaceAll(/[^A-Za-z0-9]/g, "").toLowerCase();
  return EXTERNAL_SHARING_KEY_PARTS.some((part) => normalized.includes(part));
}

function quotedExternalFieldValuePattern(quote) {
  const field = "(?:authorization|proxy[_-]?authorization|password|passwd|token|secret|client[_-]?secret|x[_-]?client[_-]?secret|api[_-]?key|verification[_-]?code|cookie|set[_-]?cookie)";
  const escapedQuote = quote === '"' ? '\\"' : "\\'";
  return new RegExp(`(${field}["']?\\s*[:=]\\s*)${escapedQuote}(?:\\\\.|[^${escapedQuote}\\\\])*${escapedQuote}`, "gi");
}
