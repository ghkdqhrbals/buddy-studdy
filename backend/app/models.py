from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .openai_models import DEFAULT_OPENAI_MODEL, normalize_openai_model


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class HealthResponse(CamelModel):
    ok: bool


class APIErrorPayload(CamelModel):
    code: str
    message: str
    request_id: str = Field(alias="requestId")
    status: int


class APIErrorResponse(CamelModel):
    error: APIErrorPayload


class DeviceRegisterRequest(CamelModel):
    apns_token: str = Field(default="", alias="apnsToken", max_length=512)
    platform: str = "ios"
    apns_environment: str = Field(default="production", alias="apnsEnvironment")
    language: str = "ko"
    timezone: str = "Asia/Seoul"


class DeviceRegisterResponse(CamelModel):
    device_id: str = Field(alias="deviceId")
    client_secret: str = Field(alias="clientSecret")
    access_token: str = Field(alias="accessToken")
    access_token_expires_at: str = Field(alias="accessTokenExpiresAt")


class PageAccessResponse(CamelModel):
    public_questions: bool = Field(alias="publicQuestions")
    statistics: bool = False
    study_detail: bool = Field(default=False, alias="studyDetail")
    records: bool = False


class PageAccessUpdateRequest(CamelModel):
    public_questions: bool | None = Field(default=None, alias="publicQuestions")


class UserProfileResponse(CamelModel):
    id: int
    display_name: str = Field(alias="displayName")
    bio: str = ""
    avatar_url: str | None = Field(default=None, alias="avatarUrl")
    avatar_symbol_name: str = Field(default="pixel-buddy", alias="avatarSymbolName")
    avatar_color_seed: str = Field(default="avatar-color-mint", alias="avatarColorSeed")
    page_access: PageAccessResponse = Field(alias="pageAccess")


class AccessTokenResponse(CamelModel):
    access_token: str = Field(alias="accessToken")
    access_token_expires_at: str = Field(alias="accessTokenExpiresAt")


class GoogleLoginResponse(CamelModel):
    profile: UserProfileResponse
    access_token: str = Field(alias="accessToken")
    access_token_expires_at: str = Field(alias="accessTokenExpiresAt")


class EmailLoginResponse(GoogleLoginResponse):
    pass


class GoogleLoginRequest(CamelModel):
    id_token: str = Field(alias="idToken", min_length=20, max_length=8192)


class EmailLoginRequest(CamelModel):
    email: str = Field(min_length=3, max_length=320)
    password: str = Field(min_length=6, max_length=256)
    verification_code: str | None = Field(default=None, alias="verificationCode", min_length=4, max_length=12)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        normalized = value.strip().lower()
        if "@" not in normalized or normalized.startswith("@") or normalized.endswith("@"):
            raise ValueError("Email address is invalid.")
        return normalized


class EmailVerificationCodeRequest(CamelModel):
    email: str = Field(min_length=3, max_length=320)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        return EmailLoginRequest.normalize_email(value)


class EmailVerificationCodeResponse(CamelModel):
    email: str
    expires_in_seconds: int = Field(alias="expiresInSeconds")


class ProfileUpdateRequest(CamelModel):
    display_name: str | None = Field(default=None, alias="displayName", max_length=120)
    bio: str | None = Field(default=None, max_length=500)
    avatar_symbol_name: str | None = Field(default=None, alias="avatarSymbolName", max_length=64)
    avatar_color_seed: str | None = Field(default=None, alias="avatarColorSeed", max_length=64)
    page_access: PageAccessUpdateRequest | None = Field(default=None, alias="pageAccess")


class ReportQuestionRequest(CamelModel):
    reason: str = Field(min_length=1, max_length=120)
    message: str = Field(default="", max_length=1000)


class ReportQuestionResponse(CamelModel):
    id: int
    email_sent: bool = Field(alias="emailSent")


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
    is_question_public: bool = Field(default=False, alias="isQuestionPublic")
    schedules: list["ScheduleItemRequest"] | None = None

    @field_validator("openai_model")
    @classmethod
    def normalize_openai_model(cls, value: str) -> str:
        return normalize_openai_model(value)


class ScheduleItemRequest(CamelModel):
    topic: str = Field(min_length=1, max_length=120)
    difficulty_level: int = Field(alias="difficultyLevel", ge=1, le=10)
    custom_prompt: str = Field(default="", alias="customPrompt", max_length=2000)
    openai_model: str = Field(default=DEFAULT_OPENAI_MODEL, alias="openaiModel")

    @field_validator("openai_model")
    @classmethod
    def normalize_openai_model(cls, value: str) -> str:
        return normalize_openai_model(value)


class CreateQuestionRequest(CamelModel):
    topic: str | None = Field(default=None, max_length=120)


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
    answer: str | None = None
    grading_result: GradingPayload | None = Field(default=None, alias="gradingResult")
    topic: str
    difficulty_level: int = Field(alias="difficultyLevel")
    status: str
    source: str
    created_at: str = Field(alias="createdAt")
    answered_at: str | None = Field(default=None, alias="answeredAt")
    author: UserProfileResponse | None = None
    like_count: int = Field(default=0, alias="likeCount")
    comment_count: int = Field(default=0, alias="commentCount")
    is_liked_by_me: bool = Field(default=False, alias="isLikedByMe")


class CommunityQuestionsResponse(CamelModel):
    questions: list[CommunityQuestionResponse]
    total_count: int = Field(alias="totalCount")
    limit: int
    offset: int


class CommunityLikeResponse(CamelModel):
    question_id: str = Field(alias="questionId")
    like_count: int = Field(alias="likeCount")
    is_liked_by_me: bool = Field(alias="isLikedByMe")


class CommunityCommentRequest(CamelModel):
    body: str = Field(min_length=1, max_length=1000)

    @field_validator("body")
    @classmethod
    def normalize_body(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Comment body is required.")
        return normalized


class CommunityCommentResponse(CamelModel):
    id: str
    question_id: str = Field(alias="questionId")
    body: str
    created_at: str = Field(alias="createdAt")
    author: UserProfileResponse


class CommunityCommentsResponse(CamelModel):
    comments: list[CommunityCommentResponse]
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
    supports_reasoning: bool = Field(alias="supportsReasoning")
    default_reasoning_effort: str | None = Field(alias="defaultReasoningEffort")


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
    is_public: bool = Field(alias="isPublic")


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
    api: APIStatusResponse | None
    records: list[StudyRecordResponse]
    stats: StatsResponse | None
    total_count: int = Field(alias="totalCount")
    server_time: str = Field(alias="serverTime")


class AnswerRequest(CamelModel):
    answer: str = Field(min_length=1, max_length=20_000)


class RecordPublicityRequest(CamelModel):
    is_public: bool = Field(alias="isPublic")
