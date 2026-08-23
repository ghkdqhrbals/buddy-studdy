import path from "node:path";

function integer(value, fallback) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadConfig(env = process.env) {
  const dataDir = path.resolve(env.TESTZONE_DATA_DIR || "./data");
  return {
    port: integer(env.TESTZONE_PORT, 3020),
    dataDir,
    maxVus: Math.min(integer(env.TESTZONE_MAX_VUS, 1000), 1000),
    maxTargetRps: Math.min(integer(env.TESTZONE_MAX_TARGET_RPS, 3000), 3000),
    maxDurationSeconds: integer(env.TESTZONE_MAX_DURATION_SECONDS, 3600),
    maxConcurrentRuns: integer(env.TESTZONE_MAX_CONCURRENT_RUNS, 1),
    grafanaBaseUrl: env.TESTZONE_GRAFANA_URL || "https://grafana.lowfidev.cloud",
    influx: {
      url: env.TESTZONE_INFLUX_URL || "http://buddystudy-testzone-influxdb:8086",
      org: env.TESTZONE_INFLUX_ORG || "buddystudy",
      bucket: env.TESTZONE_INFLUX_BUCKET || "testzone",
      token: env.TESTZONE_INFLUX_TOKEN || "",
    },
    componentPassword: env.TESTZONE_COMPONENT_PASSWORD || "testzone-local-only",
    deploymentIngestToken: env.MONITORING_DEPLOYMENT_INGEST_TOKEN || "",
  };
}
