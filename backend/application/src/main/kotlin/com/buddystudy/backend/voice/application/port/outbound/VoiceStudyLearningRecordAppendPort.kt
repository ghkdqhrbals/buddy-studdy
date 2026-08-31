package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.voice.domain.VoiceTutorExploration
import java.time.Instant

interface VoiceStudyLearningRecordAppendPort {
    /** Invoked inside the summary completion transaction, including translation outbox work. */
    suspend fun appendCompletedSession(
        userId: Long,
        sessionId: String,
        explorations: List<VoiceTutorExploration>,
        now: Instant,
    )

    /** Only already extracted, source-backed results; never regenerates or re-grades old calls. */
    suspend fun completedCandidates(limit: Int): List<VoiceStudyLearningRecordProjectionCandidate>
}

data class VoiceStudyLearningRecordProjectionCandidate(
    val userId: Long,
    val sessionId: String,
    val explorations: List<VoiceTutorExploration>,
)

object UnavailableVoiceStudyLearningRecordAppendPort : VoiceStudyLearningRecordAppendPort {
    override suspend fun appendCompletedSession(
        userId: Long, sessionId: String, explorations: List<VoiceTutorExploration>, now: Instant,
    ) {
        check(explorations.isEmpty()) { "Voice study learning record storage is not configured." }
    }
    override suspend fun completedCandidates(limit: Int): List<VoiceStudyLearningRecordProjectionCandidate> = emptyList()
}
