from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class HealthResponse(CamelModel):
    ok: bool


class DeviceRegisterRequest(CamelModel):
    apns_token: str = Field(alias="apnsToken", min_length=32)
    platform: str = "ios"
    apns_environment: str = Field(default="production", alias="apnsEnvironment")
    language: str = "ko"
    timezone: str = "Asia/Seoul"


class DeviceRegisterResponse(CamelModel):
    device_id: str = Field(alias="deviceId")
    client_secret: str = Field(alias="clientSecret")


class ScheduleRequest(CamelModel):
    topic: str = Field(min_length=1, max_length=120)
    difficulty_level: int = Field(alias="difficultyLevel", ge=1, le=10)
    interval_minutes: int = Field(alias="intervalMinutes", ge=1, le=24 * 60)
    enabled: bool = True
    openai_api_key: str | None = Field(default=None, alias="openaiApiKey")
    notification_sound: str | None = Field(default=None, alias="notificationSound")


class ScheduleResponse(CamelModel):
    device_id: str = Field(alias="deviceId")
    enabled: bool
    next_due_at: str | None = Field(alias="nextDueAt")


class QuestionPayload(CamelModel):
    question: str
    hint: str | None = None

