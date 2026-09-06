package com.buddystudy.backend.voice.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

/**
 * Isolated metadata-only SELECT contracts. Deliberately no transcript column:
 * confirmation lookups load only role, revision and opaque provider identity.
 * The IDs prove persistence order only, not private speech or semantic intent.
 * This is not a MySQL migration/integration test and uses no real account/audio.
 */
@Timeout(10)
class VoiceTutorMutationConfirmationAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-confirmation-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = VoiceTutorMutationConfirmationAdapter(database)

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        execute(
            "create table voice_tutor_sessions (id varchar(36) primary key, user_id bigint not null, status varchar(20) not null, ended_at timestamp(6))",
        )
        execute(
            "create table voice_tutor_transcript_turns (id bigint primary key, session_id varchar(36) not null, provider_item_id varchar(191) not null, role varchar(20) not null, sequence_number bigint not null, lesson_revision bigint not null default 0, foreign key (session_id) references voice_tutor_sessions(id))",
        )
    }

    @Test
    fun `latest accepted learner and tutor identities are role specific within one owned call`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "USER")
        insertTurn(12, "owned-call", "TUTOR")
        insertTurn(13, "owned-call", "USER")
        insertTurn(14, "owned-call", "TUTOR")
        insertTurn(15, "owned-call", "USER")
        insertTurn(999, "owned-call", "OTHER")

        assertThat(adapter.latestLearnerTurnId(7, "owned-call")).isEqualTo(15)
        assertThat(adapter.latestTutorTurnId(7, "owned-call")).isEqualTo(14)
    }

    @Test
    fun `a larger identity in another session or account never advances this call confirmation`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertSession("other-owned-call", 7)
        insertSession("foreign-call", 99)
        insertTurn(11, "owned-call", "USER")
        insertTurn(12, "owned-call", "TUTOR")
        insertTurn(101, "other-owned-call", "USER")
        insertTurn(102, "other-owned-call", "TUTOR")
        insertTurn(201, "foreign-call", "USER")
        insertTurn(202, "foreign-call", "TUTOR")

        assertThat(adapter.latestLearnerTurnId(7, "owned-call")).isEqualTo(11)
        assertThat(adapter.latestTutorTurnId(7, "owned-call")).isEqualTo(12)
        assertThat(adapter.latestLearnerTurnId(7, "foreign-call")).isNull()
        assertThat(adapter.latestTutorTurnId(7, "foreign-call")).isNull()
        assertThat(adapter.latestLearnerTurnId(99, "owned-call")).isNull()
        assertThat(adapter.latestTutorTurnId(99, "owned-call")).isNull()
    }

    @Test
    fun `empty missing and one-sided conversations do not invent confirmation turns`(): Unit = runBlocking {
        insertSession("empty-call", 7)
        insertSession("learner-only", 7)
        insertSession("tutor-only", 7)
        insertTurn(11, "learner-only", "USER")
        insertTurn(12, "tutor-only", "TUTOR")

        for (session in listOf("empty-call", "missing-call", "owned' OR '1'='1")) {
            assertThat(adapter.latestLearnerTurnId(7, session)).isNull()
            assertThat(adapter.latestTutorTurnId(7, session)).isNull()
        }
        assertThat(adapter.latestTutorTurnId(7, "learner-only")).isNull()
        assertThat(adapter.latestLearnerTurnId(7, "tutor-only")).isNull()
    }

    @Test
    fun `ready ending completed and failed calls cannot supply a destructive confirmation`(): Unit = runBlocking {
        for ((index, status) in listOf("READY", "ENDING", "COMPLETED", "FAILED").withIndex()) {
            val session = "state-$index"
            insertSession(session, 7, status)
            insertTurn(100L + index * 2, session, "USER")
            insertTurn(101L + index * 2, session, "TUTOR")

            assertThat(adapter.latestLearnerTurnId(7, session)).describedAs(status).isNull()
            assertThat(adapter.latestTutorTurnId(7, session)).describedAs(status).isNull()
        }
    }

    @Test
    fun `ended timestamp revokes lookups even if the stored status has not caught up`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "USER")
        insertTurn(12, "owned-call", "TUTOR")
        assertThat(adapter.latestLearnerTurnId(7, "owned-call")).isEqualTo(11)
        execute("update voice_tutor_sessions set ended_at = timestamp '2026-09-01 01:00:00' where id = 'owned-call'")

        assertThat(adapter.latestLearnerTurnId(7, "owned-call")).isNull()
        assertThat(adapter.latestTutorTurnId(7, "owned-call")).isNull()
        val roleCount = database.sql("select count(*) as turn_count from voice_tutor_transcript_turns")
            .map { row, _ -> (row.get("turn_count") as Number).toInt() }.one().awaitSingle()
        assertThat(roleCount).isEqualTo(2)
    }

    @Test
    fun `exact persisted learner item collapses answer checkpoints into one completed exchange`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "TUTOR", lessonRevision = 3)
        insertTurn(12, "owned-call", "USER", lessonRevision = 3)
        insertTurn(13, "owned-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(14, "owned-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(15, "owned-call", "USER", providerItemId = "continue-item", lessonRevision = 3)

        val authorization = adapter.learnerTurnAuthorization(
            7, "owned-call", "continue-item", 3, "item-11", "answer-item", "feedback-item",
        )

        assertThat(authorization?.turnId).isEqualTo(15)
        assertThat(authorization?.completedExchange).isTrue()
        assertThat(adapter.learnerTurnAuthorization(
            7, "owned-call", "wrong-item", 3, "item-11", "answer-item", "feedback-item",
        )).isNull()
        assertThat(adapter.learnerTurnAuthorization(
            7, "owned-call", "continue-item", 2, "item-11", "answer-item", "feedback-item",
        )).isNull()
        assertThat(adapter.learnerTurnAuthorization(
            99, "owned-call", "continue-item", 3, "item-11", "answer-item", "feedback-item",
        )).isNull()
    }

    @Test
    fun `completed exchange accepts combined or separate feedback and navigation offer identities`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(31, "owned-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(32, "owned-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(33, "owned-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(34, "owned-call", "USER", providerItemId = "continue-item", lessonRevision = 3)

        suspend fun completed(answer: String?, feedback: String?): Boolean? = adapter.learnerTurnAuthorization(
            7, "owned-call", "continue-item", 3, "question-item", answer, feedback,
        )?.completedExchange

        assertThat(adapter.learnerTurnAuthorization(
            7, "owned-call", "continue-item", 3, "missing-question", "answer-item", "feedback-item",
        )?.completedExchange).isFalse()
        assertThat(completed(null, "feedback-item")).isFalse()
        assertThat(completed("answer-item", null)).isFalse()
        assertThat(completed("missing-answer", "feedback-item")).isFalse()
        assertThat(completed("answer-item", "missing-feedback")).isFalse()
        assertThat(completed("feedback-item", "answer-item")).isFalse()

        insertSession("separate-offer-call", 7)
        insertTurn(81, "separate-offer-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(82, "separate-offer-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(83, "separate-offer-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(84, "separate-offer-call", "TUTOR", providerItemId = "offer-item", lessonRevision = 3)
        insertTurn(85, "separate-offer-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "separate-offer-call", "continue-item", 3,
            "question-item", "answer-item", "feedback-item", "offer-item",
        )?.completedExchange).isTrue()

        insertSession("offer-without-feedback-call", 7)
        insertTurn(91, "offer-without-feedback-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(92, "offer-without-feedback-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(93, "offer-without-feedback-call", "TUTOR", providerItemId = "offer-item", lessonRevision = 3)
        insertTurn(94, "offer-without-feedback-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "offer-without-feedback-call", "continue-item", 3,
            "question-item", "answer-item", null, "offer-item",
        )?.completedExchange).isFalse()

        insertSession("intervening-tutor-call", 7)
        insertTurn(101, "intervening-tutor-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(102, "intervening-tutor-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(103, "intervening-tutor-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(104, "intervening-tutor-call", "TUTOR", providerItemId = "unanswered-tutor-item", lessonRevision = 3)
        insertTurn(105, "intervening-tutor-call", "TUTOR", providerItemId = "offer-item", lessonRevision = 3)
        insertTurn(106, "intervening-tutor-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "intervening-tutor-call", "continue-item", 3,
            "question-item", "answer-item", "feedback-item", "offer-item",
        )?.completedExchange).isFalse()

        insertSession("other-revision-call", 7)
        insertTurn(41, "other-revision-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(42, "other-revision-call", "USER", providerItemId = "cross-answer", lessonRevision = 2)
        insertTurn(43, "other-revision-call", "TUTOR", providerItemId = "cross-feedback", lessonRevision = 2)
        insertTurn(44, "other-revision-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "other-revision-call", "continue-item", 3, "question-item", "cross-answer", "cross-feedback",
        )?.completedExchange).isFalse()

        insertSession("wrong-order-call", 7)
        insertTurn(51, "wrong-order-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(52, "wrong-order-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(53, "wrong-order-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(54, "wrong-order-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "wrong-order-call", "continue-item", 3, "question-item", "answer-item", "feedback-item",
        )?.completedExchange).isFalse()

        insertSession("superseded-feedback-call", 7)
        insertTurn(61, "superseded-feedback-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(62, "superseded-feedback-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(63, "superseded-feedback-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(64, "superseded-feedback-call", "TUTOR", providerItemId = "newer-tutor-item", lessonRevision = 3)
        insertTurn(65, "superseded-feedback-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "superseded-feedback-call", "continue-item", 3, "question-item", "answer-item", "feedback-item",
        )?.completedExchange).isFalse()

        insertSession("superseded-answer-call", 7)
        insertTurn(71, "superseded-answer-call", "TUTOR", providerItemId = "question-item", lessonRevision = 3)
        insertTurn(72, "superseded-answer-call", "USER", providerItemId = "answer-item", lessonRevision = 3)
        insertTurn(73, "superseded-answer-call", "USER", providerItemId = "newer-answer-item", lessonRevision = 3)
        insertTurn(74, "superseded-answer-call", "TUTOR", providerItemId = "feedback-item", lessonRevision = 3)
        insertTurn(75, "superseded-answer-call", "USER", providerItemId = "continue-item", lessonRevision = 3)
        assertThat(adapter.learnerTurnAuthorization(
            7, "superseded-answer-call", "continue-item", 3, "question-item", "answer-item", "feedback-item",
        )?.completedExchange).isFalse()
    }

    @Test
    fun `exact choice remains usable while an incomplete exchange cannot authorize guided descent`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(21, "owned-call", "TUTOR", lessonRevision = 1)
        insertTurn(22, "owned-call", "USER", providerItemId = "choice-item", lessonRevision = 1)

        val choice = adapter.learnerTurnAuthorization(7, "owned-call", "choice-item", 1, null, null, null)

        assertThat(choice?.turnId).isEqualTo(22)
        assertThat(choice?.completedExchange).isFalse()
        insertTurn(23, "owned-call", "TUTOR", lessonRevision = 1)
        assertThat(adapter.learnerTurnAuthorization(7, "owned-call", "choice-item", 1, null, null, null)).isNull()
    }

    @Test
    fun `native current learner survives tool preamble but not a newer learner in another epoch`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "USER", providerItemId = "choice", lessonRevision = 2)
        insertTurn(12, "owned-call", "TUTOR", providerItemId = "tool-preamble", lessonRevision = 3)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "choice", 2)).isEqualTo(11)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "choice", 3)).isNull()
        assertThat(adapter.persistedLearnerTurnId(99, "owned-call", "choice", 2)).isNull()
        insertTurn(13, "owned-call", "USER", providerItemId = "newer-choice", lessonRevision = 3)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "choice", 2)).isNull()
    }

    @Test
    fun `native exact question and yes remain valid after tutor preamble while legacy gate stays strict`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "TUTOR", providerItemId = "confirmation-question", lessonRevision = 2)
        insertTurn(12, "owned-call", "USER", providerItemId = "natural-yes", lessonRevision = 2)
        insertTurn(13, "owned-call", "TUTOR", providerItemId = "execution-preamble", lessonRevision = 2)
        val boundary = adapter.persistedDialogueBoundary(7, "owned-call", "natural-yes", "confirmation-question", 2)
        assertThat(boundary?.learnerTurnId).isEqualTo(12)
        assertThat(boundary?.tutorTurnId).isEqualTo(11)
        assertThat(adapter.learnerTurnAuthorization(7, "owned-call", "natural-yes", 2, null, null, null)).isNull()
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "natural-yes", "execution-preamble", 2)).isNull()
        assertThat(adapter.persistedDialogueBoundary(99, "owned-call", "natural-yes", "confirmation-question", 2)).isNull()
        insertTurn(14, "owned-call", "USER", providerItemId = "newer-reply", lessonRevision = 3)
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "natural-yes", "confirmation-question", 2)).isNull()
    }

    @Test
    fun `native confirmation rejects an intervening tutor question and ended session`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(11, "owned-call", "TUTOR", providerItemId = "old-question")
        insertTurn(12, "owned-call", "TUTOR", providerItemId = "current-question")
        insertTurn(13, "owned-call", "USER", providerItemId = "reply")
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "reply", "old-question", 0)).isNull()
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "reply", "current-question", 0)).isNotNull()
        execute("update voice_tutor_sessions set status = 'COMPLETED' where id = 'owned-call'")
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "reply", "current-question", 0)).isNull()
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "reply", 0)).isNull()
    }

    @Test
    fun `native freshness follows spoken sequence when older ASR persists with a larger row id`(): Unit = runBlocking {
        insertSession("owned-call", 7)
        insertTurn(30, "owned-call", "TUTOR", providerItemId = "question", sequenceNumber = 3)
        insertTurn(20, "owned-call", "USER", providerItemId = "yes", sequenceNumber = 4)
        insertTurn(40, "owned-call", "TUTOR", providerItemId = "preamble", sequenceNumber = 5)
        // Old source speech completed ASR last: its row ID is larger but its spoken sequence is older.
        insertTurn(100, "owned-call", "USER", providerItemId = "late-old-asr", sequenceNumber = 1)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "yes", 0)).isEqualTo(20)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "late-old-asr", 0)).isNull()
        val boundary = adapter.persistedDialogueBoundary(7, "owned-call", "yes", "question", 0)
        assertThat(boundary?.learnerTurnId).isEqualTo(20)
        assertThat(boundary?.tutorTurnId).isEqualTo(30)
        insertTurn(10, "owned-call", "USER", providerItemId = "newer-user", sequenceNumber = 6)
        assertThat(adapter.persistedLearnerTurnId(7, "owned-call", "yes", 0)).isNull()
        assertThat(adapter.persistedDialogueBoundary(7, "owned-call", "yes", "question", 0)).isNull()
    }

    private suspend fun insertSession(id: String, userId: Long, status: String = "ACTIVE"): Unit {
        database.sql("insert into voice_tutor_sessions(id, user_id, status) values (:id, :userId, :status)")
            .bind("id", id).bind("userId", userId).bind("status", status).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertTurn(
        id: Long,
        sessionId: String,
        role: String,
        providerItemId: String = "item-$id",
        lessonRevision: Long = 0,
        sequenceNumber: Long = id,
    ): Unit {
        database.sql(
            "insert into voice_tutor_transcript_turns(id, session_id, provider_item_id, role, sequence_number, lesson_revision) values (:id, :sessionId, :providerItemId, :role, :sequenceNumber, :lessonRevision)",
        ).bind("id", id).bind("sessionId", sessionId).bind("providerItemId", providerItemId)
            .bind("role", role).bind("sequenceNumber", sequenceNumber).bind("lessonRevision", lessonRevision)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String): Unit {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }
}
