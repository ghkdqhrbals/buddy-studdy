package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMutationConfirmationPort
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/** Read the accepted-turn identity, never private speech, for the voice confirmation fence. */
@Component
class VoiceTutorMutationConfirmationAdapter(private val database: DatabaseClient) : VoiceTutorMutationConfirmationPort {
    override suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long? = latestTurn(userId, sessionId, "USER")
    override suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long? = latestTurn(userId, sessionId, "TUTOR")

    private suspend fun latestTurn(userId: Long, sessionId: String, role: String): Long? = database.sql(
        """
        SELECT t.id FROM voice_tutor_transcript_turns t
        JOIN voice_tutor_sessions s ON s.id = t.session_id
        WHERE s.user_id = :userId AND s.id = :sessionId AND s.status = 'ACTIVE'
          AND s.ended_at IS NULL AND t.role = :role
        ORDER BY t.id DESC LIMIT 1
        """.trimIndent(),
    ).bind("userId", userId).bind("sessionId", sessionId).bind("role", role)
        .map { row, _ -> (row.get("id") as Number).toLong() }.one().awaitSingleOrNull()
}
