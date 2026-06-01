from __future__ import annotations

from contextlib import asynccontextmanager

import logging

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, status

from .config import Settings
from .crypto import KeyCipher
from .db import Database, utc_now
from .models import (
    AnswerRequest,
    BackendSnapshotResponse,
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    HealthResponse,
    RecordsPageResponse,
    ScheduleRequest,
    ScheduleResponse,
    StudyRecordResponse,
)
from .openai_client import OpenAIQuestionClient
from .scheduler import QuestionScheduler


settings = Settings.load()
database = Database(path=settings.database_path, url=settings.database_url)
scheduler: QuestionScheduler | None = None
logger = logging.getLogger(__name__)


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


def require_matching_device(device_id: str, authenticated_device_id: str) -> None:
    if device_id != authenticated_device_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Device mismatch.")


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
    model = schedule_row["openai_model"] if schedule_row is not None else settings.openai_model
    return OpenAIQuestionClient(model or settings.openai_model)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(ok=True)


@app.post(
    "/v1/devices/register",
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
    return DeviceRegisterResponse(deviceId=device_id, clientSecret=client_secret)


@app.put("/v1/devices/{device_id}/schedule", response_model=ScheduleResponse)
async def upsert_schedule(
    device_id: str,
    payload: ScheduleRequest,
    authenticated_device_id: str = Depends(verify_device),
) -> ScheduleResponse:
    require_matching_device(device_id, authenticated_device_id)

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
        custom_prompt=payload.custom_prompt,
        app_language=payload.app_language,
        openai_model=payload.openai_model,
        max_history_count=payload.max_history_count,
    )
    return ScheduleResponse(deviceId=device_id, enabled=payload.enabled, nextDueAt=next_due_at)


@app.get("/v1/devices/{device_id}/snapshot", response_model=BackendSnapshotResponse)
async def get_snapshot(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
    limit: int = Query(default=500, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> BackendSnapshotResponse:
    require_matching_device(device_id, authenticated_device_id)
    schedule = database.get_schedule(device_id)
    records, total_count = database.list_records(device_id, limit=limit, offset=offset)
    return BackendSnapshotResponse(
        settings=database.schedule_settings_response(schedule),
        records=records,
        totalCount=total_count,
        serverTime=database._response_timestamp(utc_now()),
    )


@app.get("/v1/devices/{device_id}/records", response_model=RecordsPageResponse)
async def list_records(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> RecordsPageResponse:
    require_matching_device(device_id, authenticated_device_id)
    records, total_count = database.list_records(device_id, limit=limit, offset=offset)
    return RecordsPageResponse(records=records, totalCount=total_count, limit=limit, offset=offset)


@app.get("/v1/devices/{device_id}/records/{record_id}", response_model=StudyRecordResponse)
async def get_record(
    device_id: str,
    record_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> StudyRecordResponse:
    require_matching_device(device_id, authenticated_device_id)
    record = database.get_record(device_id, record_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(record)


@app.post("/v1/devices/{device_id}/questions", response_model=StudyRecordResponse)
async def create_question(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> StudyRecordResponse:
    require_matching_device(device_id, authenticated_device_id)
    schedule = database.get_schedule(device_id)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Study settings are not configured.")

    pending_count = database.pending_record_count(device_id)
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
        recent_questions=database.recent_questions(device_id),
    )
    record = database.create_question(
        device_id=device_id,
        topic=schedule["topic"],
        difficulty_level=schedule["difficulty_level"],
        question=generated.question,
        expected_answer_hint=generated.expected_answer_hint,
        source="manual",
    )
    database.defer_schedule(device_id, minutes=schedule["interval_minutes"])
    return StudyRecordResponse.model_validate(record)


@app.post("/v1/devices/{device_id}/records/{record_id}/answer", response_model=StudyRecordResponse)
async def answer_record(
    device_id: str,
    record_id: str,
    payload: AnswerRequest,
    authenticated_device_id: str = Depends(verify_device),
) -> StudyRecordResponse:
    require_matching_device(device_id, authenticated_device_id)
    schedule = database.get_schedule(device_id)
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Study settings are not configured.")

    record = database.get_record(device_id, record_id)
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
    )
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.patch("/v1/devices/{device_id}/records/{record_id}/answer", response_model=StudyRecordResponse)
async def save_record_answer(
    device_id: str,
    record_id: str,
    payload: AnswerRequest,
    authenticated_device_id: str = Depends(verify_device),
) -> StudyRecordResponse:
    require_matching_device(device_id, authenticated_device_id)
    record = database.get_record(device_id, record_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    if record["status"] in {"deleted", "skipped"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"Record is {record['status']}.")
    if record["gradingResult"] is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Record is already graded.")

    updated = database.set_record_answer(device_id=device_id, record_id=record_id, answer=payload.answer)
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.post("/v1/devices/{device_id}/records/{record_id}/skip", response_model=StudyRecordResponse)
async def skip_record(
    device_id: str,
    record_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> StudyRecordResponse:
    require_matching_device(device_id, authenticated_device_id)
    updated = database.skip_record(device_id, record_id)
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found.")
    return StudyRecordResponse.model_validate(updated)


@app.delete("/v1/devices/{device_id}/records/{record_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_record(
    device_id: str,
    record_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> Response:
    require_matching_device(device_id, authenticated_device_id)
    database.delete_record(device_id, record_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete("/v1/devices/{device_id}/records", status_code=status.HTTP_204_NO_CONTENT)
async def clear_records(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> Response:
    require_matching_device(device_id, authenticated_device_id)
    database.clear_records(device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete(
    "/v1/devices/{device_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
)
async def delete_device(
    device_id: str,
    authenticated_device_id: str = Depends(verify_device),
) -> Response:
    require_matching_device(device_id, authenticated_device_id)
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
