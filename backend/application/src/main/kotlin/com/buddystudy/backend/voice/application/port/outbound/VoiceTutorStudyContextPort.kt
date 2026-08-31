package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot

/** Session-owned, bounded snapshots keep later summaries independent of renamed/deleted study nodes. */
interface VoiceTutorStudyContextPort {
    suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot>
    suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot>
    suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot>
}

/** Compatibility for isolated adapters/tests; normal runtime injects the persistent implementation. */
object UnavailableVoiceTutorStudyContextPort : VoiceTutorStudyContextPort {
    override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> =
        session.studyId?.takeIf { it > 0 }?.let {
            listOf(VoiceTutorStudySnapshot(it, null, session.topic, session.difficulty))
        }.orEmpty()

    override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>) =
        emptyList<VoiceTutorStudySnapshot>()

    override suspend fun list(userId: Long, sessionId: String) = emptyList<VoiceTutorStudySnapshot>()
}
