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
    val endedAt: Instant?,
    val finalizedAt: Instant?,
    val endReason: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VoiceTutorTranscriptTurn(
    val id: Long,
    val sessionId: String,
    val providerItemId: String,
    val role: VoiceTutorTranscriptRole,
    val transcript: String,
    val sequenceNumber: Long,
    val occurredAt: Instant,
)

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
)
