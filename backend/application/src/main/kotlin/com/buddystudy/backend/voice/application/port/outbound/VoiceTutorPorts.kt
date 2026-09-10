package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.model.VoiceTutorGeneratedResult
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionCursor
import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorRecording
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

object VoiceTutorResultFailureCodes {
    const val INCOMPLETE_TRANSCRIPT = "INCOMPLETE_TRANSCRIPT"
}

data class VoiceTutorPersonalization(
    val resumeMarkdown: String?,
    val interests: List<String>,
    val recentLearningEvidence: List<String>,
)

/**
 * One exclusive attempt to generate a session result. The token and persisted
 * claim epoch are deliberately absent from the public result model and must
 * accompany every claimed terminal write so a worker whose lease was reclaimed
 * cannot finish the newer attempt, including across mixed-version rollouts.
 */
data class VoiceTutorResultClaim(
    val sessionId: String,
    val claimToken: UUID,
    /** Exact DATETIME(6) value persisted when this claim was acquired. */
    val claimedAt: Instant,
) {
    override fun toString(): String =
        "VoiceTutorResultClaim(sessionId=[redacted], claimToken=[redacted], claimedAt=[redacted])"
}

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
    /** Idempotent owner-bound fence against summarizing incomplete native source. */
    suspend fun markTranscriptIncomplete(userId: Long, sessionId: String, now: Instant): Boolean = false

    suspend fun reconcileExpired(
        userId: Long,
        now: Instant,
        readyTimeoutSeconds: Long,
        heartbeatLeaseSeconds: Long,
    ): List<VoiceTutorSession>

    suspend fun reserve(
        userId: Long,
        studyId: Long?,
        idempotencyKey: String,
        language: String,
        model: String,
        voice: String,
        maxSessionSeconds: Int,
        now: Instant,
        recordingConsentedAt: Instant? = null,
        recordingConsentVersion: String? = null,
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
    suspend fun terminalWebRtcSessionsAwaitingHangup(limit: Int): List<VoiceTutorSession>
    suspend fun markActive(userId: Long, sessionId: String, now: Instant): VoiceTutorSession?
    suspend fun requestEnd(userId: Long, sessionId: String, now: Instant): VoiceTutorSession?
    /**
     * Atomically moves the exact monthly-exhaustion session into its spoken
     * terminal window. Repeated calls observe the same ENDING row; a normal
     * per-session deadline can never enter this path.
     */
    suspend fun beginQuotaExhaustionNotice(
        userId: Long,
        sessionId: String,
        now: Instant,
        noticeLeadSeconds: Long,
    ): VoiceTutorSession? = null
    suspend fun heartbeat(userId: Long, sessionId: String, now: Instant): VoiceTutorSessionStatus?
    suspend fun addAcceptedAudioBytes(userId: Long, sessionId: String, bytes: Long): Boolean
    suspend fun attachProviderSession(userId: Long, sessionId: String, providerSessionId: String, now: Instant): Boolean
    suspend fun clearWebRtcProviderSession(
        userId: Long,
        sessionId: String,
        providerSessionId: String,
        now: Instant,
    ): Boolean

    suspend fun appendTranscript(
        userId: Long,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
        occurredAt: Instant,
        maxSessionCharacters: Int,
        maxSessionTurns: Int,
        lessonRevision: Long = 0,
        /**
         * Exact persisted tutor item classified by the final input assessor as the
         * substantive study question answered by this USER turn. Persistence must
         * verify the owner, lesson focus, role, revision and ordering before saving
         * the durable link; arbitrary transcript text never establishes learning.
         */
        studyQuestionProviderItemId: String? = null,
        /** Exact linked learner answer evaluated by this server-attested TUTOR feedback item. */
        studyAnswerProviderItemId: String? = null,
        /** Server semantic attestation that this final USER turn asks about the saved lesson focus. */
        askedStudyQuestion: Boolean = false,
        /** Server-owned proof attached only to a completed TUTOR study-question item. */
        isStudyQuestion: Boolean = false,
        /**
         * Exact ordered provider identities for all durable USER parts semantically
         * contributing to one completed answer. This is accepted only together
         * with studyQuestionProviderItemId and is promoted atomically.
         */
        studyAnswerProviderItemIds: List<String> = emptyList(),
        /** Trusted server receipt proves this USER final ASR preceded the quota fence. */
        acceptedBeforeQuotaCutoff: Boolean = false,
        /** Native realtime raw source, never a live semantic attestation. */
        postCallEvidence: Boolean = false,
        /** Frozen provider conversation order, independent of final ASR arrival order. */
        conversationSequence: Long? = null,
        /** Private incomplete TUTOR archive; never source authority or proof of heard/completed speech. */
        interrupted: Boolean = false,
        /** Private saved-question/answer source; only an explicit canonical submission may use it. */
        canonicalAnswerSource: Boolean = false,
    ): Boolean

    suspend fun transcript(userId: Long, sessionId: String, maxCharacters: Int): List<VoiceTutorTranscriptTurn>
    /** Exact persisted question and its complete following learner run; never a clipped history prefix. */
    suspend fun canonicalAnswerTurns(
        userId: Long,
        sessionId: String,
        latestLearnerProviderItemId: String,
        precedingTutorProviderItemId: String,
        lessonRevision: Long,
    ): List<VoiceTutorTranscriptTurn> = emptyList()

    /**
     * Before submitting a canonical QUESTION answer, exclude its exact question and learner source
     * from native post-call learning eligibility. Text, roles, sequence and private history survive.
     */
    suspend fun excludeCanonicalQuestionTurns(
        userId: Long,
        sessionId: String,
        providerItemIds: List<String>,
    ): Boolean = false
    suspend fun hasVerifiedLearningExchange(userId: Long, sessionId: String): Boolean
    /** Unclassified native dialogue in a saved focus; never itself proves learning. */
    suspend fun hasPostCallLearningCandidates(userId: Long, sessionId: String): Boolean = false
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
    ): VoiceTutorResultClaim?

    suspend fun completeResult(
        userId: Long,
        claim: VoiceTutorResultClaim,
        generated: VoiceTutorGeneratedResult,
        now: Instant,
    )

    suspend fun failClaimedResult(
        userId: Long,
        claim: VoiceTutorResultClaim,
        promptVersion: String,
        error: String,
        now: Instant,
    )

    /** Records finalization with no transcript before any summary worker owns a claim. */
    suspend fun failUnclaimedResult(
        userId: Long,
        sessionId: String,
        promptVersion: String,
        error: String,
        now: Instant,
    )
}

data class VoiceTutorRecordingSession(
    val sessionId: String,
    val userId: Long,
    val status: VoiceTutorSessionStatus,
    val maxSessionSeconds: Int,
    val durationSeconds: Int,
    val endedAt: Instant?,
    val recordingConsentedAt: Instant?,
    val recordingConsentVersion: String?,
)

data class VoiceTutorRecordingObjectMetadata(
    val contentType: String,
    val byteSize: Long,
    val checksumSha256Base64: String?,
)

data class VoiceTutorRecordingUploadGrant(
    val objectKey: String,
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant,
)

data class VoiceTutorRecordingDownloadGrant(
    val downloadUrl: String,
    val expiresAt: Instant,
)

data class VoiceTutorRecordingPrefixCleanup(
    val userId: Long,
    val cleanupUntil: Instant,
)

data class VoiceTutorRecordingUploadSnapshot(
    val objectKey: String,
    val contentType: String,
    val expectedBytes: Long,
    val sha256Hex: String,
    val durationMilliseconds: Long,
    val uploadExpiresAt: Instant,
    val updatedAt: Instant,
)

fun VoiceTutorRecording.uploadSnapshot() = VoiceTutorRecordingUploadSnapshot(
    objectKey = objectKey,
    contentType = contentType,
    expectedBytes = expectedBytes,
    sha256Hex = sha256Hex,
    durationMilliseconds = durationMilliseconds,
    uploadExpiresAt = uploadExpiresAt,
    updatedAt = updatedAt,
)

interface VoiceTutorRecordingPersistencePort {
    suspend fun latestUploadExpiry(userId: Long): Instant?
    suspend fun schedulePrefixCleanup(userId: Long, cleanupUntil: Instant, now: Instant)
    suspend fun pendingPrefixCleanups(now: Instant, limit: Int): List<VoiceTutorRecordingPrefixCleanup>
    suspend fun deferPrefixCleanup(
        userId: Long,
        expectedCleanupUntil: Instant,
        attemptedAt: Instant,
        nextAttemptAt: Instant,
        failureMessage: String?,
    ): Boolean
    suspend fun session(userId: Long, sessionId: String): VoiceTutorRecordingSession?
    suspend fun recording(userId: Long, sessionId: String): VoiceTutorRecording?
    suspend fun savePending(
        recording: VoiceTutorRecording,
        expected: VoiceTutorRecordingUploadSnapshot?,
    ): VoiceTutorRecording?
    suspend fun markAvailable(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        actualBytes: Long,
        completedAt: Instant,
    ): VoiceTutorRecording?
    suspend fun markFailed(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        failureMessage: String,
        now: Instant,
    ): VoiceTutorRecording?
    suspend fun markDeleted(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecording,
        deletedAt: Instant,
    ): VoiceTutorRecording?
    suspend fun markExpiredDeleted(expected: VoiceTutorRecording, deletedAt: Instant): VoiceTutorRecording?
    suspend fun expired(
        now: Instant,
        uploadCompletionSafetySeconds: Long,
        includeOrdinaryRetention: Boolean,
        limit: Int,
    ): List<VoiceTutorRecording>
}

object UnavailableVoiceTutorRecordingPersistencePort : VoiceTutorRecordingPersistencePort {
    override suspend fun latestUploadExpiry(userId: Long): Instant? = null
    override suspend fun schedulePrefixCleanup(userId: Long, cleanupUntil: Instant, now: Instant) = Unit
    override suspend fun pendingPrefixCleanups(now: Instant, limit: Int): List<VoiceTutorRecordingPrefixCleanup> = emptyList()
    override suspend fun deferPrefixCleanup(
        userId: Long,
        expectedCleanupUntil: Instant,
        attemptedAt: Instant,
        nextAttemptAt: Instant,
        failureMessage: String?,
    ): Boolean = false
    override suspend fun session(userId: Long, sessionId: String): VoiceTutorRecordingSession? = null
    override suspend fun recording(userId: Long, sessionId: String): VoiceTutorRecording? = null
    override suspend fun savePending(
        recording: VoiceTutorRecording,
        expected: VoiceTutorRecordingUploadSnapshot?,
    ): VoiceTutorRecording =
        error("Voice Tutor recording persistence is not configured.")
    override suspend fun markAvailable(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        actualBytes: Long,
        completedAt: Instant,
    ): VoiceTutorRecording? = null
    override suspend fun markFailed(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        failureMessage: String,
        now: Instant,
    ): VoiceTutorRecording? = null
    override suspend fun markDeleted(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecording,
        deletedAt: Instant,
    ): VoiceTutorRecording? = null
    override suspend fun markExpiredDeleted(
        expected: VoiceTutorRecording,
        deletedAt: Instant,
    ): VoiceTutorRecording? = null
    override suspend fun expired(
        now: Instant,
        uploadCompletionSafetySeconds: Long,
        includeOrdinaryRetention: Boolean,
        limit: Int,
    ): List<VoiceTutorRecording> = emptyList()
}

interface VoiceTutorRecordingStoragePort {
    suspend fun presignUpload(
        userId: Long,
        sessionId: String,
        contentType: String,
        byteSize: Long,
        sha256Hex: String,
        expiresAt: Instant,
    ): VoiceTutorRecordingUploadGrant

    suspend fun inspect(objectKey: String): VoiceTutorRecordingObjectMetadata?
    suspend fun presignDownload(objectKey: String, sessionId: String, expiresAt: Instant): VoiceTutorRecordingDownloadGrant
    suspend fun delete(objectKey: String)
    suspend fun deleteAll(userId: Long)
}

object UnavailableVoiceTutorRecordingStoragePort : VoiceTutorRecordingStoragePort {
    private fun unavailable(): Nothing = error("Voice Tutor recording storage is not configured.")

    override suspend fun presignUpload(
        userId: Long,
        sessionId: String,
        contentType: String,
        byteSize: Long,
        sha256Hex: String,
        expiresAt: Instant,
    ): VoiceTutorRecordingUploadGrant = unavailable()

    override suspend fun inspect(objectKey: String): VoiceTutorRecordingObjectMetadata? = unavailable()
    override suspend fun presignDownload(objectKey: String, sessionId: String, expiresAt: Instant): VoiceTutorRecordingDownloadGrant = unavailable()
    override suspend fun delete(objectKey: String) = unavailable()
    override suspend fun deleteAll(userId: Long) = unavailable()
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
    val language: String,
)

data class VoiceTutorRelayTermination(
    val cancelActiveResponse: Boolean,
    val spokenNotice: VoiceTutorSpokenTerminationNotice? = null,
    /** Do not release/finalize a pre-armed spoken terminal before this instant. */
    val notBefore: Instant? = null,
    /** Only an explicit learner end may archive generated text from the unfinished response. */
    val preserveInterruptedTutor: Boolean = false,
)

enum class VoiceTutorSpokenTerminationNotice {
    MONTHLY_QUOTA_EXHAUSTED,
}

/**
 * The notice is pre-armed so it can be heard before a provider's own 60-minute
 * ceiling, while settlement remains capped at the exact reserved boundary.
 */
object VoiceTutorQuotaExhaustionPolicy {
    const val PROVIDER_HARD_CAP_SECONDS: Int = 3_600
    const val NOTICE_LEAD_SECONDS: Long = 8
    const val NOTICE_GRACE_SECONDS: Long = 20
}

/**
 * Server-to-server realtime provider boundary. The caller owns client framing and
 * persistence; the adapter owns provider credentials, transport, and protocol setup.
 * The callback returns true only when this exact transcript was newly persisted.
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
        ) -> Boolean,
    )
}
