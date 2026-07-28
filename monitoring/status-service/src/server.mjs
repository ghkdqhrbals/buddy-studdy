import http from "node:http";
import { fileURLToPath } from "node:url";
import {
  localizedContent,
  ServiceStatusStore,
  StatusNotFoundError,
  StatusValidationError,
} from "./store.mjs";

const MAX_BODY_BYTES = 32_000;

function sendJson(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      throw new StatusValidationError("Request body exceeds 32 KB.");
    }
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new StatusValidationError("Request body must be valid JSON.");
  }
}

function retryAfterSeconds(window, now = Date.now()) {
  if (!window.endsAt) return 60;
  return Math.min(Math.max(Math.ceil((Date.parse(window.endsAt) - now) / 1_000), 15), 300);
}

export async function createServiceStatusServer(options = {}) {
  const store = options.store
    ?? await new ServiceStatusStore(options.dataDirectory ?? process.env.STATUS_DATA_DIR ?? "./data").init();

  return http.createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://status.internal");
    try {
      if (request.method === "GET" && url.pathname === "/api/v1/service-status") {
        const now = Date.now();
        const active = store.active(now);
        if (!active) {
          sendJson(response, 200, {
            status: "OPERATIONAL",
            checkedAt: new Date(now).toISOString(),
          });
          return;
        }
        const content = localizedContent(active, request.headers["accept-language"]);
        sendJson(response, 200, {
          status: "MAINTENANCE",
          maintenanceId: active.id,
          ...content,
          startsAt: active.startsAt,
          endsAt: active.endsAt,
          retryAfterSeconds: retryAfterSeconds(active, now),
          checkedAt: new Date(now).toISOString(),
        });
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/v1/admin/service-maintenance") {
        sendJson(response, 200, store.overview());
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/v1/admin/service-maintenance/history") {
        sendJson(
          response,
          200,
          store.history(url.searchParams.get("limit"), url.searchParams.get("offset")),
        );
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/v1/admin/service-maintenance") {
        const created = await store.create(
          await readJson(request),
          request.headers["x-monitoring-user"],
        );
        sendJson(response, 201, created);
        return;
      }

      const terminateMatch = url.pathname.match(
        /^\/api\/v1\/admin\/service-maintenance\/(\d+)\/terminate$/,
      );
      if (request.method === "POST" && terminateMatch) {
        const terminated = await store.terminate(
          terminateMatch[1],
          request.headers["x-monitoring-user"],
        );
        sendJson(response, 200, terminated);
        return;
      }

      sendJson(response, 404, { message: "Not found." });
    } catch (error) {
      if (error instanceof StatusValidationError) {
        sendJson(response, 422, { message: error.message });
        return;
      }
      if (error instanceof StatusNotFoundError) {
        sendJson(response, 404, { message: error.message });
        return;
      }
      console.error("service_status_request_failed", error);
      sendJson(response, 500, { message: "Internal service status error." });
    }
  });
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const port = Number(process.env.STATUS_PORT || 3030);
  const server = await createServiceStatusServer();
  server.listen(port, "0.0.0.0", () => {
    console.log(`BuddyStudy monitoring status service listening on ${port}`);
  });
}
