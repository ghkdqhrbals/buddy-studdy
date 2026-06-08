package com.buddystuddy.backend.dto

import com.buddystuddy.backend.domain.QuestionCommentEntity
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.domain.ScheduleEntity
import com.buddystuddy.backend.domain.UserEntity
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class HealthResponse(val ok: Boolean = true)
data class DeviceRegisterRequest(
    val apnsToken: String = "",
    val platform: String = "ios",
    val apnsEnvironment: String = "production",
    val language: String = "ko",
    val timezone: String = "Asia/Seoul",
)
data class DeviceRegisterResponse(val deviceId: String, val clientSecret: String, val accessToken: String, val accessTokenExpiresAt: Instant)
data class AccessTokenResponse(val accessToken: String, val accessTokenExpiresAt: Instant)
data class PushTokenRequest(val apnsToken: String, val apnsEnvironment: String = "production")
data class GoogleLoginRequest(val idToken: String)
data class EmailVerificationCodeRequest(@field:Email val email: String)
data class EmailVerificationCodeResponse(val email: String, val expiresInSeconds: Long)
data class EmailLoginRequest(@field:Email val email: String, val password: String, val verificationCode: String? = null)
data class ProfileUpdateRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val pageAccess: CommunityPageAccess? = null,
)
data class CommunityPageAccess(
    val publicQuestions: Boolean = true,
    val statistics: Boolean = false,
    val studyDetail: Boolean = false,
    val records: Boolean = false,
)
data class UserProfileResponse(
    val id: Long,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val avatarSymbolName: String = "pixel-buddy",
    val avatarColorSeed: String = "avatar-color-mint",
    val pageAccess: CommunityPageAccess = CommunityPageAccess(),
)
data class GoogleLoginResponse(val profile: UserProfileResponse, val accessToken: String, val accessTokenExpiresAt: Instant)
typealias EmailLoginResponse = GoogleLoginResponse

data class ScheduleItemRequest(
    @field:NotBlank val topic: String,
    @field:Min(1) @field:Max(10) val difficultyLevel: Int = 5,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
)
data class ScheduleRequest(
    val topic: String = "",
    @field:Min(1) @field:Max(10) val difficultyLevel: Int = 5,
    @field:Min(1) @field:Max(1440) val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val openaiApiKey: String? = null,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val appLanguage: String = "ko",
    val openaiModel: String = "gpt-5.4",
    @field:Min(10) @field:Max(10_000) val maxHistoryCount: Int = 100,
    val isQuestionPublic: Boolean = false,
    @field:Valid val schedules: List<ScheduleItemRequest>? = null,
)
data class ScheduleResponse(val deviceId: String, val enabled: Boolean, val nextDueAt: Instant?)
data class BackendSettingsResponse(
    val topic: String = "",
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = false,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val appLanguage: String = "ko",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
    val isQuestionPublic: Boolean = false,
    val openaiKeyConfigured: Boolean = false,
    val nextDueAt: Instant? = null,
    val lastError: String? = null,
)
data class APIStatusResponse(
    val openaiKeyConfigured: Boolean,
    val openaiModel: String,
    val usageUrl: String = "https://platform.openai.com/usage",
    val billingUrl: String = "https://platform.openai.com/settings/organization/billing/overview",
    val creditsUrl: String = "https://platform.openai.com/settings/organization/billing/overview",
)
data class APIValidationResponse(val openaiKeyConfigured: Boolean, val isValid: Boolean, val openaiModel: String)
data class OpenAIModelOptionResponse(
    val id: String,
    val displayName: String,
    val supportsTextVerbosity: Boolean = true,
    val supportsReasoning: Boolean = true,
    val defaultReasoningEffort: String? = "none",
)
data class CreateQuestionRequest(val topic: String? = null)
data class AnswerRequest(val answer: String)
data class RecordPublicityRequest(val isPublic: Boolean)
data class ReportQuestionRequest(val reason: String, val message: String = "")
data class ReportQuestionResponse(val ok: Boolean = true)
data class CommunityCommentRequest(val body: String)

data class QuestionItemResponse(val question: String, val expectedAnswerHint: String? = null, val createdAt: Instant)
data class GradingResultResponse(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)
data class StudyRecordResponse(
    val id: String,
    val question: QuestionItemResponse,
    val answer: String?,
    val gradingResult: GradingResultResponse?,
    val topic: String,
    val difficulty: Int,
    val answeredAt: Instant?,
    val isPublic: Boolean,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
)
data class RecordsPageResponse(val records: List<StudyRecordResponse>, val totalCount: Long, val limit: Int, val offset: Int)
data class BackendSnapshotResponse(
    val settings: BackendSettingsResponse,
    val api: APIStatusResponse?,
    val records: List<StudyRecordResponse>,
    val stats: StatsResponse?,
    val totalCount: Long,
    val serverTime: Instant,
)
data class TopicLevelRangeResponse(
    val level: Int,
    val average: Int,
    val sampleCount: Int,
    val centerLevel: Double,
    val lowerBound: Double,
    val upperBound: Double,
)
data class TopicStatsResponse(
    val topicKey: String,
    val topic: String,
    val topicAliases: List<String>,
    val count: Int,
    val average: Int,
    val best: Int,
    val correctRate: Int,
    val levelRange: TopicLevelRangeResponse,
    val latestAt: Instant,
    val records: List<StudyRecordResponse>,
)
data class StatsResponse(
    val totalResponses: Int,
    val totalTopics: Int,
    val topics: List<TopicStatsResponse>,
    val limit: Int,
    val offset: Int,
    val generatedAt: Instant,
)
data class CommunityQuestionResponse(
    val id: String,
    val question: String,
    val answer: String?,
    val gradingResult: GradingResultResponse?,
    val topic: String,
    val difficultyLevel: Int,
    val status: String,
    val source: String,
    val createdAt: Instant,
    val answeredAt: Instant?,
    val author: UserProfileResponse?,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
    val isLikedByMe: Boolean = false,
)
data class CommunityQuestionsResponse(val questions: List<CommunityQuestionResponse>, val totalCount: Long, val limit: Int, val offset: Int)
data class CommunityLikeResponse(val questionId: String, val likeCount: Int, val isLikedByMe: Boolean)
data class CommunityCommentResponse(val id: String, val questionId: String, val body: String, val createdAt: Instant, val author: UserProfileResponse)
data class CommunityCommentsResponse(val comments: List<CommunityCommentResponse>, val totalCount: Long, val limit: Int, val offset: Int)

fun UserEntity.toProfile() = UserProfileResponse(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = CommunityPageAccess(publicQuestions = allowPublicQuestions),
)

fun ScheduleEntity?.toSettings() = this?.let {
    BackendSettingsResponse(
        topic = it.topic,
        difficultyLevel = it.difficultyLevel,
        intervalMinutes = it.intervalMinutes,
        enabled = it.enabled,
        notificationSound = it.notificationSound,
        customPrompt = it.customPrompt,
        appLanguage = it.appLanguage,
        openaiModel = it.openaiModel,
        maxHistoryCount = it.maxHistoryCount,
        isQuestionPublic = it.questionPublic,
        openaiKeyConfigured = !it.openaiApiKeyCipher.isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: BackendSettingsResponse()

fun QuestionEntity.toRecord(stats: QuestionStatsEntity? = null) = StudyRecordResponse(
    id = id.toString(),
    question = QuestionItemResponse(question = question, expectedAnswerHint = hint, createdAt = createdAt),
    answer = answer,
    gradingResult = score?.let {
        GradingResultResponse(
            score = it,
            isCorrect = correct ?: (it >= 70),
            feedback = feedback ?: "",
            explanation = explanation ?: "",
        )
    },
    topic = topic,
    difficulty = difficultyLevel,
    answeredAt = answeredAt,
    isPublic = publicQuestion,
    likeCount = stats?.likeCount ?: 0,
    commentCount = stats?.commentCount ?: 0,
    viewCount = stats?.viewCount ?: 0,
)

fun QuestionEntity.toCommunity(author: UserProfileResponse?, stats: QuestionStatsEntity?, likedByMe: Boolean) = CommunityQuestionResponse(
    id = id.toString(),
    question = question,
    answer = answer,
    gradingResult = score?.let {
        GradingResultResponse(it, correct ?: (it >= 70), feedback ?: "", explanation ?: "")
    },
    topic = topic,
    difficultyLevel = difficultyLevel,
    status = status,
    source = source,
    createdAt = createdAt,
    answeredAt = answeredAt,
    author = author,
    likeCount = stats?.likeCount ?: 0,
    commentCount = stats?.commentCount ?: 0,
    viewCount = stats?.viewCount ?: 0,
    isLikedByMe = likedByMe,
)

fun QuestionCommentEntity.toResponse(author: UserProfileResponse) =
    CommunityCommentResponse(id.toString(), questionId.toString(), body, createdAt, author)
