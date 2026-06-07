import json

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from starlette.concurrency import iterate_in_threadpool

from app.request_logging import (
    REDACTED,
    body_for_log,
    build_request_log,
    build_response_log,
    redact_headers,
    redact_query_params,
)


def test_headers_are_redacted():
    headers = redact_headers(
        {
            "Authorization": "Bearer test-token",
            "X-Client-Secret": "client-secret",
            "content-type": "application/json",
        }
    )

    assert headers["Authorization"] == REDACTED
    assert headers["X-Client-Secret"] == REDACTED
    assert headers["content-type"] == "application/json"


def test_query_params_are_redacted():
    query = redact_query_params({"token": "docs-token", "period": "last30"})

    assert query["token"] == REDACTED
    assert query["period"] == "last30"


def test_json_body_redacts_nested_sensitive_fields():
    body = json.dumps(
        {
            "topic": "Swift",
            "openaiApiKey": "sk-test",
            "nested": {
                "idToken": "google-token",
                "client_secret": "secret",
                "safe": "visible",
            },
            "items": [{"apns_token": "push-token"}],
        }
    ).encode()

    logged = body_for_log(body, "application/json")

    assert logged["topic"] == "Swift"
    assert logged["openaiApiKey"] == REDACTED
    assert logged["nested"]["idToken"] == REDACTED
    assert logged["nested"]["client_secret"] == REDACTED
    assert logged["nested"]["safe"] == "visible"
    assert logged["items"][0]["apns_token"] == REDACTED


def test_large_body_is_truncated_before_logging():
    logged = body_for_log(b"a" * 40000, "text/plain")

    assert logged["truncated"] is True
    assert logged["bytes"] == 40000
    assert len(logged["preview"]) == 32768


def test_logging_middleware_can_replay_request_and_response_body():
    app = FastAPI()
    captured_logs = []

    @app.middleware("http")
    async def test_logging_middleware(request: Request, call_next):
        request_body = await request.body()

        async def receive():
            return {"type": "http.request", "body": request_body, "more_body": False}

        request._receive = receive
        request_log = build_request_log(request, request_body)
        response = await call_next(request)
        response_body = b""
        async for chunk in response.body_iterator:
            response_body += chunk
        response_log = build_response_log(request, response, response_body, 1.2)
        response.body_iterator = iterate_in_threadpool(iter([response_body]))
        captured_logs.append((request_log, response_log))
        return response

    @app.post("/echo")
    async def echo(request: Request):
        payload = await request.json()
        return {"received": payload["message"], "clientSecret": "server-secret"}

    client = TestClient(app)
    response = client.post("/echo", json={"message": "hello", "idToken": "secret-token"})

    assert response.status_code == 200
    assert response.json() == {"received": "hello", "clientSecret": "server-secret"}
    assert captured_logs[0][0]["body"]["idToken"] == REDACTED
    assert captured_logs[0][1]["body"]["clientSecret"] == REDACTED


def test_response_log_uses_route_template_for_variable_paths():
    app = FastAPI()
    captured_logs = []

    @app.middleware("http")
    async def test_logging_middleware(request: Request, call_next):
        response = await call_next(request)
        response_body = b""
        async for chunk in response.body_iterator:
            response_body += chunk
        response_log = build_response_log(request, response, response_body, 2.3)
        response.body_iterator = iterate_in_threadpool(iter([response_body]))
        captured_logs.append(response_log)
        return response

    @app.get("/api/v1/me/records/{record_id}")
    async def get_record(record_id: int):
        return {"id": record_id}

    client = TestClient(app)
    response = client.get("/api/v1/me/records/71")

    assert response.status_code == 200
    assert captured_logs[0]["path"] == "/api/v1/me/records/71"
    assert captured_logs[0]["route"] == "/api/v1/me/records/{record_id}"
