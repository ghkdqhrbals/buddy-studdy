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
 * confirmation lookups must neither load nor retain private speech. The IDs
 * prove persistence order only, not when speech began or what it consented to.
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
            "create table voice_tutor_transcript_turns (id bigint primary key, session_id varchar(36) not null, role varchar(20) not null, foreign key (session_id) references voice_tutor_sessions(id))",
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

    private suspend fun insertSession(id: String, userId: Long, status: String = "ACTIVE"): Unit {
        database.sql("insert into voice_tutor_sessions(id, user_id, status) values (:id, :userId, :status)")
            .bind("id", id).bind("userId", userId).bind("status", status).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertTurn(id: Long, sessionId: String, role: String): Unit {
        database.sql("insert into voice_tutor_transcript_turns(id, session_id, role) values (:id, :sessionId, :role)")
            .bind("id", id).bind("sessionId", sessionId).bind("role", role).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String): Unit {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }
}
