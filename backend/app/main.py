from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import timedelta
from enum import Enum
import json
import time

import httpx
import logging

from fastapi.exceptions import RequestValidationError
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, status
from starlette.concurrency import iterate_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings
from .crypto import KeyCipher
from .db import Database, as_utc_datetime, utc_now
from .errors import APIErrorCode, error_code_for, message_for, request_id, unified_error_response
from .auth_tokens import AccessTokenError, create_access_token, decode_access_token
from .request_logging import build_error_response_log, build_request_log, build_response_log
from .models import (
    APIStatusResponse,
    APIValidationResponse,
    AnswerRequest,
    CommunityCommentRequest,
    CommunityCommentResponse,
    CommunityCommentsResponse,
    CommunityLikeResponse,
    CommunityQuestionResponse,
    CommunityQuestionsResponse,
    AccessTokenResponse,
    BackendSnapshotResponse,
    BackendSettingsResponse,
    CreateQuestionRequest,
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    EmailVerificationCodeRequest,
    EmailVerificationCodeResponse,
    EmailLoginResponse,
    EmailLoginRequest,
    GoogleLoginResponse,
    GoogleLoginRequest,
    HealthResponse,
    PushTokenRequest,
    RecordPublicityRequest,
    RecordsPageResponse,
    ProfileUpdateRequest,
    ReportQuestionRequest,
    ReportQuestionResponse,
    ScheduleItemRequest,
    ScheduleRequest,
    OpenAIModelOptionResponse,
    ScheduleResponse,
    StatsResponse,
    StudyRecordResponse,
    UserProfileResponse,
)
from .email_verification import EmailVerificationStore, EmailVerificationUnavailable
from .openai_client import OpenAIQuestionClient
from .openai_models import DEFAULT_OPENAI_MODEL, OPENAI_MODEL_OPTIONS, normalize_openai_model
from .google_auth import GoogleAuthError, verify_google_id_token
from .reporting import EmailDeliveryError, EmailDeliveryFailureReason, send_email_verification_code, send_report_email
from .reaction_aggregator import QuestionReactionAggregator
from .scheduler import QuestionScheduler


settings = Settings.load()
database = Database(path=settings.database_path, url=settings.database_url)
email_verification_store = EmailVerificationStore(settings)
scheduler: QuestionScheduler | None = None
reaction_aggregator: QuestionReactionAggregator | None = None
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(levelname)s:%(name)s:%(message)s"))
    logger.addHandler(handler)
logger.propagate = False


@dataclass(frozen=True)
class AuthenticatedPrincipal:
    user_id: int
    device_id: str
    is_anonymous: bool

    @property
    def has_google_login(self) -> bool:
        return not self.is_anonymous


class ProtectedPage(str, Enum):
    RECORDS = "records"
    STATISTICS = "statistics"
    STUDY_DETAIL = "studyDetail"


def _docs_urls() -> tuple[str | None, str | None, str | None]:
    if not getattr(settings, "enable_openapi_docs", False):
        return None, None, None
    return "/docs", "/redoc", "/openapi.json"


def _uses_reaction_stream() -> bool:
    return bool(reaction_aggregator is not None and reaction_aggregator.uses_stream)


def _publish_reaction_changed(question_id: str | int, event_type: str, user_id: int | None) -> None:
    if reaction_aggregator is None or not reaction_aggregator.uses_stream:
        return
    try:
        reaction_aggregator.publish_question_changed(question_id, event_type, user_id=user_id)
    except Exception:
        logger.exception("failed to publish question reaction event question_id=%s", question_id)
        try:
            database.reconcile_question_stats(question_ids=[int(question_id)])
        except Exception:
            logger.exception("failed to reconcile question stats after stream publish failure question_id=%s", question_id)


def _publish_question_viewed(question_id: str | int, user_id: int | None) -> None:
    if reaction_aggregator is None:
        database.increment_question_view_count(question_id, 1)
        return
    try:
        reaction_aggregator.publish_question_viewed(question_id, user_id=user_id)
    except Exception:
        logger.exception("failed to publish question view event question_id=%s", question_id)
        database.increment_question_view_count(question_id, 1)


@asynccontextmanager
async def lifespan(_: FastAPI):
    global scheduler, reaction_aggregator
    database.init()
    if settings.reaction_aggregation_enabled:
        reaction_aggregator = QuestionReactionAggregator(settings=settings, database=database)
        reaction_aggregator.start()
    if settings.scheduler_enabled:
        scheduler = QuestionScheduler(settings=settings, database=database, event_streams=reaction_aggregator)
        scheduler.start()
    try:
        yield
    finally:
        if reaction_aggregator is not None:
            await reaction_aggregator.stop()
        if scheduler is not None:
            await scheduler.stop()


app = FastAPI(
    title="BuddyStuddy Push Backend",
    version="0.1.0",
    lifespan=lifespan,
    docs_url=_docs_urls()[0],
    redoc_url=_docs_urls()[1],
    openapi_url=_docs_urls()[2],
)


@app.exception_handler(StarletteHTTPException)
async def unified_http_exception_handler(request: Request, exc: StarletteHTTPException):
    return unified_error_response(
        request,
        status_code=exc.status_code,
        code=error_code_for(exc.status_code, exc.detail),
        message=message_for(exc.status_code, exc.detail),
    )


@app.exception_handler(RequestValidationError)
async def unified_validation_exception_handler(request: Request, exc: RequestValidationError):
    return unified_error_response(
        request,
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        code=APIErrorCode.VALIDATION_ERROR,
        message=message_for(status.HTTP_422_UNPROCESSABLE_ENTITY, exc.errors()),
    )


@app.exception_handler(Exception)
async def unified_exception_handler(request: Request, exc: Exception):
    logger.exception("unhandled backend error request_id=%s error=%s", request_id(request), exc)
    return unified_error_response(
        request,
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code=APIErrorCode.INTERNAL_SERVER_ERROR,
        message=message_for(status.HTTP_500_INTERNAL_SERVER_ERROR, None),
    )


@app.middleware("http")
async def log_api_request_response(request: Request, call_next):
    started = time.perf_counter()
    request_id(request)
    request_body = await request.body()

    async def receive():
        return {"type": "http.request", "body": request_body, "more_body": False}

    request._receive = receive  # noqa: SLF001 - Starlette middleware needs to replay the consumed body.
    logger.info(
        "api_request %s",
        json.dumps(build_request_log(request, request_body), ensure_ascii=False, separators=(",", ":")),
    )

    try:
        response = await call_next(request)
    except Exception as error:
        duration_ms = (time.perf_counter() - started) * 1000
        logger.exception(
            "api_response %s",
            json.dumps(
                build_error_response_log(request, error, duration_ms),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )
        raise

    response_body = b""
    async for chunk in response.body_iterator:
        response_body += chunk if isinstance(chunk, bytes) else str(chunk).encode("utf-8")

    duration_ms = (time.perf_counter() - started) * 1000
    logger.info(
        "api_response %s",
        json.dumps(
            build_response_log(request, response, response_body, duration_ms),
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )
    response.body_iterator = iterate_in_threadpool(iter([response_body]))
    return response


@app.middleware("http")
async def support_legacy_api_prefix(request: Request, call_next):
    path = request.url.path
    if path == "/v1":
        request.scope["path"] = "/api/v1"
    elif path.startswith("/v1/"):
        request.scope["path"] = "/api/v1" + path[3:]
    request.scope["raw_path"] = request.scope["path"].encode("utf-8")
    return await call_next(request)


@app.middleware("http")
async def protect_openapi_docs(request: Request, call_next):
    if request.url.path in {"/docs", "/redoc", "/openapi.json"} and getattr(
        settings, "enable_openapi_docs", False
    ):
        if not settings.openapi_access_token:
            return unified_error_response(
                request,
                status_code=status.HTTP_401_UNAUTHORIZED,
                code=APIErrorCode.OPENAPI_TOKEN_REQUIRED,
                message="OpenAPI access token is not configured.",
            )
        token = request.query_params.get("token") or request.headers.get("x-openapi-token")
        if token != settings.openapi_access_token:
            return unified_error_response(
                request,
                status_code=status.HTTP_401_UNAUTHORIZED,
                code=APIErrorCode.OPENAPI_TOKEN_REQUIRED,
                message="OpenAPI access token is required.",
            )
    return await call_next(request)


def verify_backend_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.backend_api_token:
        return
    expected = f"Bearer {settings.backend_api_token}"
    if authorization != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid backend token.")


def verify_device(
    x_device_id: str = Header(alias="X-Device-Id"),
    x_client_secret: str = Header(alias="X-Client-Secret"),
) -> str:
    if not database.authenticate_device(x_device_id, x_client_secret):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device credentials.")
    return x_device_id


def issue_access_token_for_device(device_id: str) -> AccessTokenResponse:
    principal = database.get_device_principal(device_id)
    if principal is None:
        profile = database.ensure_anonymous_user_for_device(device_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")
        principal = database.get_device_principal(device_id)
    if principal is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")

    token, expires_at = create_access_token(
        user_id=int(principal["user_id"]),
        device_id=str(principal["device_id"]),
        secret=settings.auth_jwt_secret,
        session_id=int(principal["userDeviceId"]),
        is_anonymous=bool(principal["isAnonymous"]),
    )
    return AccessTokenResponse(accessToken=token, accessTokenExpiresAt=database._response_timestamp(expires_at))


def authenticate_principal(authorization: str | None = Header(default=None)) -> AuthenticatedPrincipal:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Access token is required.")

    raw_token = authorization.removeprefix("Bearer ").strip()
    try:
        claims = decode_access_token(raw_token, settings.auth_jwt_secret)
    except AccessTokenError as error:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid access token.") from error

    principal = database.get_access_token_principal(claims.user_id, claims.session_id)
    if principal is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Access token principal is no longer valid.")
    if str(principal["device_id"]) != claims.device_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Access token device is no longer valid.")

    return AuthenticatedPrincipal(
        user_id=claims.user_id,
        device_id=claims.device_id,
        is_anonymous=bool(principal["isAnonymous"]),
    )


def authenticate_optional_principal(authorization: str | None = Header(default=None)) -> AuthenticatedPrincipal | None:
    if not authorization or not authorization.startswith("Bearer "):
        return None

    try:
        return authenticate_principal(authorization)
    except HTTPException:
        return None


def authenticate_login_principal(
    authorization: str | None = Header(default=None),
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_client_secret: str | None = Header(default=None, alias="X-Client-Secret"),
) -> AuthenticatedPrincipal:
    if authorization and authorization.startswith("Bearer "):
        return authenticate_principal(authorization)

    if x_device_id or x_client_secret:
        if not x_device_id or not x_client_secret:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device credentials.")
        if not database.authenticate_device(x_device_id, x_client_secret):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device credentials.")
        token_principal = database.get_device_principal(x_device_id)
        if token_principal is None:
            profile = database.ensure_anonymous_user_for_device(x_device_id)
            if profile is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")
            token_principal = database.get_device_principal(x_device_id)
        if token_principal is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")
        return AuthenticatedPrincipal(
            user_id=int(token_principal["user_id"]),
            device_id=x_device_id,
            is_anonymous=bool(token_principal["isAnonymous"]),
        )

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Access token is required.")


def require_google_principal(principal: AuthenticatedPrincipal) -> None:
    if principal.is_anonymous:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Google Login is required.")


def principal_can_access_page(principal: AuthenticatedPrincipal, page: ProtectedPage) -> bool:
    _ = page
    return principal.has_google_login


def require_page_access(principal: AuthenticatedPrincipal, page: ProtectedPage) -> None:
    if not principal_can_access_page(principal, page):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Page access denied: {page.value}.",
        )


def require_records_access(principal: AuthenticatedPrincipal = Depends(authenticate_principal)) -> AuthenticatedPrincipal:
    require_page_access(principal, ProtectedPage.RECORDS)
    return principal


def require_statistics_access(principal: AuthenticatedPrincipal = Depends(authenticate_principal)) -> AuthenticatedPrincipal:
    require_page_access(principal, ProtectedPage.STATISTICS)
    return principal


def require_study_detail_access(principal: AuthenticatedPrincipal = Depends(authenticate_principal)) -> AuthenticatedPrincipal:
    require_page_access(principal, ProtectedPage.STUDY_DETAIL)
    return principal


def device_api_key(schedule_row) -> str:
    encrypted_key = schedule_row["openai_api_key_cipher"] if schedule_row is not None else None
    if encrypted_key:
        return KeyCipher(settings.backend_master_key).decrypt(encrypted_key)
    if settings.openai_api_key:
        return settings.openai_api_key
    raise HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail="OpenAI API key is not configured for this device.",
    )


def openai_for_schedule(schedule_row) -> OpenAIQuestionClient:
    model = (schedule_row["openai_model"] if schedule_row is not None else settings.openai_model)
    return OpenAIQuestionClient(normalize_openai_model(model or DEFAULT_OPENAI_MODEL))


def stats_window(period: str, start_at: str | None, end_at: str | None) -> tuple[object | None, object | None]:
    if start_at or end_at:
        return (
            as_utc_datetime(start_at) if start_at else None,
            as_utc_datetime(end_at) if end_at else None,
        )

    now = utc_now()
    normalized = period.strip().lower()
    if normalized == "today":
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        return start, start + timedelta(days=1)
    if normalized == "last7":
        return now - timedelta(days=7), None
    if normalized == "last30":
        return now - timedelta(days=30), None
    if normalized == "last90":
        return now - timedelta(days=90), None
    return None, None


@app.get("/health", response_model=HealthResponse)
@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(ok=True)


@app.get("/api/v1/openai/models", response_model=list[OpenAIModelOptionResponse])
async def list_openai_models() -> list[OpenAIModelOptionResponse]:
    return [
        OpenAIModelOptionResponse(
            id=option.id,
            displayName=option.display_name,
            supportsTextVerbosity=option.supports_text_verbosity,
            supportsReasoning=option.supports_reasoning,
            defaultReasoningEffort=option.default_reasoning_effort,
        )
        for option in OPENAI_MODEL_OPTIONS
    ]


@app.post(
    "/api/v1/devices/register",
    response_model=DeviceRegisterResponse,
)
async def register_device(payload: DeviceRegisterRequest) -> DeviceRegisterResponse:
    device_id, client_secret = database.register_device(
        apns_token=payload.apns_token,
        platform=payload.platform,
        apns_environment=payload.apns_environment,
        language=payload.language,
        timezone=payload.timezone,
    )
    token_response = issue_access_token_for_device(device_id)
    return DeviceRegisterResponse(
        deviceId=device_id,
        clientSecret=client_secret,
        accessToken=token_response.access_token,
        accessTokenExpiresAt=token_response.access_token_expires_at,
    )


@app.post("/api/v1/auth/token", response_model=AccessTokenResponse)
async def bootstrap_access_token(
    authenticated_device_id: str = Depends(verify_device),
) -> AccessTokenResponse:
    return issue_access_token_for_device(authenticated_device_id)


@app.put("/api/v1/me/push-token", status_code=status.HTTP_204_NO_CONTENT)
async def update_push_token(
    payload: PushTokenRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> Response:
    device_id = principal.device_id
    database.update_device_push_token(
        device_id=device_id,
        apns_token=payload.apns_token,
        apns_environment=payload.apns_environment,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.post("/api/v1/auth/google", response_model=GoogleLoginResponse)
async def google_login(
    payload: GoogleLoginRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_login_principal),
) -> GoogleLoginResponse:
    device_id = principal.device_id
    if not settings.google_ios_client_id:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Google Login is not configured on this backend.",
        )

    try:
        identity = await verify_google_id_token(payload.id_token, settings.google_ios_client_id)
    except GoogleAuthError as error:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(error)) from error

    profile = database.link_google_user_to_device(
        device_id=device_id,
        provider_id=str(identity["sub"] or ""),
        email=str(identity["email"] or ""),
        display_name=str(identity["name"] or ""),
        avatar_url=None,
    )
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")
    token_response = issue_access_token_for_device(device_id)
    return GoogleLoginResponse(
        profile=UserProfileResponse.model_validate(profile),
        accessToken=token_response.access_token,
        accessTokenExpiresAt=token_response.access_token_expires_at,
    )


@app.post("/api/v1/auth/email/code", response_model=EmailVerificationCodeResponse)
async def request_email_verification_code(
    payload: EmailVerificationCodeRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_login_principal),
) -> EmailVerificationCodeResponse:
    _ = principal
    try:
        issued = email_verification_store.issue_code(payload.email)
    except EmailVerificationUnavailable as error:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Email verification is not configured.") from error

    try:
        sent = send_email_verification_code(settings, issued.email, issued.code, issued.expires_in_seconds)
    except EmailDeliveryError as error:
        logger.warning("email verification send failed email=%s reason=%s error=%s", issued.email, error.reason, error)
        if error.reason == EmailDeliveryFailureReason.QUOTA_EXCEEDED:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Email verification email quota exceeded.",
            ) from error
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Email verification email could not be sent.",
        ) from error
    except Exception as error:
        logger.warning("email verification send failed email=%s error=%s", issued.email, error)
        sent = False

    if not sent:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Email verification email could not be sent.",
        )

    return EmailVerificationCodeResponse(email=issued.email, expiresInSeconds=issued.expires_in_seconds)


@app.post("/api/v1/auth/email", response_model=EmailLoginResponse)
async def email_login(
    payload: EmailLoginRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_login_principal),
) -> EmailLoginResponse:
    is_existing_email_user = database.email_user_exists(payload.email)
    if not is_existing_email_user:
        if not payload.verification_code:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Email verification code is required.")
        try:
            verified = email_verification_store.verify_and_consume(payload.email, payload.verification_code)
        except EmailVerificationUnavailable as error:
            raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Email verification is not configured.") from error
        if not verified:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or expired email verification code.",
            )

    profile, password_mismatch = database.link_email_user_to_device(
        device_id=principal.device_id,
        email=payload.email,
        password=payload.password,
    )
    if password_mismatch:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password.")
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found.")

    token_response = issue_access_token_for_device(principal.device_id)
    return EmailLoginResponse(
        profile=UserProfileResponse.model_validate(profile),
        accessToken=token_response.access_token,
        accessTokenExpiresAt=token_response.access_token_expires_at,
    )


@app.get("/api/v1/me/profile", response_model=UserProfileResponse)
async def get_my_profile(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> UserProfileResponse:
    device_id = principal.device_id
    require_google_principal(principal)
    profile = database.get_device_profile(device_id)
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")
    return UserProfileResponse.model_validate(profile)


@app.patch("/api/v1/me/profile", response_model=UserProfileResponse)
async def update_my_profile(
    payload: ProfileUpdateRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> UserProfileResponse:
    device_id = principal.device_id
    require_google_principal(principal)
    profile = database.update_device_profile(
        device_id=device_id,
        display_name=payload.display_name,
        bio=payload.bio,
        allow_public_questions=(
            payload.page_access.public_questions
            if payload.page_access is not None
            else None
        ),
        avatar_symbol_name=payload.avatar_symbol_name,
        avatar_color_seed=payload.avatar_color_seed,
    )
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")
    return UserProfileResponse.model_validate(profile)


@app.delete("/api/v1/me/profile", response_model=AccessTokenResponse)
async def withdraw_my_profile(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> AccessTokenResponse:
    device_id = principal.device_id
    require_google_principal(principal)
    profile = database.withdraw_device_user(device_id)
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")
    return issue_access_token_for_device(device_id)


@app.put("/api/v1/me/schedule", response_model=ScheduleResponse)
async def upsert_schedule(
    payload: ScheduleRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> ScheduleResponse:
    device_id = principal.device_id

    encrypted_key = None
    if payload.openai_api_key:
        encrypted_key = KeyCipher(settings.backend_master_key).encrypt(payload.openai_api_key)

    is_question_public = bool(payload.is_question_public and principal.has_google_login)
    schedule_items = payload.schedules or [
        ScheduleItemRequest(
            topic=payload.topic,
            difficultyLevel=payload.difficulty_level,
            customPrompt=payload.custom_prompt,
            openaiModel=payload.openai_model,
        )
    ]
    next_due_at = None
    for item in schedule_items:
        next_due_at = database.upsert_schedule(
            device_id=device_id,
            user_id=principal.user_id,
            topic=item.topic,
            difficulty_level=item.difficulty_level,
            interval_minutes=payload.interval_minutes,
            enabled=payload.enabled,
            openai_api_key_cipher=encrypted_key,
            notification_sound=payload.notification_sound,
            custom_prompt=item.custom_prompt,
            app_language=payload.app_language,
            openai_model=item.openai_model,
            max_history_count=payload.max_history_count,
            is_question_public=is_question_public,
        )
    return ScheduleResponse(deviceId=device_id, enabled=payload.enabled, nextDueAt=next_due_at)


@app.get("/api/v1/me/settings", response_model=BackendSettingsResponse)
async def get_settings(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> BackendSettingsResponse:
    device_id = principal.device_id
    return BackendSettingsResponse.model_validate(
        database.schedule_settings_response(database.get_schedule(device_id, user_id=principal.user_id))
    )


@app.put("/api/v1/me/settings", response_model=ScheduleResponse)
async def put_settings(
    payload: ScheduleRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> ScheduleResponse:
    return await upsert_schedule(payload=payload, principal=principal)


@app.get("/api/v1/me/api", response_model=APIStatusResponse)
async def get_api_status(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> APIStatusResponse:
    device_id = principal.device_id
    return APIStatusResponse.model_validate(
        database.api_status_response(database.get_schedule(device_id, user_id=principal.user_id))
    )


@app.post("/api/v1/me/api/validate", response_model=APIValidationResponse)
async def validate_api_key(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> APIValidationResponse:
    device_id = principal.device_id
    schedule = database.get_schedule(device_id, user_id=principal.user_id)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Study settings are not configured.")

    try:
        api_key = device_api_key(schedule)
    except RuntimeError as error:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(error)) from error

    try:
        await openai_for_schedule(schedule).validate_api_key(api_key)
    except httpx.HTTPStatusError as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"OpenAI API key validation failed: HTTP {error.response.status_code}.",
        ) from error

    return APIValidationResponse(
        openaiKeyConfigured=True,
        isValid=True,
        openaiModel=(schedule["openai_model"] or settings.openai_model),
    )


@app.get("/api/v1/me/snapshot", response_model=BackendSnapshotResponse)
async def get_snapshot(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
    limit: int = Query(default=500, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> BackendSnapshotResponse:
    device_id = principal.device_id
    schedule = database.get_schedule(device_id, user_id=principal.user_id)
    if principal_can_access_page(principal, ProtectedPage.RECORDS):
        records, total_count = database.list_records(device_id, limit=limit, offset=offset, user_id=principal.user_id)
    else:
        records, total_count = [], 0

    stats = (
        database.stats_response(device_id=device_id, user_id=principal.user_id, limit=8, offset=0)
        if principal_can_access_page(principal, ProtectedPage.STATISTICS)
        else None
    )
    return BackendSnapshotResponse(
        settings=database.schedule_settings_response(schedule),
        api=database.api_status_response(schedule),
        records=records,
        stats=stats,
        totalCount=total_count,
        serverTime=database._response_timestamp(utc_now()),
    )


@app.get("/api/v1/me/records", response_model=RecordsPageResponse)
async def list_records(
    principal: AuthenticatedPrincipal = Depends(require_records_access),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> RecordsPageResponse:
    device_id = principal.device_id
    records, total_count = database.list_records(
        device_id,
        limit=limit,
        offset=offset,
        user_id=principal.user_id,
        include_ungraded=False,
    )
    return RecordsPageResponse(records=records, totalCount=total_count, limit=limit, offset=offset)


@app.get("/api/v1/me/stats", response_model=StatsResponse)
async def get_stats(
    principal: AuthenticatedPrincipal = Depends(require_statistics_access),
    period: str = Query(default="all", pattern="^(all|today|last7|last30|last90)$"),
    start_at: str | None = Query(default=None, alias="startAt"),
    end_at: str | None = Query(default=None, alias="endAt"),
    search: str = Query(default="", max_length=120),
    sort: str = Query(default="level", pattern="^(level|recent|name|count)$"),
    limit: int = Query(default=8, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> StatsResponse:
    device_id = principal.device_id
    start, end = stats_window(period, start_at, end_at)
    return StatsResponse.model_validate(
        database.stats_response(
            device_id=device_id,
            user_id=principal.user_id,
            start_at=start,
            end_at=end,
            search=search,
            sort=sort,
            limit=limit,
            offset=offset,
        )
    )


@app.get("/api/v1/me/records/{record_id}", response_model=StudyRecordResponse)
async def get_record(
    record_id: str,
    principal: AuthenticatedPrincipal = Depends(require_study_detail_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    record = database.get_record(device_id, record_id, user_id=principal.user_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(record)


@app.post("/api/v1/me/questions", response_model=StudyRecordResponse)
async def create_question(
    payload: CreateQuestionRequest | None = None,
    principal: AuthenticatedPrincipal = Depends(require_study_detail_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    requested_topic = payload.topic if payload is not None else None
    schedule = database.get_schedule(device_id, user_id=principal.user_id, topic=requested_topic)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Study settings are not configured.")

    pending_count = database.pending_record_count(device_id, user_id=principal.user_id, topic=schedule["topic"])
    if pending_count >= QuestionScheduler.max_pending_questions:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Pending question limit reached ({pending_count}).",
        )

    api_key = device_api_key(schedule)
    generated = await openai_for_schedule(schedule).generate_question(
        api_key=api_key,
        topic=schedule["topic"],
        difficulty_level=schedule["difficulty_level"],
        language=schedule["app_language"] or schedule["device_language"],
        custom_prompt=schedule["custom_prompt"] or "",
        recent_questions=database.recent_questions(device_id, user_id=principal.user_id),
    )
    record = database.create_question(
        device_id=device_id,
        topic=schedule["topic"],
        difficulty_level=schedule["difficulty_level"],
        question=generated.question,
        expected_answer_hint=generated.expected_answer_hint,
        is_public=bool(schedule.get("is_question_public", False)),
        user_id=principal.user_id,
        source="manual",
    )
    database.defer_schedule(
        device_id,
        minutes=schedule["interval_minutes"],
        user_id=principal.user_id,
        topic=schedule["topic"],
    )
    return StudyRecordResponse.model_validate(record)


@app.post("/api/v1/me/records/{record_id}/answer", response_model=StudyRecordResponse)
async def answer_record(
    record_id: str,
    payload: AnswerRequest,
    principal: AuthenticatedPrincipal = Depends(require_study_detail_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    schedule = database.get_schedule(device_id, user_id=principal.user_id)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Study settings are not configured.")

    record = database.get_record(device_id, record_id, user_id=principal.user_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    if record["status"] in {"deleted", "skipped"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"Record is {record['status']}.")

    api_key = device_api_key(schedule)
    grading = await openai_for_schedule(schedule).grade_answer(
        api_key=api_key,
        topic=record["topic"] or schedule["topic"],
        difficulty_level=record["difficulty"],
        language=schedule["app_language"] or schedule["device_language"],
        question=record["question"]["question"],
        expected_answer_hint=record["question"]["expectedAnswerHint"],
        answer=payload.answer,
    )
    updated = database.grade_record(
        device_id=device_id,
        record_id=record_id,
        answer=payload.answer,
        score=grading.score,
        is_correct=grading.is_correct,
        feedback=grading.feedback,
        explanation=grading.explanation,
        user_id=principal.user_id,
    )
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.patch("/api/v1/me/records/{record_id}/answer", response_model=StudyRecordResponse)
async def save_record_answer(
    record_id: str,
    payload: AnswerRequest,
    principal: AuthenticatedPrincipal = Depends(require_study_detail_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    record = database.get_record(device_id, record_id, user_id=principal.user_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    if record["status"] in {"deleted", "skipped"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"Record is {record['status']}.")
    if record["gradingResult"] is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Record is already graded.")

    updated = database.set_record_answer(
        device_id=device_id,
        record_id=record_id,
        answer=payload.answer,
        user_id=principal.user_id,
    )
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.post("/api/v1/me/records/{record_id}/skip", response_model=StudyRecordResponse)
async def skip_record(
    record_id: str,
    principal: AuthenticatedPrincipal = Depends(require_study_detail_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    updated = database.skip_record(device_id, record_id, user_id=principal.user_id)
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.patch("/api/v1/me/records/{record_id}/publicity", response_model=StudyRecordResponse)
async def update_record_publicity(
    record_id: str,
    payload: RecordPublicityRequest,
    principal: AuthenticatedPrincipal = Depends(require_records_access),
) -> StudyRecordResponse:
    device_id = principal.device_id
    require_google_principal(principal)
    updated = database.set_record_publicity(
        device_id=device_id,
        record_id=record_id,
        is_public=payload.is_public,
        user_id=principal.user_id,
    )
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.get("/api/v1/public/questions", response_model=CommunityQuestionsResponse)
async def list_public_questions(
    topic: str | None = Query(default=None, max_length=120),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    exclude_device_id: str | None = Query(default=None, alias="excludeDeviceId"),
    principal: AuthenticatedPrincipal | None = Depends(authenticate_optional_principal),
) -> CommunityQuestionsResponse:
    questions, total = database.list_public_questions(
        exclude_device_id=exclude_device_id,
        limit=limit,
        offset=offset,
        topic=topic,
        viewer_user_id=principal.user_id if principal is not None else None,
    )
    return CommunityQuestionsResponse(questions=questions, totalCount=total, limit=limit, offset=offset)


@app.get("/api/v1/public/questions/{question_id}", response_model=CommunityQuestionResponse)
async def get_public_question(
    question_id: str,
    principal: AuthenticatedPrincipal | None = Depends(authenticate_optional_principal),
) -> CommunityQuestionResponse:
    question = database.get_public_question(
        question_id,
        viewer_user_id=principal.user_id if principal is not None else None,
    )
    if question is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")
    _publish_question_viewed(question_id, principal.user_id if principal is not None else None)
    return CommunityQuestionResponse.model_validate(question)


@app.put("/api/v1/public/questions/{question_id}/like", response_model=CommunityLikeResponse)
async def like_public_question(
    question_id: str,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> CommunityLikeResponse:
    like = database.set_public_question_like(
        question_id,
        user_id=principal.user_id,
        is_liked=True,
        emit_reaction_event=not _uses_reaction_stream(),
    )
    if like is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")
    _publish_reaction_changed(question_id, "LIKE_CREATED", principal.user_id)
    return CommunityLikeResponse.model_validate(like)


@app.delete("/api/v1/public/questions/{question_id}/like", response_model=CommunityLikeResponse)
async def unlike_public_question(
    question_id: str,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> CommunityLikeResponse:
    like = database.set_public_question_like(
        question_id,
        user_id=principal.user_id,
        is_liked=False,
        emit_reaction_event=not _uses_reaction_stream(),
    )
    if like is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")
    _publish_reaction_changed(question_id, "LIKE_REMOVED", principal.user_id)
    return CommunityLikeResponse.model_validate(like)


@app.get("/api/v1/public/questions/{question_id}/comments", response_model=CommunityCommentsResponse)
async def list_public_question_comments(
    question_id: str,
    limit: int = Query(default=30, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> CommunityCommentsResponse:
    result = database.list_public_question_comments(question_id, limit=limit, offset=offset)
    if result is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")
    comments, total = result
    return CommunityCommentsResponse(comments=comments, totalCount=total, limit=limit, offset=offset)


@app.post("/api/v1/public/questions/{question_id}/comments", response_model=CommunityCommentResponse)
async def create_public_question_comment(
    question_id: str,
    payload: CommunityCommentRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> CommunityCommentResponse:
    comment = database.create_public_question_comment(
        question_id,
        user_id=principal.user_id,
        body=payload.body,
        emit_reaction_event=not _uses_reaction_stream(),
    )
    if comment is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")
    _publish_reaction_changed(question_id, "COMMENT_CREATED", principal.user_id)
    return CommunityCommentResponse.model_validate(comment)


@app.get("/api/v1/public/users/{user_id}/profile", response_model=UserProfileResponse)
async def get_public_profile(user_id: int) -> UserProfileResponse:
    profile = database.get_public_profile(user_id)
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")
    return UserProfileResponse.model_validate(profile)


@app.post("/api/v1/public/questions/{question_id}/report", response_model=ReportQuestionResponse)
async def report_public_question(
    question_id: str,
    payload: ReportQuestionRequest,
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> ReportQuestionResponse:
    device_id = principal.device_id
    require_google_principal(principal)
    report = database.create_report(
        reporter_device_id=device_id,
        question_id=question_id,
        reason=payload.reason,
        message=payload.message,
    )
    if report is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Question not found.")

    email_sent = False
    try:
        email_sent = send_report_email(settings, report)
    except Exception as error:
        logger.warning("report email failed report_id=%s error=%s", report.get("id"), error)
    return ReportQuestionResponse(id=int(report["id"]), emailSent=email_sent)


@app.delete("/api/v1/me/records/{record_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_record(
    record_id: str,
    principal: AuthenticatedPrincipal = Depends(require_records_access),
) -> Response:
    device_id = principal.device_id
    database.delete_record(device_id, record_id, user_id=principal.user_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete("/api/v1/me/records", status_code=status.HTTP_204_NO_CONTENT)
async def clear_records(
    principal: AuthenticatedPrincipal = Depends(require_records_access),
) -> Response:
    device_id = principal.device_id
    database.clear_records(device_id, user_id=principal.user_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete(
    "/api/v1/me/device",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
)
async def delete_device(
    principal: AuthenticatedPrincipal = Depends(authenticate_principal),
) -> Response:
    device_id = principal.device_id
    database.delete_device(device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.post("/api/v1/admin/scheduler/run-once", dependencies=[Depends(verify_backend_token)])
async def run_scheduler_once(request: Request) -> dict:
    if scheduler is not None:
        count = await scheduler.run_once()
    else:
        local_scheduler = QuestionScheduler(settings=settings, database=database)
        count = await local_scheduler.run_once()
    return {"sent": count, "client": request.client.host if request.client else None}


@app.post("/api/v1/admin/reactions/aggregate/run-once", dependencies=[Depends(verify_backend_token)])
async def run_reaction_aggregation_once(request: Request) -> dict:
    if reaction_aggregator is not None:
        processed = await reaction_aggregator.run_once()
    else:
        local_aggregator = QuestionReactionAggregator(settings=settings, database=database)
        processed = await local_aggregator.run_once()
    return {"processed": processed, "client": request.client.host if request.client else None}


@app.post("/api/v1/admin/reactions/reconcile/run-once", dependencies=[Depends(verify_backend_token)])
async def run_reaction_reconcile_once(request: Request) -> dict:
    if reaction_aggregator is not None:
        reconciled = await reaction_aggregator.reconcile_once()
    else:
        local_aggregator = QuestionReactionAggregator(settings=settings, database=database)
        reconciled = await local_aggregator.reconcile_once()
    return {"reconciled": reconciled, "client": request.client.host if request.client else None}
