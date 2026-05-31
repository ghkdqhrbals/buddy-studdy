from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, status

from .config import Settings
from .crypto import KeyCipher
from .db import Database
from .models import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    HealthResponse,
    ScheduleRequest,
    ScheduleResponse,
)
from .scheduler import QuestionScheduler


settings = Settings.load()
database = Database(settings.database_path)
scheduler: QuestionScheduler | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global scheduler
    database.init()
    if settings.scheduler_enabled:
        scheduler = QuestionScheduler(settings=settings, database=database)
        scheduler.start()
    try:
        yield
    finally:
        if scheduler is not None:
            await scheduler.stop()


app = FastAPI(title="BuddyStuddy Push Backend", version="0.1.0", lifespan=lifespan)


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


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(ok=True)


@app.post(
    "/v1/devices/register",
    response_model=DeviceRegisterResponse,
    dependencies=[Depends(verify_backend_token)],
)
async def register_device(payload: DeviceRegisterRequest) -> DeviceRegisterResponse:
    device_id, client_secret = database.register_device(
        apns_token=payload.apns_token,
        platform=payload.platform,
        apns_environment=payload.apns_environment,
        language=payload.language,
        timezone=payload.timezone,
    )
    return DeviceRegisterResponse(deviceId=device_id, clientSecret=client_secret)


@app.put("/v1/devices/{device_id}/schedule", response_model=ScheduleResponse)
async def upsert_schedule(
    device_id: str,
    payload: ScheduleRequest,
    authenticated_device_id: str = Depends(verify_device),
) -> ScheduleResponse:
    if device_id != authenticated_device_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Device mismatch.")

    encrypted_key = None
    if payload.openai_api_key:
        encrypted_key = KeyCipher(settings.backend_master_key).encrypt(payload.openai_api_key)

    next_due_at = database.upsert_schedule(
        device_id=device_id,
        topic=payload.topic,
        difficulty_level=payload.difficulty_level,
        interval_minutes=payload.interval_minutes,
        enabled=payload.enabled,
        openai_api_key_cipher=encrypted_key,
        notification_sound=payload.notification_sound,
    )
    return ScheduleResponse(deviceId=device_id, enabled=payload.enabled, nextDueAt=next_due_at)


@app.delete(
    "/v1/devices/{device_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
)
async def delete_device(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> Response:
    if device_id != authenticated_device_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Device mismatch.")
    database.delete_device(device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.post("/v1/admin/scheduler/run-once", dependencies=[Depends(verify_backend_token)])
async def run_scheduler_once(request: Request) -> dict:
    if scheduler is not None:
        count = await scheduler.run_once()
    else:
        local_scheduler = QuestionScheduler(settings=settings, database=database)
        count = await local_scheduler.run_once()
    return {"sent": count, "client": request.client.host if request.client else None}
