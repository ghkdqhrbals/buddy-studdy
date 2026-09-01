package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMutationConfirmationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearnerTurnAuthorization
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/** Read the accepted-turn identity, never private speech, for the voice confirmation fence. */
@Component
class VoiceTutorMutationConfirmationAdapter(private val database: DatabaseClient) : VoiceTutorMutationConfirmationPort {
    override suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long? = latestTurn(userId, sessionId, "USER")
    override suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long? = latestTurn(userId, sessionId, "TUTOR")

    override suspend fun learnerTurnAuthorization(
        userId: Long,
        sessionId: String,
        providerItemId: String,
        lessonRevision: Long,
        expectedQuestionProviderItemId: String?,
        expectedAnswerProviderItemId: String?,
        expectedTutorFeedbackProviderItemId: String?,
        expectedTutorNavigationOfferProviderItemId: String?,
    ): VoiceTutorLearnerTurnAuthorization? {
        if (userId <= 0 || sessionId.isBlank() || providerItemId.isBlank() || providerItemId.length > 191 || lessonRevision < 0) {
            return null
        }
        val latest = database.sql(
            """
            SELECT t.id, t.provider_item_id, t.role, t.sequence_number
            FROM voice_tutor_transcript_turns t
            JOIN voice_tutor_sessions s ON s.id = t.session_id
            WHERE s.user_id = :userId AND s.id = :sessionId AND s.status = 'ACTIVE'
              AND s.ended_at IS NULL AND t.lesson_revision = :lessonRevision
            ORDER BY t.sequence_number DESC, t.id DESC
            LIMIT 1
            """.trimIndent(),
        ).bind("userId", userId).bind("sessionId", sessionId).bind("lessonRevision", lessonRevision)
            .map { row, _ -> boundary(row) }.one().awaitSingleOrNull()?.takeIf {
            it.providerItemId == providerItemId && it.role == "USER"
        } ?: return null
        // Initial focus selection needs only the exact current learner item. Guided descent
        // additionally requires the controller-frozen question, answer, feedback and offer
        // identities. Feedback and offer may be one combined TUTOR item or two consecutive
        // TUTOR items; an unrelated tutor turn between either boundary fails closed.
        val expectedOffer = expectedTutorNavigationOfferProviderItemId?.takeIf(::validProviderItemId)
        val expectedFeedback = expectedTutorFeedbackProviderItemId?.takeIf(::validProviderItemId)
        val expectedAnswer = expectedAnswerProviderItemId?.takeIf(::validProviderItemId)
        val expectedQuestion = expectedQuestionProviderItemId?.takeIf(::validProviderItemId)
        val offer = expectedOffer?.let {
            previousRole(sessionId, lessonRevision, latest.sequenceNumber, "TUTOR")
                ?.takeIf { turn -> turn.providerItemId == expectedOffer }
        }
        val feedback = expectedFeedback?.let {
            offer?.let { acceptedOffer ->
                if (acceptedOffer.providerItemId == expectedFeedback) {
                    acceptedOffer
                } else {
                    previousRole(sessionId, lessonRevision, acceptedOffer.sequenceNumber, "TUTOR")
                        ?.takeIf { turn -> turn.providerItemId == expectedFeedback }
                }
            }
        }
        val answer = expectedAnswer?.let {
            feedback?.let { acceptedFeedback ->
                val beforeFeedback = previousRole(sessionId, lessonRevision, acceptedFeedback.sequenceNumber, "USER")
                    ?.takeIf { turn -> turn.providerItemId == expectedAnswer }
                val beforeOffer = offer?.let { acceptedOffer ->
                    previousRole(sessionId, lessonRevision, acceptedOffer.sequenceNumber, "USER")
                        ?.takeIf { turn -> turn.providerItemId == expectedAnswer }
                }
                beforeFeedback?.takeIf { beforeOffer != null }
            }
        }
        val question = expectedQuestion?.let {
            answer?.let { acceptedAnswer ->
                val beforeAnswer = previousRole(sessionId, lessonRevision, acceptedAnswer.sequenceNumber, "TUTOR")
                    ?.takeIf { turn -> turn.providerItemId == expectedQuestion }
                val beforeFeedback = feedback?.let { acceptedFeedback ->
                    previousRole(sessionId, lessonRevision, acceptedFeedback.sequenceNumber, "TUTOR")
                        ?.takeIf { turn -> turn.providerItemId == expectedQuestion }
                }
                beforeAnswer?.takeIf { beforeFeedback != null }
            }
        }
        return VoiceTutorLearnerTurnAuthorization(
            turnId = latest.id,
            completedExchange = offer != null && feedback != null && answer != null && question != null,
        )
    }

    private suspend fun previousRole(
        sessionId: String,
        lessonRevision: Long,
        beforeSequence: Long,
        role: String,
    ): TurnBoundary? = database.sql(
        """
        SELECT id, provider_item_id, role, sequence_number
        FROM voice_tutor_transcript_turns
        WHERE session_id = :sessionId AND lesson_revision = :lessonRevision
          AND sequence_number < :beforeSequence AND role = :role
        ORDER BY sequence_number DESC, id DESC
        LIMIT 1
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("lessonRevision", lessonRevision)
        .bind("beforeSequence", beforeSequence).bind("role", role)
        .map { row, _ -> boundary(row) }.one().awaitSingleOrNull()

    private fun boundary(row: io.r2dbc.spi.Row) = TurnBoundary(
        id = (row.get("id") as Number).toLong(),
        providerItemId = row.get("provider_item_id", String::class.java).orEmpty(),
        role = row.get("role", String::class.java).orEmpty(),
        sequenceNumber = (row.get("sequence_number") as Number).toLong(),
    )

    private fun validProviderItemId(value: String): Boolean = value.isNotBlank() && value.length <= 191

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

    private data class TurnBoundary(
        val id: Long,
        val providerItemId: String,
        val role: String,
        val sequenceNumber: Long,
    )
}
