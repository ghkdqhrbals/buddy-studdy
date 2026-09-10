package com.buddystudy.voice.domain

import java.time.Instant

enum class VoiceTutorSessionStatus {
    READY,
    ACTIVE,
    ENDING,
    COMPLETED,
    FAILED,
}

enum class VoiceTutorResultStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

enum class VoiceTutorTranscriptRole {
    USER,
    TUTOR,
}

enum class VoiceTutorRecordingStatus {
    PENDING,
    AVAILABLE,
    FAILED,
    DELETED,
}

data class VoiceTutorSession(
    val id: String,
    val userId: Long,
    val studyId: Long?,
    val idempotencyKey: String,
    val providerSessionId: String?,
    val status: VoiceTutorSessionStatus,
    val resultStatus: VoiceTutorResultStatus,
    val language: String,
    val model: String,
    val voice: String,
    val topic: String,
    val difficulty: Int,
    val periodStartedAt: Instant,
    val periodEndsAt: Instant,
    val reservedSeconds: Int,
    val chargedSeconds: Int,
    val maxSessionSeconds: Int,
    val hardEndsAt: Instant,
    val connectedAt: Instant?,
    val relayHeartbeatAt: Instant?,
    val acceptedAudioBytes: Long,
    val recordingConsentedAt: Instant? = null,
    val recordingConsentVersion: String? = null,
    val endedAt: Instant?,
    val finalizedAt: Instant?,
    val endReason: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Accepted lesson identity survives the nullable live-study FK when that node is deleted. */
    val acceptedStudyId: Long? = studyId,
    /**
     * Frozen at reservation time. True only when this session owns every
     * remaining second in its finite monthly allowance. Keeping this on the
     * session avoids reclassifying a 60-minute call after an administrator
     * changes the user's allowance while the call is active.
     */
    val monthlyQuotaExhaustsAtHardEnd: Boolean = false,
    /** Missing native source must never be interpreted as a complete learner answer. */
    val postCallTranscriptIncomplete: Boolean = false,
)

data class VoiceTutorRecording(
    val sessionId: String,
    val userId: Long,
    val objectKey: String,
    val status: VoiceTutorRecordingStatus,
    val contentType: String,
    val expectedBytes: Long,
    val actualBytes: Long?,
    val sha256Hex: String,
    val durationMilliseconds: Long,
    val consentedAt: Instant,
    val consentVersion: String,
    val uploadExpiresAt: Instant,
    val retainedUntil: Instant,
    val completedAt: Instant?,
    val deletedAt: Instant?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Source is derived from a server-reserved item identity, never from learner-authored text. */
enum class VoiceTutorTranscriptSource {
    AUDIO,
    STRUCTURED_INPUT;

    companion object {
        const val STRUCTURED_ITEM_PREFIX = "buddystudy-user-input-"
    }
}

data class VoiceTutorTranscriptTurn(
    val id: Long,
    val sessionId: String,
    val providerItemId: String,
    val role: VoiceTutorTranscriptRole,
    val transcript: String,
    val sequenceNumber: Long,
    val occurredAt: Instant,
    /** Server-owned response/input epoch; legacy is zero and an explicitly unknown binding is -1. */
    val lessonRevision: Long = 0,
    /** Server-persisted proof that this USER turn answered the exact TUTOR study-question row. */
    val studyQuestionTurnId: Long? = null,
    /** Server-persisted proof that this TUTOR feedback evaluates one exact linked USER answer. */
    val studyAnswerTurnId: Long? = null,
    /** Server semantic attestation that this USER turn asked about its saved lesson focus. */
    val askedStudyQuestion: Boolean = false,
    /** Server-owned proof that this completed TUTOR item was issued for a substantive study question. */
    val isStudyQuestion: Boolean = false,
    /** Clean native-realtime source; learning semantics are verified only after the call. */
    val postCallEvidence: Boolean = false,
) {
    val source: VoiceTutorTranscriptSource
        get() = if (providerItemId.startsWith(VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX)) {
            VoiceTutorTranscriptSource.STRUCTURED_INPUT
        } else VoiceTutorTranscriptSource.AUDIO
}

data class VoiceTutorResult(
    val sessionId: String,
    val status: VoiceTutorResultStatus,
    val summaryMarkdown: String?,
    val strengths: List<String>,
    val improvements: List<String>,
    val nextSteps: List<String>,
    val model: String?,
    val promptVersion: String,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val explorations: List<VoiceTutorExploration> = emptyList(),
)
