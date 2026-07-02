import process from "node:process";

const monitorUrl = normalizeMonitorUrl(process.env.HEALTH_MONITOR_URL || process.argv[2]);
const token = process.env.MANUAL_CHECK_TOKEN || process.argv[3];
const allowDown = parseBoolean(process.env.ALLOW_DOWN);

if (!monitorUrl || !token) {
  console.error(
    [
      "Usage:",
      "  HEALTH_MONITOR_URL=https://<worker-host> MANUAL_CHECK_TOKEN=<token> npm run smoke",
      "  npm run smoke -- https://<worker-host> <token>",
      "",
      "Set ALLOW_DOWN=true to treat a backend-down monitor result as a successful smoke test.",
    ].join("\n"),
  );
  process.exit(1);
}

const response = await fetch(`${monitorUrl}/check`, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${token}`,
    Accept: "application/json",
  },
});
const body = await readJson(response);

console.log(JSON.stringify({ httpStatus: response.status, body }, null, 2));

if (!response.ok) {
  console.error(`Health monitor smoke check failed with HTTP ${response.status}.`);
  process.exit(1);
}

if (!body || typeof body !== "object" || !body.state) {
  console.error("Health monitor smoke check response did not include state.");
  process.exit(1);
}

if (body.ok === false && !allowDown) {
  console.error("Health monitor reached the Worker, but backend health is not up. Set ALLOW_DOWN=true if this is expected.");
  process.exit(2);
}

console.log(`Health monitor smoke check passed. Backend status: ${body.state.status}.`);

function normalizeMonitorUrl(value) {
  const trimmed = value?.trim().replace(/\/+$/, "");
  if (!trimmed) return "";
  if (!/^https?:\/\//.test(trimmed)) return `https://${trimmed}`;
  return trimmed;
}

async function readJson(response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (_error) {
    return { raw: text };
  }
}

function parseBoolean(value) {
  return ["1", "true", "yes", "y"].includes(String(value || "").toLowerCase());
}
