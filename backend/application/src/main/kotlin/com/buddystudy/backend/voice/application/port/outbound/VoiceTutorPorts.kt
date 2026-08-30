package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.model.VoiceTutorGeneratedResult
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionCursor
import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class VoiceTutorPersonalization(
    val resumeMarkdown: String?,
    val interests: List<String>,
    val recentLearningEvidence: List<String>,
)

interface VoiceTutorQuotaQueryPort {
    suspend fun quota(userId: Long, now: Instant): VoiceTutorQuotaSnapshot?
}

interface VoiceTutorAvailabilityPort {
    fun isEnabled(): Boolean
}

/** Revalidates the device session backing a long-lived realtime socket. */
interface VoiceTutorRelayAuthorizationPort {
    suspend fun isAuthorized(
        userId: Long,
        deviceId: String,
        authSessionId: Long,
        now: Instant,
    ): Boolean
}

object UnavailableVoiceTutorAvailabilityPort : VoiceTutorAvailabilityPort {
    override fun isEnabled(): Boolean = false
}

object UnavailableVoiceTutorQuotaQueryPort : VoiceTutorQuotaQueryPort {
    override suspend fun quota(userId: Long, now: Instant): VoiceTutorQuotaSnapshot? = null
}

interface VoiceTutorPersistencePort : VoiceTutorQuotaQueryPort {
    suspend fun reconcileExpired(
        userId: Long,
        now: Instant,
        readyTimeoutSeconds: Long,
        heartbeatLeaseSeconds: Long,
    ): List<VoiceTutorSession>

    suspend fun reserve(
        userId: Long,
        studyId: Long,
        idempotencyKey: String,
        language: String,
        model: String,
        voice: String,
        maxSessionSeconds: Int,
        now: Instant,
    ): ReserveVoiceTutorSessionResult

    suspend fun activeSession(userId: Long): VoiceTutorSession?
    suspend fun findSession(userId: Long, sessionId: String): VoiceTutorSession?
    suspend fun sessions(userId: Long, limit: Int, cursor: VoiceTutorSessionCursor?): List<VoiceTutorSession>
    suspend fun sessionsAwaitingResult(
        limit: Int,
        now: Instant,
        processingLeaseSeconds: Long,
    ): List<VoiceTutorSession>
    suspend fun staleSessionUserIds(
        limit: Int,
        now: Instant,
        readyTimeoutSeconds: Long,
        heartbeatLeaseSeconds: Long,
    ): List<Long>
    suspend fun markActive(userId: Long, sessionId: String, now: Instant): VoiceTutorSession?
    suspend fun requestEnd(userId: Long, sessionId: String, now: Instant): VoiceTutorSession?
    suspend fun heartbeat(userId: Long, sessionId: String, now: Instant): VoiceTutorSessionStatus?
    suspend fun addAcceptedAudioBytes(userId: Long, sessionId: String, bytes: Long): Boolean
    suspend fun attachProviderSession(userId: Long, sessionId: String, providerSessionId: String, now: Instant): Boolean

    suspend fun appendTranscript(
        userId: Long,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
        occurredAt: Instant,
        maxSessionCharacters: Int,
        maxSessionTurns: Int,
    ): Boolean

    suspend fun transcript(userId: Long, sessionId: String, maxCharacters: Int): List<VoiceTutorTranscriptTurn>
    suspend fun result(userId: Long, sessionId: String): VoiceTutorResult?

    suspend fun finalize(
        userId: Long,
        sessionId: String,
        reason: String,
        failed: Boolean,
        failureMessage: String?,
        now: Instant,
    ): VoiceTutorSession?

    suspend fun beginResult(
        userId: Long,
        sessionId: String,
        promptVersion: String,
        now: Instant,
        processingLeaseSeconds: Long,
    ): Boolean
    suspend fun completeResult(userId: Long, generated: VoiceTutorGeneratedResult, sessionId: String, now: Instant)
    suspend fun failResult(userId: Long, sessionId: String, promptVersion: String, error: String, now: Instant)
}

interface VoiceTutorPersonalizationPort {
    suspend fun load(userId: Long, studyId: Long): VoiceTutorPersonalization
}

interface VoiceTutorSummaryPort {
    suspend fun summarize(
        session: VoiceTutorSession,
        transcript: List<VoiceTutorTranscriptTurn>,
    ): VoiceTutorGeneratedResult
}

data class VoiceTutorRealtimeRequest(
    val userId: Long,
    val model: String,
    val voice: String,
    val instructions: String,
)

data class VoiceTutorRelayTermination(
    val cancelActiveResponse: Boolean,
)

/**
 * Server-to-server realtime provider boundary. The caller owns client framing and
 * persistence; the adapter owns provider credentials, transport, and protocol setup.
 */
interface VoiceTutorRealtimePort {
    suspend fun relay(
        request: VoiceTutorRealtimeRequest,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Unit,
    )
}
