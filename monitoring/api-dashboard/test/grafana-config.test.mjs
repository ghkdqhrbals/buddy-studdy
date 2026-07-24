import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const composePath = path.resolve(testDirectory, "../../docker-compose.yml");
const gatewayPath = path.resolve(testDirectory, "../../grafana-gateway/nginx.conf");
const deployTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/deploy-macbookair-monitoring.yml",
);

test("Grafana Live accepts only the public Grafana origin", async () => {
  const [compose, deployTemplate] = await Promise.all([
    fs.readFile(composePath, "utf8"),
    fs.readFile(deployTemplatePath, "utf8"),
  ]);

  for (const source of [compose, deployTemplate]) {
    assert.match(
      source,
      /GF_LIVE_ALLOWED_ORIGINS(?::|=) ?https:\/\/grafana\.lowfidev\.cloud/,
    );
  }
});

test("Grafana gateway preserves the public HTTPS scheme from Cloudflare", async () => {
  const gateway = await fs.readFile(gatewayPath, "utf8");

  assert.match(gateway, /map \$http_x_forwarded_proto \$public_forwarded_proto/);
  assert.match(gateway, /default \$http_x_forwarded_proto;/);
  assert.match(gateway, /"" https;/);
  assert.match(gateway, /proxy_set_header X-Forwarded-Proto \$public_forwarded_proto;/);
  assert.doesNotMatch(gateway, /proxy_set_header X-Forwarded-Proto \$scheme;/);
});
