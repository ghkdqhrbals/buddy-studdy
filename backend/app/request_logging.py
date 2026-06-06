from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

from starlette.requests import Request
from starlette.responses import Response


MAX_LOG_BODY_BYTES = 32 * 1024
REDACTED = "[REDACTED]"

SENSITIVE_HEADER_NAMES = {
    "authorization",
    "cookie",
    "set-cookie",
    "x-client-secret",
    "x-openapi-token",
}

SENSITIVE_FIELD_HINTS = (
    "authorization",
    "clientsecret",
    "client_secret",
    "cookie",
    "idtoken",
    "id_token",
    "openaiapikey",
    "openai_api_key",
    "password",
    "secret",
    "token",
    "verificationcode",
    "verification_code",
)


def redact_headers(headers: Mapping[str, str]) -> dict[str, str]:
    redacted: dict[str, str] = {}
    for name, value in headers.items():
        if _is_sensitive_header(name):
            redacted[name] = REDACTED
        else:
            redacted[name] = value
    return redacted


def redact_query_params(params: Mapping[str, str]) -> dict[str, str]:
    redacted: dict[str, str] = {}
    for name, value in params.items():
        redacted[name] = REDACTED if _is_sensitive_field(name) else value
    return redacted


def redact_data(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: REDACTED if _is_sensitive_field(str(key)) else redact_data(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_data(item) for item in value]
    return value


def body_for_log(body: bytes, content_type: str | None) -> Any:
    if not body:
        return None

    truncated = len(body) > MAX_LOG_BODY_BYTES
    preview = body[:MAX_LOG_BODY_BYTES]
    decoded = preview.decode("utf-8", errors="replace")
    normalized_content_type = (content_type or "").lower()

    if "application/json" in normalized_content_type:
        try:
            parsed = json.loads(decoded)
        except json.JSONDecodeError:
            parsed = decoded
        logged_body = redact_data(parsed)
    elif _is_text_content_type(normalized_content_type):
        logged_body = decoded
    else:
        logged_body = {"bytes": len(body), "contentType": content_type or "unknown"}

    if not truncated:
        return logged_body
    return {
        "truncated": True,
        "bytes": len(body),
        "preview": logged_body,
    }


def build_request_log(request: Request, body: bytes) -> dict[str, Any]:
    return {
        "method": request.method,
        "path": request.url.path,
        "query": redact_query_params(request.query_params),
        "client": request.client.host if request.client else None,
        "headers": redact_headers(request.headers),
        "body": body_for_log(body, request.headers.get("content-type")),
    }


def build_response_log(
    request: Request,
    response: Response,
    body: bytes,
    duration_ms: float,
) -> dict[str, Any]:
    return {
        "method": request.method,
        "path": request.url.path,
        "status": response.status_code,
        "durationMs": round(duration_ms, 2),
        "headers": redact_headers(response.headers),
        "body": body_for_log(body, response.headers.get("content-type")),
    }


def build_error_response_log(request: Request, error: BaseException, duration_ms: float) -> dict[str, Any]:
    return {
        "method": request.method,
        "path": request.url.path,
        "status": 500,
        "durationMs": round(duration_ms, 2),
        "errorType": type(error).__name__,
        "error": str(error),
    }


def _is_sensitive_field(name: str) -> bool:
    normalized = name.replace("-", "_").lower()
    compact = normalized.replace("_", "")
    return any(hint in normalized or hint in compact for hint in SENSITIVE_FIELD_HINTS)


def _is_sensitive_header(name: str) -> bool:
    return name.lower() in SENSITIVE_HEADER_NAMES


def _is_text_content_type(content_type: str) -> bool:
    return (
        content_type.startswith("text/")
        or "application/x-www-form-urlencoded" in content_type
        or "application/xml" in content_type
        or "application/problem+json" in content_type
    )
