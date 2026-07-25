import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const config = await readFile(new URL("../nginx.conf", import.meta.url), "utf8");

test("Loki proxy requests uncompressed responses", () => {
  const lokiLocation = config.match(/location \/loki\/ \{([\s\S]*?)\n  \}/)?.[1];

  assert.ok(lokiLocation, "Loki proxy location must exist");
  assert.match(lokiLocation, /proxy_set_header Accept-Encoding "";/);
});

test("monitoring gateway preserves the public scheme from its upstream proxy", () => {
  assert.match(config, /map \$http_x_forwarded_proto \$public_forwarded_proto/);
  assert.match(config, /default \$http_x_forwarded_proto;/);
  assert.match(config, /"" https;/);
  assert.match(config, /proxy_set_header X-Forwarded-Proto \$public_forwarded_proto;/);
  assert.doesNotMatch(config, /proxy_set_header X-Forwarded-Proto \$scheme;/);
});

test("monitoring gateway records a bounded access audit without request bodies", () => {
  assert.match(config, /log_format monitoring_access escape=json/);
  assert.match(config, /"event":"monitoring_access"/);
  assert.match(config, /"user":"\$remote_user"/);
  assert.match(config, /access_log \/var\/log\/nginx\/monitoring-access\.log monitoring_access/);
  assert.match(config, /GET:\/\(index\|performance\|system\|testzone\|audit\|settings\)/);
  assert.match(config, /testzone\/api/);
  assert.doesNotMatch(config, /requestBody/);
  assert.doesNotMatch(config, /\$http_authorization/);
});
