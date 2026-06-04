from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .openai_models import DEFAULT_OPENAI_MODEL, normalize_openai_model


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class HealthResponse(CamelModel):
    ok: bool


class DeviceRegisterRequest(CamelModel):
    apns_token: str = Field(default="", alias="apnsToken", max_length=512)
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
    custom_prompt: str = Field(default="", alias="customPrompt", max_length=2000)
    app_language: str = Field(default="ko", alias="appLanguage")
    openai_model: str = Field(default=DEFAULT_OPENAI_MODEL, alias="openaiModel")
    max_history_count: int = Field(default=100, alias="maxHistoryCount", ge=10, le=10_000)
    is_question_public: bool = Field(default=True, alias="isQuestionPublic")

    @field_validator("openai_model")
    @classmethod
    def normalize_openai_model(cls, value: str) -> str:
        return normalize_openai_model(value)


class ScheduleResponse(CamelModel):
    device_id: str = Field(alias="deviceId")
    enabled: bool
    next_due_at: str | None = Field(alias="nextDueAt")


class PushTokenRequest(CamelModel):
    apns_token: str = Field(alias="apnsToken", min_length=32, max_length=512)
    apns_environment: str = Field(default="production", alias="apnsEnvironment")


class BackendSettingsResponse(CamelModel):
    topic: str
    difficulty_level: int = Field(alias="difficultyLevel")
    interval_minutes: int = Field(alias="intervalMinutes")
    enabled: bool
    notification_sound: str | None = Field(alias="notificationSound")
    custom_prompt: str = Field(alias="customPrompt")
    app_language: str = Field(alias="appLanguage")
    openai_model: str = Field(alias="openaiModel")
    max_history_count: int = Field(alias="maxHistoryCount")
    is_question_public: bool = Field(alias="isQuestionPublic")
    openai_key_configured: bool = Field(alias="openaiKeyConfigured")
    next_due_at: str | None = Field(alias="nextDueAt")
    last_error: str | None = Field(alias="lastError")


class CommunityQuestionResponse(CamelModel):
    id: str
    question: str
    topic: str
    difficulty_level: int = Field(alias="difficultyLevel")
    status: str
    source: str
    created_at: str = Field(alias="createdAt")


class CommunityQuestionsResponse(CamelModel):
    questions: list[CommunityQuestionResponse]
    total_count: int = Field(alias="totalCount")
    limit: int
    offset: int


class APIStatusResponse(CamelModel):
    openai_key_configured: bool = Field(alias="openaiKeyConfigured")
    openai_model: str = Field(alias="openaiModel")
    usage_url: str = Field(alias="usageUrl")
    billing_url: str = Field(alias="billingUrl")
    credits_url: str = Field(alias="creditsUrl")


class APIValidationResponse(CamelModel):
    openai_key_configured: bool = Field(alias="openaiKeyConfigured")
    is_valid: bool = Field(alias="isValid")
    openai_model: str = Field(alias="openaiModel")


class OpenAIModelOptionResponse(CamelModel):
    id: str
    display_name: str = Field(alias="displayName")
    supports_text_verbosity: bool = Field(alias="supportsTextVerbosity")


class QuestionPayload(CamelModel):
    question: str
    expected_answer_hint: str | None = Field(default=None, alias="expectedAnswerHint")


class GradingPayload(CamelModel):
    score: int = Field(ge=0, le=100)
    is_correct: bool = Field(alias="isCorrect")
    feedback: str
    explanation: str


class QuestionItemResponse(CamelModel):
    question: str
    expected_answer_hint: str | None = Field(alias="expectedAnswerHint")
    created_at: str = Field(alias="createdAt")


class StudyRecordResponse(CamelModel):
    id: str
    question: QuestionItemResponse
    answer: str | None
    grading_result: GradingPayload | None = Field(alias="gradingResult")
    topic: str
    difficulty: int
    answered_at: str | None = Field(alias="answeredAt")
    status: str


class RecordsPageResponse(CamelModel):
    records: list[StudyRecordResponse]
    total_count: int = Field(alias="totalCount")
    limit: int
    offset: int


class TopicLevelRangeResponse(CamelModel):
    level: int
    average: int
    sample_count: int = Field(alias="sampleCount")
    center_level: float = Field(alias="centerLevel")
    lower_bound: float = Field(alias="lowerBound")
    upper_bound: float = Field(alias="upperBound")


class TopicStatsResponse(CamelModel):
    topic_key: str = Field(alias="topicKey")
    topic: str
    topic_aliases: list[str] = Field(alias="topicAliases")
    count: int
    average: int
    best: int
    correct_rate: int = Field(alias="correctRate")
    level_range: TopicLevelRangeResponse = Field(alias="levelRange")
    latest_at: str = Field(alias="latestAt")
    records: list[StudyRecordResponse]


class StatsResponse(CamelModel):
    total_responses: int = Field(alias="totalResponses")
    total_topics: int = Field(alias="totalTopics")
    topics: list[TopicStatsResponse]
    limit: int
    offset: int
    generated_at: str = Field(alias="generatedAt")


class BackendSnapshotResponse(CamelModel):
    settings: BackendSettingsResponse
    api: APIStatusResponse
    records: list[StudyRecordResponse]
    stats: StatsResponse
    total_count: int = Field(alias="totalCount")
    server_time: str = Field(alias="serverTime")


class AnswerRequest(CamelModel):
    answer: str = Field(min_length=1, max_length=20_000)
