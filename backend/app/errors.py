from __future__ import annotations

from enum import Enum
from typing import Any
from uuid import uuid4

from fastapi import Request, status
from fastapi.responses import JSONResponse


class APIErrorCode(str, Enum):
    AUTH_ACCESS_TOKEN_REQUIRED = "AUTH_ACCESS_TOKEN_REQUIRED"
    AUTH_DEVICE_MISMATCH = "AUTH_DEVICE_MISMATCH"
    AUTH_GOOGLE_REQUIRED = "AUTH_GOOGLE_REQUIRED"
    AUTH_INVALID_ACCESS_TOKEN = "AUTH_INVALID_ACCESS_TOKEN"
    AUTH_INVALID_BACKEND_TOKEN = "AUTH_INVALID_BACKEND_TOKEN"
    AUTH_INVALID_DEVICE_CREDENTIALS = "AUTH_INVALID_DEVICE_CREDENTIALS"
    AUTH_INVALID_EMAIL_CREDENTIALS = "AUTH_INVALID_EMAIL_CREDENTIALS"
    AUTH_EMAIL_VERIFICATION_REQUIRED = "AUTH_EMAIL_VERIFICATION_REQUIRED"
    AUTH_EMAIL_VERIFICATION_INVALID = "AUTH_EMAIL_VERIFICATION_INVALID"
    AUTH_EMAIL_VERIFICATION_UNAVAILABLE = "AUTH_EMAIL_VERIFICATION_UNAVAILABLE"
    AUTH_EMAIL_VERIFICATION_SEND_FAILED = "AUTH_EMAIL_VERIFICATION_SEND_FAILED"
    AUTH_PRINCIPAL_INVALID = "AUTH_PRINCIPAL_INVALID"
    DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    GOOGLE_LOGIN_NOT_CONFIGURED = "GOOGLE_LOGIN_NOT_CONFIGURED"
    GOOGLE_TOKEN_INVALID = "GOOGLE_TOKEN_INVALID"
    OPENAI_API_KEY_INVALID = "OPENAI_API_KEY_INVALID"
    OPENAI_API_KEY_MISSING = "OPENAI_API_KEY_MISSING"
    OPENAPI_TOKEN_REQUIRED = "OPENAPI_TOKEN_REQUIRED"
    PAGE_ACCESS_DENIED = "PAGE_ACCESS_DENIED"
    PROFILE_NOT_FOUND = "PROFILE_NOT_FOUND"
    QUESTION_NOT_FOUND = "QUESTION_NOT_FOUND"
    RECORD_ALREADY_GRADED = "RECORD_ALREADY_GRADED"
    RECORD_NOT_FOUND = "RECORD_NOT_FOUND"
    RECORD_STATUS_INVALID = "RECORD_STATUS_INVALID"
    STUDY_SETTINGS_MISSING = "STUDY_SETTINGS_MISSING"
    VALIDATION_ERROR = "VALIDATION_ERROR"
    INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"


DETAIL_CODE_MAP: dict[str, APIErrorCode] = {
    "Access token is required.": APIErrorCode.AUTH_ACCESS_TOKEN_REQUIRED,
    "Access token principal is no longer valid.": APIErrorCode.AUTH_PRINCIPAL_INVALID,
    "Device is not linked to this user.": APIErrorCode.AUTH_DEVICE_MISMATCH,
    "Device mismatch.": APIErrorCode.AUTH_DEVICE_MISMATCH,
    "Device not found.": APIErrorCode.DEVICE_NOT_FOUND,
    "Google Login is not configured on this backend.": APIErrorCode.GOOGLE_LOGIN_NOT_CONFIGURED,
    "Google Login is required.": APIErrorCode.AUTH_GOOGLE_REQUIRED,
    "Invalid access token.": APIErrorCode.AUTH_INVALID_ACCESS_TOKEN,
    "Invalid backend token.": APIErrorCode.AUTH_INVALID_BACKEND_TOKEN,
    "Invalid device credentials.": APIErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS,
    "Invalid email or password.": APIErrorCode.AUTH_INVALID_EMAIL_CREDENTIALS,
    "Email verification code is required.": APIErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED,
    "Invalid or expired email verification code.": APIErrorCode.AUTH_EMAIL_VERIFICATION_INVALID,
    "Email verification is not configured.": APIErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE,
    "Email verification email could not be sent.": APIErrorCode.AUTH_EMAIL_VERIFICATION_SEND_FAILED,
    "OpenAI API key is not configured for this device.": APIErrorCode.OPENAI_API_KEY_MISSING,
    "OpenAPI access token is not configured.": APIErrorCode.OPENAPI_TOKEN_REQUIRED,
    "OpenAPI access token is required.": APIErrorCode.OPENAPI_TOKEN_REQUIRED,
    "Page access is denied.": APIErrorCode.PAGE_ACCESS_DENIED,
    "Profile not found.": APIErrorCode.PROFILE_NOT_FOUND,
    "Question not found.": APIErrorCode.QUESTION_NOT_FOUND,
    "Record is already graded.": APIErrorCode.RECORD_ALREADY_GRADED,
    "Record not found.": APIErrorCode.RECORD_NOT_FOUND,
    "Study settings are not configured.": APIErrorCode.STUDY_SETTINGS_MISSING,
}


def request_id(request: Request) -> str:
    existing = getattr(request.state, "request_id", None)
    if existing:
        return str(existing)

    resolved = request.headers.get("x-request-id") or str(uuid4())
    request.state.request_id = resolved
    return resolved


def error_code_for(status_code: int, detail: Any) -> APIErrorCode:
    if isinstance(detail, str):
        if detail.startswith("OpenAI API key validation failed"):
            return APIErrorCode.OPENAI_API_KEY_INVALID
        if detail.startswith("Google token verification failed") or detail.startswith("Google token "):
            return APIErrorCode.GOOGLE_TOKEN_INVALID
        if detail.startswith("Record is "):
            return APIErrorCode.RECORD_STATUS_INVALID
        if detail.startswith("Page access denied:"):
            return APIErrorCode.PAGE_ACCESS_DENIED
        if detail in DETAIL_CODE_MAP:
            return DETAIL_CODE_MAP[detail]

    if status_code == status.HTTP_422_UNPROCESSABLE_ENTITY:
        return APIErrorCode.VALIDATION_ERROR
    if status_code >= 500:
        return APIErrorCode.INTERNAL_SERVER_ERROR
    return APIErrorCode.VALIDATION_ERROR


def message_for(status_code: int, detail: Any) -> str:
    if isinstance(detail, str) and detail.strip():
        return detail
    if status_code == status.HTTP_422_UNPROCESSABLE_ENTITY:
        return "Request validation failed."
    if status_code >= 500:
        return "The server could not complete the request. Please try again later."
    return "The request could not be completed."


def unified_error_response(
    request: Request,
    *,
    status_code: int,
    code: APIErrorCode,
    message: str,
) -> JSONResponse:
    rid = request_id(request)
    return JSONResponse(
        status_code=status_code,
        content={
            "error": {
                "code": code.value,
                "message": message,
                "requestId": rid,
                "status": status_code,
            }
        },
        headers={"X-Request-Id": rid},
    )
