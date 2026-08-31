package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Isolated in-memory SQL contracts only. The fixture mirrors V102's snapshot
 * key/check/cascade constraints; it does not execute the MySQL Flyway migration
 * or claim H2 row locking proves MySQL production behavior. No Docker, existing
 * database, real account, provider, question table, or quota table is used.
 */
@Timeout(15)
class VoiceTutorStudyContextAdapterTest {
    private val now = Instant.parse("2026-08-31T12:00:00Z")
    private val connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-study-context-${UUID.randomUUID()};" +
            "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    )
    private val database = DatabaseClient.create(connectionFactory)
    private val transactions = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))
    private val adapter = VoiceTutorStudyContextAdapter(database, Clock.fixed(now, ZoneOffset.UTC))

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        execute(
            """
            create table studies (
                id bigint primary key,
                user_id bigint not null,
                parent_study_id bigint,
                topic varchar(255) not null,
                difficulty_level int not null,
                sort_order int not null default 0
            )
            """.trimIndent(),
        )
        execute("create index idx_studies_user_parent_order on studies(user_id, parent_study_id, sort_order, id)")
        execute(
            """
            create table voice_tutor_sessions (
                id varchar(36) primary key,
                user_id bigint not null,
                study_id bigint,
                topic_snapshot varchar(255) not null,
                difficulty_snapshot int not null,
                status varchar(20) not null,
                hard_ends_at timestamp(6) not null,
                ended_at timestamp(6)
            )
            """.trimIndent(),
        )
        execute(
            """
            create table voice_tutor_study_snapshots (
                session_id varchar(36) not null,
                study_id bigint not null,
                parent_study_id bigint,
                topic varchar(255) not null,
                difficulty int not null,
                captured_at timestamp(6) not null,
                primary key (session_id, study_id),
                constraint fk_voice_tutor_study_snapshot_session foreign key (session_id)
                    references voice_tutor_sessions(id) on delete cascade,
                constraint chk_voice_tutor_study_snapshot_id check (study_id > 0),
                constraint chk_voice_tutor_study_snapshot_level check (difficulty between 1 and 10)
            )
            """.trimIndent(),
        )
    }

    @Test
    fun `prepare copies accepted selected metadata and selects only the first ten owned direct children`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, parentId = 1, topic = "Later root title", difficulty = 9)
        // Reverse sort order proves selection uses sort_order, not just node id.
        for (id in 11L..22L) insertStudy(id, parentId = 10, sortOrder = (23 - id).toInt())
        insertStudy(30, userId = 99, parentId = 10, sortOrder = -100)
        insertStudy(31, parentId = 11, sortOrder = -100)
        insertStudy(32, parentId = 1, sortOrder = -100)

        val snapshots = transaction {
            adapter.prepare(accepted.copy(studyId = 999, topic = "Untrusted argument", difficulty = 1))
        }

        assertThat(snapshots).hasSize(11)
        assertThat(snapshots.single { it.studyId == 10L })
            .isEqualTo(VoiceTutorStudySnapshot(10, 1, "Accepted Redis", 6))
        // list() orders equal capture timestamps by identity. The selected set
        // must nevertheless be the ten lowest sort_order children (13..22).
        assertThat(snapshots.map { it.studyId }).containsExactlyElementsOf(listOf(10L) + (13L..22L))
        assertThat(snapshots.map { it.studyId }).doesNotContain(11L, 12L, 30L, 31L, 32L, 999L)
    }

    @Test
    fun `prepare without a selected study does not manufacture a root or snapshots`(): Unit = runBlocking {
        val accepted = session(studyId = null)
        insertSession(accepted)
        insertStudy(10)
        assertThat(transaction { adapter.prepare(accepted) }).isEmpty()
        assertThat(snapshotCount()).isZero()
    }

    @Test
    fun `prepare and remember reject a foreign session while reads remain owner scoped`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10)
        val own = transaction { adapter.prepare(accepted) }
        assertThat(own).hasSize(1)

        assertThat(transaction { adapter.prepare(accepted.copy(userId = 99)) }).isEmpty()
        assertThat(transaction { adapter.remember(99, accepted.id, listOf(10)) }).isEmpty()
        assertThat(adapter.list(99, accepted.id)).isEmpty()
        assertThat(adapter.list(7, "missing-session")).isEmpty()
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `remember ignores foreign missing invalid and duplicate nodes without learning their data`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, topic = "Owned child", parentId = 10)
        insertStudy(12, userId = 99, topic = "Foreign child", parentId = 10)

        val remembered = transaction {
            adapter.remember(7, accepted.id, listOf(-1, 0, 12, 11, 11, 999))
        }

        assertThat(remembered).containsExactly(VoiceTutorStudySnapshot(11, 10, "Owned child", 5))
        assertThat(adapter.list(7, accepted.id)).isEqualTo(remembered)
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `inactive expired and explicitly ended sessions cannot append snapshots`(): Unit = runBlocking {
        insertStudy(10)
        val blocked = listOf(
            session(id = "ready", status = VoiceTutorSessionStatus.READY),
            session(id = "ending", status = VoiceTutorSessionStatus.ENDING),
            session(id = "completed", status = VoiceTutorSessionStatus.COMPLETED, endedAt = now),
            session(id = "failed", status = VoiceTutorSessionStatus.FAILED, endedAt = now),
            session(id = "expired", hardEndsAt = now.minusSeconds(1)),
            session(id = "at-boundary", hardEndsAt = now),
            // A legacy/inconsistent ACTIVE row must not override explicit end.
            session(id = "ended-active", endedAt = now),
        )
        for (accepted in blocked) {
            insertSession(accepted)
            assertThat(transaction { adapter.prepare(accepted) }).describedAs(accepted.id).isEmpty()
            assertThat(transaction { adapter.remember(7, accepted.id, listOf(10)) })
                .describedAs(accepted.id).isEmpty()
        }
        assertThat(snapshotCount()).isZero()
    }

    @Test
    fun `first captured child metadata survives rename level reparent deletion and call end`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, parentId = 10, topic = "First child", difficulty = 3)
        val original = transaction { adapter.remember(7, accepted.id, listOf(11)) }

        execute("update studies set topic = 'Renamed', difficulty_level = 9, parent_study_id = 88 where id = 11")
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11)) }).isEqualTo(original)
        execute("delete from studies where id = 11")
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11)) }).isEqualTo(original)
        database.sql("update voice_tutor_sessions set status = 'COMPLETED', ended_at = :now where id = :id")
            .bind("now", now.utc()).bind("id", accepted.id).fetch().rowsUpdated().awaitSingle()

        assertThat(original).containsExactly(VoiceTutorStudySnapshot(11, 10, "First child", 3))
        assertThat(adapter.list(7, accepted.id)).isEqualTo(original)
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11)) }).isEmpty()
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `selected snapshot retains original parent and accepted metadata when its live node changes or disappears`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, parentId = 1, topic = "Live title", difficulty = 9)
        val original = transaction { adapter.prepare(accepted) }
        execute("update studies set topic = 'Changed', difficulty_level = 2, parent_study_id = 88 where id = 10")
        assertThat(transaction { adapter.prepare(accepted) }).isEqualTo(original)
        execute("delete from studies where id = 10")
        assertThat(transaction { adapter.prepare(accepted) }).isEqualTo(original)
        assertThat(original).containsExactly(VoiceTutorStudySnapshot(10, 1, "Accepted Redis", 6))
    }

    @Test
    fun `one capture admits thirty two unique positive ids and the whole call never exceeds sixty four`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        for (id in 100L..180L) insertStudy(id)
        val firstRequest = listOf(-1L, 0L, 100L, 100L) + (101L..180L)
        val first = transaction { adapter.remember(7, accepted.id, firstRequest) }
        assertThat(first.map { it.studyId }).containsExactlyElementsOf((100L..131L).toList())
        assertThat(snapshotCount()).isEqualTo(32)
        assertThat(transaction { adapter.remember(7, accepted.id, firstRequest) }).isEqualTo(first)
        assertThat(snapshotCount()).isEqualTo(32)

        val second = transaction { adapter.remember(7, accepted.id, (132L..180L).toList()) }
        assertThat(second.map { it.studyId }).containsExactlyElementsOf((132L..163L).toList())
        assertThat(transaction { adapter.remember(7, accepted.id, (164L..180L).toList()) }).isEmpty()
        assertThat(adapter.list(7, accepted.id).map { it.studyId }).containsExactlyElementsOf((100L..163L).toList())
        assertThat(snapshotCount()).isEqualTo(64)
    }

    @Test
    fun `empty capture and unusable node metadata do not consume snapshot capacity`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, difficulty = 0)
        insertStudy(12, difficulty = 11)
        insertStudy(13, topic = "   ")
        assertThat(transaction { adapter.remember(7, accepted.id, emptyList()) }).isEmpty()
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11, 12, 13)) }).isEmpty()
        assertThat(snapshotCount()).isZero()
    }

    @Test
    fun `deleting one session cascades only its snapshots and leaves another private lesson intact`(): Unit = runBlocking {
        val first = session(id = "first")
        val second = session(id = "second")
        insertSession(first)
        insertSession(second)
        insertStudy(10)
        transaction { adapter.prepare(first) }
        val other = transaction { adapter.prepare(second) }
        assertThat(snapshotCount()).isEqualTo(2)
        database.sql("delete from voice_tutor_sessions where id = :id").bind("id", first.id)
            .fetch().rowsUpdated().awaitSingle()
        assertThat(adapter.list(7, first.id)).isEmpty()
        assertThat(adapter.list(7, second.id)).isEqualTo(other)
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `two transactional writers serialize on the session lock and cannot exceed the shared limit`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        for (id in 100L..164L) insertStudy(id)
        transaction { adapter.remember(7, accepted.id, (100L..131L).toList()) }
        transaction { adapter.remember(7, accepted.id, (132L..162L).toList()) }
        assertThat(snapshotCount()).isEqualTo(63)

        val firstInserted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEnteredTransaction = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            transaction {
                val captured = adapter.remember(7, accepted.id, listOf(163))
                firstInserted.complete(Unit)
                releaseFirst.await()
                captured
            }
        }
        try {
            withTimeout(5_000) { firstInserted.await() }
            val second = async(Dispatchers.Default) {
                transaction {
                    secondEnteredTransaction.complete(Unit)
                    adapter.remember(7, accepted.id, listOf(164))
                }
            }
            withTimeout(5_000) { secondEnteredTransaction.await() }
            // Bounded evidence that the second transaction cannot finish while
            // the first still owns the SELECT ... FOR UPDATE lock.
            assertThat(withTimeoutOrNull(100) { second.await() }).isNull()
            releaseFirst.complete(Unit)
            assertThat(withTimeout(5_000) { first.await() }.map { it.studyId }).containsExactly(163L)
            assertThat(withTimeout(5_000) { second.await() }).isEmpty()
        } finally {
            releaseFirst.complete(Unit)
        }
        assertThat(snapshotCount()).isEqualTo(64)
        assertThat(adapter.list(7, accepted.id).map { it.studyId }).contains(163L).doesNotContain(164L)
    }

    @Test
    fun `production write entrypoints declare a transaction for the shared session lock`() {
        for (name in listOf("prepare", "remember")) {
            val method = VoiceTutorStudyContextAdapter::class.java.declaredMethods.single { it.name == name }
            assertThat(method.getAnnotation(Transactional::class.java)).describedAs(name).isNotNull()
        }
    }

    private suspend fun <T : Any> transaction(block: suspend () -> T): T =
        requireNotNull(transactions.executeAndAwait { block() })

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private suspend fun snapshotCount(): Long = database.sql("select count(*) as count from voice_tutor_study_snapshots")
        .map { row, _ -> (row.get("count") as Number).toLong() }.one().awaitSingle()

    private suspend fun insertStudy(
        id: Long,
        userId: Long = 7,
        parentId: Long? = null,
        topic: String = "Synthetic topic $id",
        difficulty: Int = 5,
        sortOrder: Int = 0,
    ) {
        var insert = database.sql(
            """
            insert into studies(id, user_id, parent_study_id, topic, difficulty_level, sort_order)
            values (:id, :userId, :parentId, :topic, :difficulty, :sortOrder)
            """.trimIndent(),
        ).bind("id", id).bind("userId", userId).bind("topic", topic)
            .bind("difficulty", difficulty).bind("sortOrder", sortOrder)
        insert = parentId?.let { insert.bind("parentId", it) }
            ?: insert.bindNull("parentId", java.lang.Long::class.java)
        insert.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertSession(session: VoiceTutorSession) {
        var insert = database.sql(
            """
            insert into voice_tutor_sessions
                (id, user_id, study_id, topic_snapshot, difficulty_snapshot, status, hard_ends_at, ended_at)
            values (:id, :userId, :studyId, :topic, :difficulty, :status, :hardEndsAt, :endedAt)
            """.trimIndent(),
        ).bind("id", session.id).bind("userId", session.userId).bind("topic", session.topic)
            .bind("difficulty", session.difficulty).bind("status", session.status.name)
            .bind("hardEndsAt", session.hardEndsAt.utc())
        insert = session.studyId?.let { insert.bind("studyId", it) }
            ?: insert.bindNull("studyId", java.lang.Long::class.java)
        insert = session.endedAt?.let { insert.bind("endedAt", it.utc()) }
            ?: insert.bindNull("endedAt", LocalDateTime::class.java)
        insert.fetch().rowsUpdated().awaitSingle()
    }

    private fun session(
        id: String = "synthetic-study-snapshot",
        userId: Long = 7,
        studyId: Long? = 10,
        status: VoiceTutorSessionStatus = VoiceTutorSessionStatus.ACTIVE,
        hardEndsAt: Instant = now.plusSeconds(60),
        endedAt: Instant? = null,
    ) = VoiceTutorSession(
        id = id, userId = userId, studyId = studyId, idempotencyKey = "synthetic-idempotency-$id",
        providerSessionId = null, status = status, resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko", model = "synthetic-realtime", voice = "synthetic-voice",
        topic = "Accepted Redis", difficulty = 6,
        periodStartedAt = now.minusSeconds(60), periodEndsAt = now.plusSeconds(3_600),
        reservedSeconds = 60, chargedSeconds = 0, maxSessionSeconds = 60,
        hardEndsAt = hardEndsAt, connectedAt = now, relayHeartbeatAt = now, acceptedAudioBytes = 0,
        endedAt = endedAt, finalizedAt = null, endReason = null, failureCode = null, failureMessage = null,
        createdAt = now, updatedAt = now,
    )
}
