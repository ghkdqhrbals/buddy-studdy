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
  const auditLogFormat = config.match(/log_format monitoring_access[\s\S]*?';/)?.[0];

  assert.ok(auditLogFormat, "Monitoring access log format must exist");
  assert.match(config, /log_format monitoring_access escape=json/);
  assert.match(config, /"event":"monitoring_access"/);
  assert.match(config, /"user":"\$remote_user"/);
  assert.match(config, /access_log \/var\/log\/nginx\/monitoring-access\.log monitoring_access/);
  assert.match(config, /administrators\|feedback\|jobs\|streams\|deployments\|service-status\|login/);
  assert.match(config, /testzone\/api/);
  assert.match(config, /backend\/api/);
  assert.doesNotMatch(auditLogFormat, /requestBody/);
  assert.doesNotMatch(auditLogFormat, /\$http_authorization/);
});

test("monitoring proxies admin APIs through the same authenticated origin", () => {
  const backendLocation = config.match(/location \^~ \/backend\/api\/v1\/admin\/ \{([\s\S]*?)\n  \}/)?.[1];
  assert.ok(backendLocation, "Backend admin proxy location must exist");
  assert.match(backendLocation, /proxy_pass https:\/\/api\.ghkdqhrbals\.org\/api\/v1\/admin\//);
  assert.match(backendLocation, /proxy_ssl_server_name on/);
  assert.match(backendLocation, /proxy_set_header Authorization \$http_authorization;/);
  assert.doesNotMatch(config, /location \/backend\/api\/ \{/);
});

test("monitoring uses the backend admin session instead of browser Basic Auth", () => {
  const sessionLocation = config.match(/location = \/_admin_session \{([\s\S]*?)\n  \}/)?.[1];
  const testzoneLocation = config.match(/location \/testzone\/api\/ \{([\s\S]*?)\n  \}/)?.[1];
  const lokiLocation = config.match(/location \/loki\/ \{([\s\S]*?)\n  \}/)?.[1];
  assert.ok(sessionLocation, "Admin session validation location must exist");
  assert.match(sessionLocation, /\/api\/v1\/admin\/session/);
  assert.match(sessionLocation, /proxy_pass_request_body off/);
  assert.match(sessionLocation, /proxy_set_header Authorization \$http_authorization/);
  assert.match(testzoneLocation, /auth_request \/_admin_session/);
  assert.match(lokiLocation, /auth_request \/_admin_session/);
  assert.doesNotMatch(config, /auth_basic/);
  assert.doesNotMatch(config, /htpasswd/);
});

test("deployment event ingestion keeps its service bearer credential", () => {
  const ingestLocation = config.match(/location = \/deployment-events\/events \{([\s\S]*?)\n  \}/)?.[1];
  assert.ok(ingestLocation, "Deployment event ingest location must exist");
  assert.match(ingestLocation, /limit_except POST/);
  assert.match(ingestLocation, /\/api\/deployments\/events/);
  assert.match(ingestLocation, /proxy_set_header Authorization \$http_authorization;/);
});

test("existing monitoring gateway exposes RedisStreamScope on its dedicated listener", () => {
  const redisServer = config.match(
    /server \{\n  listen 8082;([\s\S]*?)proxy_pass \$redisstreamscope;([\s\S]*?)\n\}/,
  )?.[0];

  assert.ok(redisServer, "RedisStreamScope server must exist on listener 8082");
  assert.match(redisServer, /buddystudy-redisstreamscope:8080/);
  assert.match(redisServer, /proxy_buffering off/);
  assert.match(redisServer, /proxy_set_header X-Forwarded-Proto \$public_forwarded_proto/);
  assert.match(redisServer, /proxy_set_header Upgrade \$http_upgrade/);
  assert.doesNotMatch(redisServer, /auth_request/);
});

test("legacy monitoring-owned service status routes are removed", () => {
  assert.doesNotMatch(config, /\/status\/api\/v1\/service-status/);
  assert.doesNotMatch(config, /\/status\/api\/v1\/admin/);
  assert.doesNotMatch(config, /service-status:8080/);
});
