package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorRecording
import com.buddystudy.voice.domain.VoiceTutorRecordingStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import java.time.Instant

data class VoiceTutorQuotaResponse(
    val tierCode: String,
    val periodStartedAt: Instant,
    val resetAt: Instant,
    val limitSeconds: Int,
    val usedSeconds: Int,
    val reservedSeconds: Int,
    val remainingSeconds: Int,
)

data class VoiceTutorStatusResponse(
    val eligible: Boolean,
    val reason: String?,
    val tierCode: String,
    val quota: VoiceTutorQuotaResponse,
    val maxSessionSeconds: Int,
    val recording: VoiceTutorRecordingResponse,
    val activeSession: VoiceTutorSessionResponse?,
)

data class VoiceTutorSessionResponse(
    val sessionId: String,
    val studyId: Long?,
    val topic: String,
    val difficulty: Int,
    val language: String,
    val state: VoiceTutorSessionStatus,
    val resultStatus: VoiceTutorResultStatus,
    val createdAt: Instant,
    val connectedAt: Instant?,
    val endedAt: Instant?,
    val hardEndsAt: Instant,
    val durationSeconds: Int,
    val chargedSeconds: Int,
)

data class VoiceTutorCreateSessionResponse(
    val sessionId: String,
    val state: VoiceTutorSessionStatus,
    val websocketUrl: String,
    val sdpUrl: String,
    val controlWebsocketUrl: String,
    val websocketProtocol: String = WEBSOCKET_PROTOCOL,
    val realtimeTransport: String = REALTIME_TRANSPORT,
    val controlWebsocketProtocol: String = CONTROL_WEBSOCKET_PROTOCOL,
    val createdAt: Instant,
    val hardEndsAt: Instant,
    val recording: VoiceTutorRecordingResponse,
    val quota: VoiceTutorQuotaResponse,
) {
    companion object {
        const val WEBSOCKET_PROTOCOL = "buddystudy.voice.v1"
        const val REALTIME_TRANSPORT = "WEBRTC"
        const val CONTROL_WEBSOCKET_PROTOCOL = "buddystudy.voice.control.v2"
    }
}

data class VoiceTutorTranscriptTurnResponse(
    val id: Long,
    val role: VoiceTutorTranscriptRole,
    val transcript: String,
    val occurredAt: Instant,
    val source: com.buddystudy.voice.domain.VoiceTutorTranscriptSource = com.buddystudy.voice.domain.VoiceTutorTranscriptSource.AUDIO,
    val interrupted: Boolean = false,
)

data class VoiceTutorRecordingUploadResponse(
    val uploadUrl: String,
    val method: String = "PUT",
    val headers: Map<String, String>,
    val requiredHeaders: Map<String, String> = headers,
    val recordingId: String,
    val expiresAt: Instant,
    val recording: VoiceTutorRecordingResponse,
)

data class VoiceTutorRecordingDownloadResponse(
    val url: String,
    val downloadUrl: String = url,
    val expiresAt: Instant,
    val recording: VoiceTutorRecordingResponse,
)

data class VoiceTutorRecordingResponse(
    val enabled: Boolean,
    val consentRequired: Boolean = true,
    val available: Boolean,
    val status: VoiceTutorRecordingStatus?,
    val retentionDays: Int,
    val expiresAt: Instant?,
    val recordingId: String?,
    val contentType: String?,
    val contentLength: Long?,
    val durationSeconds: Int?,
)

data class VoiceTutorRecordingRetentionResult(
    val deletedRecordings: Int,
    val attemptedRecordings: Int,
    val capped: Boolean,
    val completedPrefixCleanups: Int = 0,
    val attemptedPrefixCleanups: Int = 0,
)

data class VoiceTutorResultResponse(
    val status: VoiceTutorResultStatus,
    val summaryMarkdown: String?,
    val strengths: List<String>,
    val improvements: List<String>,
    val nextSteps: List<String>,
    val model: String?,
    val promptVersion: String,
    val errorMessage: String?,
    val createdAt: Instant,
    val explorations: List<VoiceTutorExplorationResponse> = emptyList(),
)

data class VoiceTutorExplorationResponse(
    val topic: String,
    val studyId: Long?,
    val difficulty: Int?,
    val depthSummary: String,
    val exchanges: List<VoiceTutorLearningExchangeResponse>,
)

data class VoiceTutorLearningExchangeResponse(
    val kind: VoiceTutorExchangeKind,
    val question: String,
    val answer: String,
    val score: Int?,
    val strengths: List<String>,
    val improvements: List<String>,
    val questionTurnId: Long,
    val answerTurnIds: List<Long>,
    val feedbackTurnIds: List<Long>,
)

data class VoiceTutorSessionDetailResponse(
    val sessionId: String,
    val studyId: Long?,
    val topic: String,
    val difficulty: Int,
    val language: String,
    val state: VoiceTutorSessionStatus,
    val resultStatus: VoiceTutorResultStatus,
    val createdAt: Instant,
    val connectedAt: Instant?,
    val endedAt: Instant?,
    val hardEndsAt: Instant,
    val durationSeconds: Int,
    val chargedSeconds: Int,
    val quota: VoiceTutorQuotaResponse,
    val transcriptTurns: List<VoiceTutorTranscriptTurnResponse>,
    val result: VoiceTutorResultResponse?,
    val recording: VoiceTutorRecordingResponse,
)

data class VoiceTutorSessionsPageResponse(
    val sessions: List<VoiceTutorSessionResponse>,
    val nextCursor: String?,
)

data class VoiceTutorGeneratedResult(
    val summaryMarkdown: String,
    val strengths: List<String>,
    val improvements: List<String>,
    val nextSteps: List<String>,
    val model: String,
    val promptVersion: String,
    val explorations: List<VoiceTutorExploration> = emptyList(),
    /** Internal source-bound metadata; never included in public/session response DTOs. */
    val postCallEvidence: com.buddystudy.voice.domain.VoiceTutorPostCallEvidence? = null,
)

data class VoiceTutorRelayContext(
    val session: VoiceTutorSession,
    val instructions: String,
)

data class VoiceTutorQuotaSnapshot(
    val tierCode: String,
    val periodStartedAt: Instant,
    val periodEndsAt: Instant,
    val baseSeconds: Int,
    val usedSeconds: Int,
    val reservedSeconds: Int,
    /** Membership entitlement is distinct from a positive administrator-configured allowance. */
    val planEligible: Boolean = tierCode == "TIER2" || tierCode == "TIER3",
) {
    val remainingSeconds: Int
        get() = (baseSeconds - usedSeconds - reservedSeconds).coerceAtLeast(0)

    val eligible: Boolean
        get() = planEligible
}

data class VoiceTutorSessionCursor(
    val createdAt: Instant,
    val sessionId: String,
)

data class ReservedVoiceTutorSession(
    val session: VoiceTutorSession,
    val quota: VoiceTutorQuotaSnapshot,
)

sealed interface ReserveVoiceTutorSessionResult {
    data class Reserved(val value: ReservedVoiceTutorSession) : ReserveVoiceTutorSessionResult
    data object IdempotencyConflict : ReserveVoiceTutorSessionResult
    data class NotEligible(val quota: VoiceTutorQuotaSnapshot) : ReserveVoiceTutorSessionResult
    data class Exhausted(val quota: VoiceTutorQuotaSnapshot) : ReserveVoiceTutorSessionResult
    data class ActiveSession(val session: VoiceTutorSession, val quota: VoiceTutorQuotaSnapshot) : ReserveVoiceTutorSessionResult
    data object StudyNotFound : ReserveVoiceTutorSessionResult
    data object UserNotFound : ReserveVoiceTutorSessionResult
}

fun VoiceTutorQuotaSnapshot.toResponse() = VoiceTutorQuotaResponse(
    tierCode = tierCode,
    periodStartedAt = periodStartedAt,
    resetAt = periodEndsAt,
    limitSeconds = baseSeconds,
    usedSeconds = usedSeconds,
    reservedSeconds = reservedSeconds,
    remainingSeconds = remainingSeconds,
)

fun VoiceTutorSession.toResponse(now: Instant = Instant.now()): VoiceTutorSessionResponse {
    val duration = when {
        connectedAt == null -> 0L
        endedAt != null -> java.time.Duration.between(connectedAt, endedAt).seconds
        else -> java.time.Duration.between(connectedAt, now.coerceAtMost(hardEndsAt)).seconds
    }.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return VoiceTutorSessionResponse(
        sessionId = id,
        studyId = studyId,
        topic = topic,
        difficulty = difficulty,
        language = language,
        state = status,
        resultStatus = resultStatus,
        createdAt = createdAt,
        connectedAt = connectedAt,
        endedAt = endedAt,
        hardEndsAt = hardEndsAt,
        durationSeconds = duration,
        chargedSeconds = chargedSeconds,
    )
}

fun VoiceTutorTranscriptTurn.toResponse() = VoiceTutorTranscriptTurnResponse(
    id = id,
    role = role,
    transcript = transcript,
    occurredAt = occurredAt,
    source = source,
    interrupted = interrupted,
)

fun VoiceTutorResult.toResponse() = VoiceTutorResultResponse(
    status = status,
    summaryMarkdown = summaryMarkdown,
    strengths = strengths,
    improvements = improvements,
    nextSteps = nextSteps,
    model = model,
    promptVersion = promptVersion,
    errorMessage = errorMessage,
    createdAt = createdAt,
    explorations = explorations.map(VoiceTutorExploration::toResponse),
)

fun VoiceTutorExploration.toResponse() = VoiceTutorExplorationResponse(
    topic = topic,
    studyId = studyId,
    difficulty = difficulty,
    depthSummary = depthSummary,
    exchanges = exchanges.map(VoiceTutorLearningExchange::toResponse),
)

fun VoiceTutorLearningExchange.toResponse() = VoiceTutorLearningExchangeResponse(
    kind = kind,
    question = question,
    answer = answer,
    score = score,
    strengths = strengths,
    improvements = improvements,
    questionTurnId = questionTurnId,
    answerTurnIds = answerTurnIds,
    feedbackTurnIds = feedbackTurnIds,
)

fun VoiceTutorRecording.toResponse(enabled: Boolean, retentionDays: Int, now: Instant) = VoiceTutorRecordingResponse(
    enabled = enabled,
    available = status == VoiceTutorRecordingStatus.AVAILABLE && retainedUntil.isAfter(now),
    status = status,
    retentionDays = retentionDays,
    expiresAt = retainedUntil,
    recordingId = sessionId,
    contentType = contentType,
    contentLength = actualBytes ?: expectedBytes,
    durationSeconds = ((durationMilliseconds + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
)

private fun Instant.coerceAtMost(other: Instant): Instant = if (isAfter(other)) other else this
