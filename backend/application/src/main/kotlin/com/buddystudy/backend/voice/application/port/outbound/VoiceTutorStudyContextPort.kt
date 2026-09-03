package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot

/** Session-owned, bounded snapshots keep later summaries independent of renamed/deleted study nodes. */
interface VoiceTutorStudyContextPort {
    /**
     * Complete first-turn owner snapshot or null. Implementations must return null when the call already has a
     * persisted USER turn, the owner has more than the bounded maximum, or any row is malformed.
     */
    suspend fun initialMutationSnapshot(
        userId: Long,
        sessionId: String,
        maxCandidates: Int = VoiceTutorInitialStudyMutationSnapshot.MAX_CANDIDATES,
    ): VoiceTutorInitialStudyMutationSnapshot? = null

    /** Current view only: one effective snapshot per node for the next response. */
    suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot>
    suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot>
    /** Initial captures plus every immutable revision, for question-time evidence resolution. */
    suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot>
    /**
     * Adopt an explicit saved node change and return the current view. The caller must capture a
     * baseline before mutating; inactive, unowned, missing-baseline and exhausted revisions fail.
     * Ordinary reads/remember never revise a lesson, and a same-metadata replay is a no-op.
     */
    suspend fun revise(userId: Long, sessionId: String, studyId: Long): List<VoiceTutorStudySnapshot> =
        error("Voice lesson study revisions are not available.")
    suspend fun currentRevision(userId: Long, sessionId: String): Long =
        list(userId, sessionId).maxOfOrNull { it.revision } ?: 0
}

/** Compatibility for isolated adapters/tests; normal runtime injects the persistent implementation. */
object UnavailableVoiceTutorStudyContextPort : VoiceTutorStudyContextPort {
    override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> =
        session.acceptedStudyId?.takeIf { it > 0 }?.let {
            listOf(VoiceTutorStudySnapshot(it, null, session.topic, session.difficulty))
        }.orEmpty()

    override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>) =
        emptyList<VoiceTutorStudySnapshot>()

    override suspend fun list(userId: Long, sessionId: String) = emptyList<VoiceTutorStudySnapshot>()
}
