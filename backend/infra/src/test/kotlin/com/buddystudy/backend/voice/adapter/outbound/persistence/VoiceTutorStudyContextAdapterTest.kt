package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.model.VoiceTutorLessonTreeContext
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorTranscriptSource
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
import java.time.ZoneId
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
            create table user_devices (
                id bigint primary key,
                user_id bigint not null,
                device_id varchar(191) not null,
                session_expires_at timestamp(6),
                logged_out_at timestamp(6),
                revoked_at timestamp(6)
            )
            """.trimIndent(),
        )
        execute("insert into user_devices(id, user_id, device_id) values (11, 7, 'synthetic-device')")
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
                accepted_study_id bigint,
                topic_snapshot varchar(255) not null,
                difficulty_snapshot int not null,
                provider_session_id varchar(191),
                status varchar(20) not null,
                hard_ends_at timestamp(6) not null,
                ended_at timestamp(6),
                updated_at timestamp(6) not null
            )
            """.trimIndent(),
        )
        execute(
            """
            create table voice_tutor_lesson_focuses (
                session_id varchar(36) not null, revision bigint not null,
                study_id bigint not null, learner_turn_id bigint, captured_at timestamp(6) not null,
                primary key (session_id, revision),
                unique (session_id, learner_turn_id),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (revision >= 0), check (study_id > 0),
                check (learner_turn_id is null or learner_turn_id > 0)
            )
            """.trimIndent(),
        )
        execute(
            """
            create table voice_tutor_transcript_turns (
                id bigint primary key,
                session_id varchar(36) not null,
                provider_item_id varchar(191) not null,
                role varchar(16) not null,
                sequence_number bigint not null,
                unique (session_id, provider_item_id, role),
                unique (session_id, sequence_number),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade
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
        execute(
            """
            create table voice_tutor_study_revisions (
                session_id varchar(36) not null,
                revision bigint not null,
                study_id bigint not null,
                parent_study_id bigint,
                topic varchar(255) not null,
                difficulty int not null,
                captured_at timestamp(6) not null,
                primary key (session_id, revision),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (revision > 0), check (study_id > 0), check (difficulty between 1 and 10)
            )
            """.trimIndent(),
        )
    }

    @Test
    fun `initial mutation snapshot is a complete deterministic owner scoped set of at most seventeen nodes`(): Unit =
        runBlocking {
            val fresh = session(id = "fresh-owner-snapshot", studyId = null).copy(topic = "", difficulty = 0)
            insertSession(fresh)
            for (id in 17L downTo 1L) {
                insertStudy(
                    id = id,
                    parentId = if (id == 1L) null else 1L,
                    topic = "Owned $id",
                    difficulty = ((id - 1) % 10 + 1).toInt(),
                )
            }
            insertStudy(100, userId = 99, topic = "Foreign root", difficulty = 9)

            val snapshot = adapter.initialMutationSnapshot(
                userId = 7,
                sessionId = fresh.id,
                maxCandidates = VoiceTutorInitialStudyMutationSnapshot.MAX_CANDIDATES,
            )

            assertThat(snapshot?.candidates?.map { it.studyId }).containsExactlyElementsOf(1L..17L)
            assertThat(snapshot?.candidates).allSatisfy { candidate ->
                assertThat(candidate.topic).startsWith("Owned ")
                assertThat(candidate.difficulty).isBetween(1, 10)
            }
            assertThat(snapshot?.isValid()).isTrue()
            assertThat(adapter.initialMutationSnapshot(99, fresh.id)).isNull()
        }

    @Test
    fun `initial mutation snapshot fails closed instead of returning a partial eighteenth node window`(): Unit =
        runBlocking {
            val fresh = session(id = "overflow-owner-snapshot", studyId = null).copy(topic = "", difficulty = 0)
            insertSession(fresh)
            for (id in 1L..18L) insertStudy(id, topic = "Owned $id")

            assertThat(adapter.initialMutationSnapshot(7, fresh.id)).isNull()
        }

    @Test
    fun `initial mutation snapshot is unavailable after the first persisted USER turn but not a tutor turn`(): Unit =
        runBlocking {
            val fresh = session(id = "resumed-owner-snapshot", studyId = null).copy(topic = "", difficulty = 0)
            insertSession(fresh)
            insertStudy(1, topic = "Redis")
            insertTranscriptTurn(11, fresh.id, role = "TUTOR")

            assertThat(adapter.initialMutationSnapshot(7, fresh.id)?.candidates?.map { it.studyId })
                .containsExactly(1L)

            insertTranscriptTurn(12, fresh.id, role = "USER")

            assertThat(adapter.initialMutationSnapshot(7, fresh.id)).isNull()
        }

    @Test
    fun `initial mutation snapshot fails closed for malformed rows and duplicate identities are invalid evidence`(): Unit =
        runBlocking {
            val malformed = session(id = "malformed-owner-snapshot", studyId = null).copy(topic = "", difficulty = 0)
            insertSession(malformed)
            insertStudy(1, topic = "Redis", difficulty = 5)
            insertStudy(2, topic = "", difficulty = 5)
            insertStudy(3, parentId = 3, topic = "Self parent", difficulty = 11)

            assertThat(adapter.initialMutationSnapshot(7, malformed.id)).isNull()

            // The SQL primary key prevents duplicate rows at source; the transport model still
            // rejects a duplicated identity if an alternate adapter ever violates that contract.
            val duplicate = VoiceTutorInitialStudyMutationSnapshot(
                listOf(
                    VoiceTutorStudyTargetCandidate(1, null, "Redis", 5),
                    VoiceTutorStudyTargetCandidate(1, null, "Redis copy", 5),
                ),
            )
            assertThat(duplicate.isValid()).isFalse()
            val ambiguousName = VoiceTutorInitialStudyMutationSnapshot(
                listOf(
                    VoiceTutorStudyTargetCandidate(1, null, "Redis", 5),
                    VoiceTutorStudyTargetCandidate(2, null, "redis", 7),
                ),
            )
            assertThat(ambiguousName.isValid()).isFalse()
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
    fun `prepare adds the owned parent path without collecting siblings or recursively expanding children`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(1, topic = "Systems", difficulty = 9)
        insertStudy(5, parentId = 1, topic = "Databases", difficulty = 2)
        insertStudy(10, parentId = 5, topic = "Live Redis", difficulty = 8)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 3)
        insertStudy(6, parentId = 1, topic = "Sibling branch")
        insertStudy(12, parentId = 11, topic = "Unopened grandchild")
        insertStudy(99, topic = "Other root")

        val saved = transaction { adapter.prepare(accepted) }

        assertThat(saved.map { it.studyId }).containsExactly(1, 5, 10, 11)
        assertThat(saved.single { it.studyId == 10L }).isEqualTo(VoiceTutorStudySnapshot(10, 5, "Accepted Redis", 6))
        assertThat(saved.single { it.studyId == 5L }.difficulty).isEqualTo(2)
        assertThat(saved.single { it.studyId == 11L }.difficulty).isEqualTo(3)
        val tree = VoiceTutorLessonTreeContext.metadata(10, saved)
        assertThat(tree["selectedPathStudyIds"]).isEqualTo(listOf(1L, 5L, 10L))
        assertThat(tree["selectedPathComplete"]).isEqualTo(true)
    }

    @Test
    fun `a selected node missing owned evidence is not fabricated as a root even when its id has children`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        assertThat(transaction { adapter.prepare(accepted) }).isEmpty()
        insertStudy(10, userId = 99, topic = "Foreign node")
        insertStudy(11, parentId = 10, topic = "Inconsistent child")
        assertThat(transaction { adapter.prepare(accepted) }).isEmpty()
        assertThat(snapshotCount()).isZero()
    }

    @Test
    fun `foreign or missing parent paths remain incomplete without inferring a same named root`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, parentId = 1)
        insertStudy(11, parentId = 10)
        insertStudy(1, userId = 99, topic = "Systems")
        insertStudy(2, topic = "Systems")
        val saved = transaction { adapter.prepare(accepted) }
        assertThat(saved.map { it.studyId }).containsExactly(10, 11)
        val tree = VoiceTutorLessonTreeContext.metadata(10, saved)
        assertThat(tree["selectedRootStudyId"]).isNull()
        assertThat(tree["selectedPathComplete"]).isEqualTo(false)
        assertThat(tree["selectedPathStudyIds"]).isEqualTo(listOf(10L))
    }

    @Test
    fun `remember captures an exact owned ancestry but returns only the requested immutable nodes`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(1)
        insertStudy(10, parentId = 1)
        insertStudy(11, parentId = 10, difficulty = 3)
        insertStudy(12, parentId = 11, difficulty = 2)
        insertStudy(13, parentId = 10)

        val captured = transaction { adapter.remember(7, accepted.id, listOf(12)) }
        assertThat(captured).containsExactly(VoiceTutorStudySnapshot(12, 11, "Synthetic topic 12", 2))
        assertThat(adapter.list(7, accepted.id).map { it.studyId }).containsExactly(1, 10, 11, 12)
        insertStudy(88)
        execute("update studies set parent_study_id = 88, difficulty_level = 9 where id in (11, 12)")
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(12)) }).isEqualTo(captured)
        assertThat(adapter.list(7, accepted.id).map { it.studyId }).containsExactly(1, 10, 11, 12)
        assertThat(adapter.list(7, accepted.id).single { it.studyId == 11L }.parentStudyId).isEqualTo(10)
    }

    @Test
    fun `actual requested nodes take the final capacity before their shared parent metadata`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        for (id in 100L..161L) insertStudy(id)
        transaction { adapter.remember(7, accepted.id, (100L..131L).toList()) }
        transaction { adapter.remember(7, accepted.id, (132L..161L).toList()) }
        insertStudy(1)
        insertStudy(200, parentId = 1)
        insertStudy(201, parentId = 1)

        val captured = transaction { adapter.remember(7, accepted.id, listOf(200, 201)) }
        assertThat(captured.map { it.studyId }).containsExactly(200, 201)
        val saved = adapter.list(7, accepted.id)
        assertThat(saved).hasSize(64)
        assertThat(saved.map { it.studyId }).doesNotContain(1L)
        assertThat(VoiceTutorLessonTreeContext.metadata(200, saved)["selectedPathComplete"]).isEqualTo(false)
    }

    @Test
    fun `selected node and immediate child take the final capacity before preparing ancestors`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        for (id in 100L..161L) insertStudy(id)
        transaction { adapter.remember(7, accepted.id, (100L..131L).toList()) }
        transaction { adapter.remember(7, accepted.id, (132L..161L).toList()) }
        insertStudy(1)
        insertStudy(10, parentId = 1)
        insertStudy(11, parentId = 10)

        val saved = transaction { adapter.prepare(accepted) }
        assertThat(saved).hasSize(64)
        assertThat(saved.map { it.studyId }).contains(10L, 11L).doesNotContain(1L)
        assertThat(saved.single { it.studyId == 10L }.difficulty).isEqualTo(6)
    }

    @Test
    fun `ancestry stops at thirty two edges and leaves overlong or cyclic paths explicit`(): Unit = runBlocking {
        val accepted = session(studyId = 139)
        insertSession(accepted)
        for (id in 100L..139L) insertStudy(id, parentId = if (id == 100L) null else id - 1)
        val saved = transaction { adapter.prepare(accepted) }
        assertThat(saved.map { it.studyId }).containsExactlyElementsOf((107L..139L).toList())
        assertThat(VoiceTutorLessonTreeContext.metadata(139, saved)["selectedPathComplete"]).isEqualTo(false)
        assertThat(VoiceTutorLessonTreeContext.metadata(139, saved)["selectedRootStudyId"]).isNull()

        val cyclicSession = session(id = "cyclic-study-context")
        insertSession(cyclicSession)
        insertStudy(10, parentId = 11)
        insertStudy(11, parentId = 10)
        val cyclic = transaction { adapter.prepare(cyclicSession) }
        assertThat(cyclic.map { it.studyId }).containsExactly(10, 11)
        assertThat(VoiceTutorLessonTreeContext.metadata(10, cyclic)["selectedPathComplete"]).isEqualTo(false)
    }

    @Test
    fun `a lease expiring during metadata reads cannot append selected children or ancestor snapshots`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(1)
        insertStudy(10, parentId = 1)
        insertStudy(11, parentId = 10)
        var readings = 0
        val expiringClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (readings++ == 0) now else accepted.hardEndsAt
        }
        val expiring = VoiceTutorStudyContextAdapter(database, expiringClock)
        assertThat(transaction { expiring.prepare(accepted) }).isEmpty()
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
        for (name in listOf("prepare", "remember", "revise")) {
            val method = VoiceTutorStudyContextAdapter::class.java.declaredMethods.single { it.name == name }
            assertThat(method.getAnnotation(Transactional::class.java)).describedAs(name).isNotNull()
        }
    }

    @Test
    fun `explicit revision appends owned metadata and ordinary reads return its current view without rewriting baseline`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Accepted Redis", difficulty = 6)
        insertStudy(11, parentId = 10, topic = "First child", difficulty = 3)
        val original = transaction { adapter.prepare(accepted) }
        execute("update studies set topic = 'Renamed child', difficulty_level = 8 where id = 11")

        val current = transaction { adapter.revise(7, accepted.id, 11) }

        val revised = VoiceTutorStudySnapshot(11, 10, "Renamed child", 8, revision = 1)
        assertThat(current.single { it.studyId == 11L }).isEqualTo(revised)
        assertThat(adapter.list(7, accepted.id)).containsExactlyElementsOf(original + revised)
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11)) }).containsExactly(revised)
        assertThat(transaction { adapter.prepare(accepted) }).isEqualTo(current)
        assertThat(adapter.currentRevision(7, accepted.id)).isEqualTo(1)
        assertThat(adapter.currentRevision(99, accepted.id)).isZero()
        assertThat(adapter.currentRevision(7, "missing")).isZero()
        assertThat(snapshotCount()).isEqualTo(2)
        assertThat(revisionCount()).isEqualTo(1)
    }

    @Test
    fun `replayed unchanged revision is a no-op and later external changes are not adopted by remember`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, topic = "Original child", difficulty = 3)
        transaction { adapter.remember(7, accepted.id, listOf(11)) }
        assertThat(transaction { adapter.revise(7, accepted.id, 11) }.single().revision).isZero()
        execute("update studies set difficulty_level = 8 where id = 11")
        val first = transaction { adapter.revise(7, accepted.id, 11) }
        assertThat(transaction { adapter.revise(7, accepted.id, 11) }).isEqualTo(first)
        execute("update studies set difficulty_level = 9 where id = 11")
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(11)) }).isEqualTo(first)
        assertThat(revisionCount()).isEqualTo(1)
        assertThat(transaction { adapter.revise(7, accepted.id, 11) }.single().revision).isEqualTo(2)
    }

    @Test
    fun `revision requires prior baseline and an active owned live node without fabricating first seen history`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11)
        insertStudy(12, userId = 99)
        for (id in listOf(11L, 12L, 999L)) {
            assertThat(runCatching { transaction { adapter.revise(7, accepted.id, id) } }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(snapshotCount()).isZero()
        transaction { adapter.remember(7, accepted.id, listOf(11)) }
        assertThat(runCatching { transaction { adapter.revise(99, accepted.id, 11) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        execute("update studies set user_id = 99 where id = 11")
        assertThat(runCatching { transaction { adapter.revise(7, accepted.id, 11) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        execute("delete from studies where id = 11")
        assertThat(runCatching { transaction { adapter.revise(7, accepted.id, 11) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        execute("update voice_tutor_sessions set status = 'ENDING'")
        assertThat(runCatching { transaction { adapter.revise(7, accepted.id, 11) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(revisionCount()).isZero()
        assertThat(adapter.list(7, accepted.id)).hasSize(1)
    }

    @Test
    fun `all sixty four baselines and thirty two changes are retained while extra revisions fail explicitly`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        for (id in 100L..163L) insertStudy(id)
        transaction { adapter.remember(7, accepted.id, (100L..131L).toList()) }
        transaction { adapter.remember(7, accepted.id, (132L..163L).toList()) }
        for (revision in 1..32) {
            execute("update studies set topic = 'Revision $revision' where id = 100")
            assertThat(transaction { adapter.revise(7, accepted.id, 100) }.single { it.studyId == 100L }.revision)
                .isEqualTo(revision.toLong())
        }
        val current = transaction { adapter.revise(7, accepted.id, 100) }
        assertThat(current).hasSize(64)
        assertThat(adapter.list(7, accepted.id)).hasSize(96)
        assertThat(snapshotCount()).isEqualTo(64)
        assertThat(revisionCount()).isEqualTo(32)
        execute("update studies set topic = 'Uncaptured over-limit change' where id = 100")
        assertThat(runCatching { transaction { adapter.revise(7, accepted.id, 100) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("limit")
        assertThat(transaction { adapter.remember(7, accepted.id, listOf(100)) }.single().topic).isEqualTo("Revision 32")
        assertThat(revisionCount()).isEqualTo(32)
        assertThat(adapter.list(7, accepted.id).first { it.studyId == 100L }.topic).isEqualTo("Synthetic topic 100")
    }

    @Test
    fun `revision checks the hard lease again after reading metadata including unchanged no-op attempts`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11)
        transaction { adapter.remember(7, accepted.id, listOf(11)) }
        var readings = 0
        val expiringClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (readings++ == 0) now else accepted.hardEndsAt
        }
        val expiring = VoiceTutorStudyContextAdapter(database, expiringClock)
        assertThat(runCatching { transaction { expiring.revise(7, accepted.id, 11) } }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("expired")
        assertThat(revisionCount()).isZero()
    }

    @Test
    fun `nullable live study FK cannot erase accepted context and deleting a call cascades its revision ledger`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Accepted Redis", difficulty = 6)
        transaction { adapter.prepare(accepted) }
        execute("update studies set difficulty_level = 8 where id = 10")
        val changed = transaction { adapter.revise(7, accepted.id, 10) }
        execute("delete from studies where id = 10")
        execute("update voice_tutor_sessions set study_id = null")
        assertThat(transaction { adapter.prepare(accepted.copy(studyId = null)) }).isEqualTo(changed)
        assertThat(adapter.list(7, accepted.id)).hasSize(2)
        execute("delete from voice_tutor_sessions")
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
    }

    @Test
    fun `a transaction rollback removes appended revisions but never touches first-seen snapshots`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, difficulty = 3)
        val original = transaction { adapter.remember(7, accepted.id, listOf(11)) }
        execute("update studies set difficulty_level = 8 where id = 11")
        val failure = runCatching {
            transaction {
                adapter.revise(7, accepted.id, 11)
                error("synthetic rollback")
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(adapter.list(7, accepted.id)).isEqualTo(original)
        assertThat(adapter.currentRevision(7, accepted.id)).isZero()
    }

    @Test
    fun `concurrent node revisions share one monotonically increasing session epoch`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(11, difficulty = 3)
        insertStudy(12, difficulty = 4)
        transaction { adapter.remember(7, accepted.id, listOf(11, 12)) }
        execute("update studies set difficulty_level = 8 where id in (11, 12)")
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            transaction {
                val result = adapter.revise(7, accepted.id, 11)
                inserted.complete(Unit)
                release.await()
                result
            }
        }
        try {
            withTimeout(5_000) { inserted.await() }
            val entered = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) {
                transaction { entered.complete(Unit); adapter.revise(7, accepted.id, 12) }
            }
            withTimeout(5_000) { entered.await() }
            assertThat(withTimeoutOrNull(100) { second.await() }).isNull()
            release.complete(Unit)
            assertThat(withTimeout(5_000) { first.await() }.single { it.studyId == 11L }.revision).isEqualTo(1)
            assertThat(withTimeout(5_000) { second.await() }.single { it.studyId == 12L }.revision).isEqualTo(2)
        } finally {
            release.complete(Unit)
        }
        assertThat(adapter.list(7, accepted.id).map { it.revision }).containsExactly(0, 0, 1, 2)
    }

    @Test
    fun `discovery has no invented topic and explicit focus atomically captures its owned frozen path`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Root Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertStudy(12, parentId = 11, topic = "Expiry", difficulty = 5)

        assertThat(transaction { adapter.prepare(discovery) }).isEmpty()
        assertThat(adapter.history(7, discovery.id)).isEmpty()
        assertThat(snapshotCount()).isZero()
        val selected = transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }

        assertThat(selected.focus).isEqualTo(VoiceTutorLessonFocus(11, 1))
        assertThat(selected.snapshot).isEqualTo(VoiceTutorStudySnapshot(11, 10, "Cache", 4, 1))
        assertThat(selected.revision).isEqualTo(1)
        assertThat(adapter.currentRevision(7, discovery.id)).isEqualTo(1)
        assertThat(adapter.history(7, discovery.id)).containsExactly(selected.focus)
        assertThat(adapter.history(99, discovery.id)).isEmpty()
        assertThat(focusHeader()).containsExactly(11L, null, "Cache", 4)
        val context = transaction { adapter.prepare(discovery) }
        assertThat(context).contains(VoiceTutorStudySnapshot(10, null, "Root Redis", 3))
        assertThat(context).contains(VoiceTutorStudySnapshot(12, 11, "Expiry", 5))
        assertThat(context.single { it.studyId == 11L }).isEqualTo(selected.snapshot)
    }

    @Test
    fun `guided advance atomically accepts only the persisted current focus direct child`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertStudy(12, parentId = 11, topic = "Expiry", difficulty = 5)
        insertStudy(13, parentId = 10, topic = "PubSub", difficulty = 6)
        insertTranscriptTurn(11, accepted.id)

        val child = transaction { requireNotNull(adapter.advance(
            7, accepted.id, 10, 11, learnerTurnId = 11, authorization = authorization(),
            commitAuthority = commitAuthority(),
        )) }

        assertThat(child.focus).isEqualTo(VoiceTutorLessonFocus(11, 1))
        assertThat(child.snapshot).isEqualTo(VoiceTutorStudySnapshot(11, 10, "Cache", 4, 1))
        assertThat(focusHeader()).containsExactly(11L, 10L, "Cache", 4)
        insertTranscriptTurn(12, accepted.id)
        transaction { assertThat(adapter.advance(7, accepted.id, 11, 13, 12, authorization = authorization(), commitAuthority = commitAuthority())).isNull() }
        transaction { assertThat(adapter.advance(7, accepted.id, 10, 12, 12, authorization = authorization(), commitAuthority = commitAuthority())).isNull() }
        transaction { assertThat(adapter.advance(7, accepted.id, 11, 11, 12, authorization = authorization(), commitAuthority = commitAuthority())).isNull() }
        transaction { assertThat(adapter.advance(7, accepted.id, 11, 12, 11, authorization = authorization(), commitAuthority = commitAuthority())).isNull() }
        assertThat(adapter.history(7, accepted.id)).containsExactly(child.focus)
    }

    @Test
    fun `learner focus locks and revalidates the exact auth session and provider call before consuming its lease`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertTranscriptTurn(11, discovery.id)

        val wrongAuthLease = authorization()
        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 10, 11, authorization = wrongAuthLease,
                commitAuthority = commitAuthority().copy(authSessionId = 12),
            )).isNull()
        }
        assertThat(wrongAuthLease.isActive()).isTrue()

        val wrongCallLease = authorization()
        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 10, 11, authorization = wrongCallLease,
                commitAuthority = commitAuthority("rtc_other_call"),
            )).isNull()
        }
        assertThat(wrongCallLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        assertThat(focusHeader()).containsExactly(null, null, "", 0)
    }

    @Test
    fun `auth expiry is rechecked before lease consumption while permanent sessions remain valid`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertTranscriptTurn(11, discovery.id)
        val expiryUpdated = database.sql("update user_devices set session_expires_at = :expiresAt where id = 11")
            .bind("expiresAt", now.plusSeconds(1).utc()).fetch().rowsUpdated().awaitSingle()
        assertThat(expiryUpdated).isEqualTo(1)
        var readings = 0
        val expiring = VoiceTutorStudyContextAdapter(database, object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (readings++ < 3) now else now.plusSeconds(2)
        })
        val expiringLease = authorization()

        transaction {
            assertThat(expiring.focus(
                7, discovery.id, 10, 11, authorization = expiringLease,
                commitAuthority = commitAuthority(),
            )).isNull()
        }

        assertThat(expiringLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()

        execute("update user_devices set session_expires_at = null where id = 11")
        val permanentLease = authorization()
        val permanent = VoiceTutorStudyContextAdapter(
            database,
            Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC),
        )

        val selected = transaction {
            requireNotNull(permanent.focus(
                7, discovery.id, 10, 11, authorization = permanentLease,
                commitAuthority = commitAuthority(),
            ))
        }

        assertThat(selected.studyId).isEqualTo(10)
        assertThat(permanentLease.isActive()).isFalse()
        assertThat(focusCount()).isEqualTo(1)
    }

    @Test
    fun `concurrent logout that wins the auth row lock prevents focus commit without consuming its lease`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertTranscriptTurn(11, discovery.id)
        val lease = authorization()
        val logoutUpdated = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val logout = async(Dispatchers.Default) {
            transaction {
                val updated = database.sql(
                    "update user_devices set logged_out_at = :now where id = 11",
                ).bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
                assertThat(updated).isEqualTo(1)
                logoutUpdated.complete(Unit)
                releaseLogout.await()
            }
        }
        try {
            withTimeout(5_000) { logoutUpdated.await() }
            val focusEnteredTransaction = CompletableDeferred<Unit>()
            val focus = async(Dispatchers.Default) {
                transaction {
                    focusEnteredTransaction.complete(Unit)
                    listOf(adapter.focus(
                        7, discovery.id, 10, 11, authorization = lease,
                        commitAuthority = commitAuthority(),
                    ))
                }
            }
            withTimeout(5_000) { focusEnteredTransaction.await() }
            assertThat(withTimeoutOrNull(100) { focus.await() }).isNull()
            releaseLogout.complete(Unit)
            withTimeout(5_000) { logout.await() }
            assertThat(withTimeout(5_000) { focus.await() }.single()).isNull()
        } finally {
            releaseLogout.complete(Unit)
        }

        assertThat(lease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        assertThat(focusHeader()).containsExactly(null, null, "", 0)
    }

    @Test
    fun `focus rejects renamed or reparented metadata after the spoken offer without consuming its lease`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertTranscriptTurn(11, discovery.id)
        val offered = VoiceTutorStudyTargetCandidate(11, 10, "Cache")
        execute("update studies set topic = 'Renamed cache' where id = 11")
        val renameLease = authorization()

        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 11, 11, 0, renameLease, offered, commitAuthority(),
            )).isNull()
        }

        assertThat(renameLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()

        execute("update studies set topic = 'Cache', parent_study_id = null where id = 11")
        val reparentLease = authorization()
        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 11, 11, 0, reparentLease, offered, commitAuthority(),
            )).isNull()
        }
        assertThat(reparentLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
    }

    @Test
    fun `focus rejects a changed exact readback difficulty before consuming its lease or writing a revision`(): Unit =
        runBlocking {
            val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
            insertSession(discovery)
            insertStudy(10, topic = "Spring", difficulty = 8)
            insertTranscriptTurn(11, discovery.id)
            val exactReadback = VoiceTutorStudyTargetCandidate(10, null, "Spring", difficulty = 7)
            val lease = authorization()

            transaction {
                assertThat(adapter.focus(
                    7,
                    discovery.id,
                    10,
                    learnerTurnId = 11,
                    expectedCurrentRevision = 0,
                    authorization = lease,
                    expectedCandidate = exactReadback,
                    commitAuthority = commitAuthority(),
                    expectedTraversal = VoiceTutorStudyTargetTraversal(),
                )).isNull()
            }

            assertThat(lease.isActive()).isTrue()
            assertThat(snapshotCount()).isZero()
            assertThat(revisionCount()).isZero()
            assertThat(focusCount()).isZero()
            assertThat(focusHeader()).containsExactly(null, null, "", 0)
        }

    @Test
    fun `focus rejects a stale automatic descent topology without consuming its lease`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertStudy(12, parentId = 11, topic = "Expiry", difficulty = 5)
        insertTranscriptTurn(11, discovery.id)
        val offered = VoiceTutorStudyTargetCandidate(12, 11, "Expiry")
        val traversal = VoiceTutorStudyTargetTraversal(
            singleChildEdges = listOf(
                VoiceTutorStudyTargetSingleChildEdge(10, 11),
                VoiceTutorStudyTargetSingleChildEdge(11, 12),
            ),
            terminalLeafStudyId = 12,
        )

        // The spoken single-child path is no longer single: a fresh branch must be offered.
        insertStudy(13, parentId = 10, topic = "PubSub", difficulty = 4)
        val branchLease = authorization()
        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 12, 11, 0, branchLease, offered, commitAuthority(), traversal,
            )).isNull()
        }
        assertThat(branchLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()

        // The endpoint was offered as a leaf, so a newly added child also invalidates that offer.
        execute("delete from studies where id = 13")
        insertStudy(14, parentId = 12, topic = "TTL", difficulty = 6)
        val leafLease = authorization()
        transaction {
            assertThat(adapter.focus(
                7, discovery.id, 12, 11, 0, leafLease, offered, commitAuthority(), traversal,
            )).isNull()
        }
        assertThat(leafLease.isActive()).isTrue()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
    }

    @Test
    fun `deepest allowed thirty two edge focus locks all thirty three owned nodes`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        var parentId: Long? = null
        for (id in 100L..132L) {
            insertStudy(id, parentId = parentId, topic = "Depth $id")
            parentId = id
        }

        val selected = transaction { requireNotNull(adapter.focus(7, discovery.id, 132)) }

        assertThat(selected.studyId).isEqualTo(132)
        assertThat(snapshotCount()).isEqualTo(33)
        assertThat(adapter.history(7, discovery.id)).containsExactly(VoiceTutorLessonFocus(132, 1))
    }

    @Test
    fun `guided advance rejects a stale expected lesson revision while holding the session lock`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertStudy(12, parentId = 11, topic = "Expiry", difficulty = 5)
        insertTranscriptTurn(11, accepted.id)
        val first = transaction {
            requireNotNull(adapter.advance(
                7, accepted.id, 10, 11, 11, expectedCurrentRevision = 0, authorization = authorization(),
                commitAuthority = commitAuthority(),
            ))
        }
        insertTranscriptTurn(12, accepted.id)

        transaction {
            assertThat(
                adapter.advance(7, accepted.id, 11, 12, 12, 0, authorization(), commitAuthority = commitAuthority()),
            ).isNull()
        }

        assertThat(adapter.currentRevision(7, accepted.id)).isEqualTo(1)
        assertThat(adapter.history(7, accepted.id)).containsExactly(first.focus)
        assertThat(revisionCount()).isEqualTo(1)
        assertThat(focusHeader()).containsExactly(11L, 10L, "Cache", 4)
    }

    @Test
    fun `focus rejects an authorized learner turn when a newer transcript already exists at the locked write`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertTranscriptTurn(11, discovery.id)
        insertTranscriptTurn(12, discovery.id, role = "TUTOR")

        transaction {
            assertThat(
                adapter.focus(7, discovery.id, 10, 11, 0, authorization(), commitAuthority = commitAuthority()),
            ).isNull()
        }

        assertThat(adapter.currentRevision(7, discovery.id)).isZero()
        assertThat(adapter.history(7, discovery.id)).isEmpty()
        assertThat(revisionCount()).isZero()
        assertThat(focusHeader()).containsExactly(null, null, "", 0)
    }

    @Test
    fun `guided advance fails closed when the direct child edge changes before the locked focus write`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        execute("update studies set parent_study_id = null where id = 11")
        insertTranscriptTurn(11, accepted.id)

        transaction { assertThat(adapter.advance(
            7, accepted.id, 10, 11, 11, authorization = authorization(),
            commitAuthority = commitAuthority(),
        )).isNull() }

        assertThat(adapter.history(7, accepted.id)).isEmpty()
        assertThat(revisionCount()).isZero()
        assertThat(focusHeader()).containsExactly(10L, 10L, "Accepted Redis", 6)
    }

    @Test
    fun `concurrent reparent wins before the locked path check and guided advance fails closed`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertTranscriptTurn(11, accepted.id)
        val reparentedButUncommitted = CompletableDeferred<Unit>()
        val releaseReparent = CompletableDeferred<Unit>()
        val reparent = async(Dispatchers.Default) {
            transaction {
                val updated = database.sql("update studies set parent_study_id = null where id = 11")
                    .fetch().rowsUpdated().awaitSingle()
                assertThat(updated).isEqualTo(1)
                reparentedButUncommitted.complete(Unit)
                releaseReparent.await()
            }
        }
        try {
            withTimeout(5_000) { reparentedButUncommitted.await() }
            val advanceEnteredTransaction = CompletableDeferred<Unit>()
            val advance = async(Dispatchers.Default) {
                transaction {
                    advanceEnteredTransaction.complete(Unit)
                    // A list keeps the async result non-null, so timeout and the expected
                    // null focus result remain distinguishable below.
                    listOf(adapter.advance(
                        7, accepted.id, 10, 11, 11, authorization = authorization(),
                        commitAuthority = commitAuthority(),
                    ))
                }
            }
            withTimeout(5_000) { advanceEnteredTransaction.await() }
            // The reparent transaction and focus transaction use separate connections.
            // The focus must wait for the child row lock instead of authorizing the old edge.
            assertThat(withTimeoutOrNull(100) { advance.await() }).isNull()
            releaseReparent.complete(Unit)
            withTimeout(5_000) { reparent.await() }
            assertThat(withTimeout(5_000) { advance.await() }.single()).isNull()
        } finally {
            releaseReparent.complete(Unit)
        }

        assertThat(
            database.sql("select count(*) as count from studies where id = 11 and parent_study_id is null")
                .map { row, _ -> (row.get("count") as Number).toLong() }.one().awaitSingle(),
        ).isEqualTo(1)
        assertThat(adapter.history(7, accepted.id)).isEmpty()
        assertThat(snapshotCount()).isZero()
        assertThat(adapter.currentRevision(7, accepted.id)).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        assertThat(focusHeader()).containsExactly(10L, 10L, "Accepted Redis", 6)
    }

    @Test
    fun `new speech invalidates an in flight guided focus while its study row is locked`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Cache", difficulty = 4)
        insertTranscriptTurn(11, accepted.id)
        val focusAuthorization = authorization()
        val studyRowLocked = CompletableDeferred<Unit>()
        val releaseStudyRow = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.Default) {
            transaction {
                val lockedId = database.sql("select id from studies where id = 11 for update")
                    .map { row, _ -> (row.get("id") as Number).toLong() }.one().awaitSingle()
                assertThat(lockedId).isEqualTo(11)
                studyRowLocked.complete(Unit)
                releaseStudyRow.await()
            }
        }
        try {
            withTimeout(5_000) { studyRowLocked.await() }
            val advanceEnteredTransaction = CompletableDeferred<Unit>()
            val advance = async(Dispatchers.Default) {
                transaction {
                    advanceEnteredTransaction.complete(Unit)
                    // Keep timeout distinct from the expected nullable selection result.
                    listOf(adapter.advance(
                        7, accepted.id, 10, 11, 11,
                        expectedCurrentRevision = 0,
                        authorization = focusAuthorization,
                        commitAuthority = commitAuthority(),
                    ))
                }
            }
            withTimeout(5_000) { advanceEnteredTransaction.await() }
            assertThat(withTimeoutOrNull(100) { advance.await() }).isNull()

            // Models a newer speech_started edge while the old tool invocation is still
            // waiting to authorize its path on another database connection.
            focusAuthorization.invalidate()
            assertThat(focusAuthorization.isActive()).isFalse()
            releaseStudyRow.complete(Unit)
            withTimeout(5_000) { blocker.await() }
            assertThat(withTimeout(5_000) { advance.await() }.single()).isNull()
        } finally {
            releaseStudyRow.complete(Unit)
        }

        assertThat(adapter.history(7, accepted.id)).isEmpty()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        assertThat(focusHeader()).containsExactly(10L, 10L, "Accepted Redis", 6)
    }

    @Test
    fun `one accepted learner turn cannot select two different lesson focuses`(): Unit = runBlocking {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(20, topic = "Kafka", difficulty = 4)
        insertTranscriptTurn(11, discovery.id)

        val first = transaction { requireNotNull(adapter.focus(
            7, discovery.id, 10, 11, authorization = authorization(),
            commitAuthority = commitAuthority(),
        )) }
        transaction { assertThat(adapter.focus(
            7, discovery.id, 20, 11, authorization = authorization(),
            commitAuthority = commitAuthority(),
        )).isNull() }
        insertTranscriptTurn(12, discovery.id)
        val second = transaction { requireNotNull(adapter.focus(
            7, discovery.id, 20, 12, authorization = authorization(),
            commitAuthority = commitAuthority(),
        )) }

        assertThat(first.focus).isEqualTo(VoiceTutorLessonFocus(10, 1))
        assertThat(second.focus).isEqualTo(VoiceTutorLessonFocus(20, 2))
        assertThat(adapter.history(7, discovery.id)).containsExactly(first.focus, second.focus)
    }

    @Test
    fun `same explicit focus is idempotent without adopting live rename or downgrading metadata epoch`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(10, topic = "Redis root", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Frozen cache", difficulty = 4)
        val initial = transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }
        execute("update studies set topic = 'Changed live cache', difficulty_level = 9 where id = 11")
        execute("update studies set difficulty_level = 7 where id = 10")
        transaction { adapter.revise(7, discovery.id, 10) }

        val repeated = transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }

        assertThat(repeated.focus).isEqualTo(initial.focus)
        assertThat(repeated.snapshot).isEqualTo(initial.snapshot)
        assertThat(repeated.revision).isEqualTo(2)
        assertThat(adapter.history(7, discovery.id)).containsExactly(initial.focus)
        assertThat(revisionCount()).isEqualTo(2)
        assertThat(focusHeader()).containsExactly(11L, null, "Frozen cache", 4)
    }

    @Test
    fun `first explicit selection of the legacy anchor creates epoch one and later root selection preserves acceptance`(): Unit = runBlocking {
        val accepted = session()
        insertSession(accepted)
        insertStudy(10, topic = "Changed live Redis", difficulty = 9)
        insertStudy(20, topic = "Kafka", difficulty = 2)
        val first = transaction { requireNotNull(adapter.focus(7, accepted.id, 10)) }
        assertThat(first.revision).isEqualTo(1)
        assertThat(first.snapshot.topic).isEqualTo("Accepted Redis")
        assertThat(first.snapshot.difficulty).isEqualTo(6)

        val second = transaction { requireNotNull(adapter.focus(7, accepted.id, 20)) }
        assertThat(second.revision).isEqualTo(2)
        assertThat(focusHeader()).containsExactly(20L, 10L, "Kafka", 2)
        val current = transaction { adapter.prepare(accepted) }
        assertThat(current.single { it.studyId == 10L }.topic).isEqualTo("Accepted Redis")
        assertThat(current.single { it.studyId == 20L }.topic).isEqualTo("Kafka")
        assertThat(adapter.list(7, accepted.id).first { it.studyId == 10L }.revision).isZero()
    }

    @Test
    fun `focus rejects foreign missing cyclic or unresolved ancestry without creating any metadata`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(10)
        insertStudy(20, userId = 99)
        insertStudy(21, parentId = 20)
        insertStudy(22, parentId = 23)
        insertStudy(23, parentId = 22)
        insertStudy(24, parentId = 999)

        for (studyId in listOf(0L, -1L, 20L, 21L, 22L, 24L, 999L)) {
            transaction { assertThat(adapter.focus(7, discovery.id, studyId)).isNull() }
        }
        transaction { assertThat(adapter.focus(99, discovery.id, 20)).isNull() }
        transaction { assertThat(adapter.focus(7, "missing-call", 10)).isNull() }
        execute("update voice_tutor_sessions set status = 'ENDING'")
        transaction { assertThat(adapter.focus(7, discovery.id, 10)).isNull() }

        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        assertThat(focusHeader().first()).isNull()
    }

    @Test
    fun `focus rechecks hard deadline after owned reads without changing the current lesson`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(10)
        var readings = 0
        val expiring = VoiceTutorStudyContextAdapter(database, object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (readings++ == 0) now else discovery.hardEndsAt
        })

        transaction { assertThat(expiring.focus(7, discovery.id, 10)).isNull() }
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
    }

    @Test
    fun `focus and metadata edits share a thirty two revision cap and same-focus retry still succeeds at the cap`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(11)
        insertStudy(12)
        transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }
        execute("update studies set difficulty_level = 8 where id = 11")
        transaction { adapter.revise(7, discovery.id, 11) }
        for (revision in 3..32) {
            val id = if (revision % 2 == 1) 12L else 11L
            assertThat(transaction { requireNotNull(adapter.focus(7, discovery.id, id)) }.revision)
                .isEqualTo(revision.toLong())
        }

        transaction { assertThat(adapter.focus(7, discovery.id, 12)).isNull() }
        val repeated = transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }
        assertThat(repeated.revision).isEqualTo(32)
        assertThat(revisionCount()).isEqualTo(32)
        assertThat(focusCount()).isEqualTo(31)
        assertThat(snapshotCount()).isEqualTo(2)
        assertThat(focusHeader()).containsExactly(11L, null, "Synthetic topic 11", 8)
    }

    @Test
    fun `full snapshot cache rejects an uncaptured path but can select an already captured owned node`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        for (id in 100L..164L) insertStudy(id)
        transaction { adapter.remember(7, discovery.id, (100L..131L).toList()) }
        transaction { adapter.remember(7, discovery.id, (132L..163L).toList()) }

        transaction { assertThat(adapter.focus(7, discovery.id, 164)).isNull() }
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
        val selected = transaction { requireNotNull(adapter.focus(7, discovery.id, 100)) }
        assertThat(selected.studyId).isEqualTo(100)
        assertThat(selected.revision).isEqualTo(1)
        assertThat(snapshotCount()).isEqualTo(64)
    }

    @Test
    fun `rolled back focus never leaves a live pointer ledger or metadata epoch behind`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(10)
        val failure = runCatching {
            transaction {
                requireNotNull(adapter.focus(7, discovery.id, 10))
                error("synthetic focus rollback")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(focusHeader().first()).isNull()
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
    }

    @Test
    fun `concurrent focus writers serialize against the same session epoch without mixing pointers or metadata`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(11)
        insertStudy(12)
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            transaction {
                val result = requireNotNull(adapter.focus(7, discovery.id, 11))
                captured.complete(Unit)
                release.await()
                result
            }
        }
        try {
            withTimeout(5_000) { captured.await() }
            val entered = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) {
                transaction { entered.complete(Unit); requireNotNull(adapter.focus(7, discovery.id, 12)) }
            }
            withTimeout(5_000) { entered.await() }
            assertThat(withTimeoutOrNull(100) { second.await() }).isNull()
            release.complete(Unit)
            assertThat(withTimeout(5_000) { first.await() }.revision).isEqualTo(1)
            assertThat(withTimeout(5_000) { second.await() }.revision).isEqualTo(2)
        } finally {
            release.complete(Unit)
        }
        assertThat(adapter.history(7, discovery.id)).containsExactly(VoiceTutorLessonFocus(11, 1), VoiceTutorLessonFocus(12, 2))
        assertThat(focusHeader()).containsExactly(12L, null, "Synthetic topic 12", 5)
    }

    @Test
    fun `deleted current focus remains historical without becoming discovery and session deletion cascades both ledgers`(): Unit = runBlocking {
        val discovery = session(studyId = null)
        insertSession(discovery)
        insertStudy(11)
        val selected = transaction { requireNotNull(adapter.focus(7, discovery.id, 11)) }
        execute("delete from studies where id = 11")
        execute("update voice_tutor_sessions set study_id = null")

        assertThat(adapter.history(7, discovery.id)).containsExactly(selected.focus)
        assertThat(transaction { adapter.prepare(discovery) }).containsExactly(selected.snapshot)
        transaction { assertThat(adapter.focus(7, discovery.id, 11)).isNull() }
        execute("delete from voice_tutor_sessions")
        assertThat(snapshotCount()).isZero()
        assertThat(revisionCount()).isZero()
        assertThat(focusCount()).isZero()
    }

    @Test
    fun `native focus uses spoken order despite preambles and late ASR while retaining one focus per learner`() = runBlocking<Unit> {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Redis", difficulty = 3)
        insertStudy(20, topic = "Spring", difficulty = 7)
        insertTranscriptTurn(100, discovery.id, sequenceNumber = 2)
        val first = transaction { requireNotNull(adapter.focusFromRealtimeModel(
            7, discovery.id, 10, 100, 0, VoiceTutorStudyTargetCandidate(10, null, "Redis", 3), commitAuthority(),
        )) }
        assertThat(first.revision).isEqualTo(1)
        insertTranscriptTurn(200, discovery.id, role = "TUTOR", sequenceNumber = 3)
        insertTranscriptTurn(50, discovery.id, sequenceNumber = 4)
        insertTranscriptTurn(300, discovery.id, role = "TUTOR", sequenceNumber = 5)
        insertTranscriptTurn(400, discovery.id, sequenceNumber = 1) // Older speech whose ASR arrived last.
        val selected = transaction { requireNotNull(adapter.focusFromRealtimeModel(
            7, discovery.id, 20, 50, 1, VoiceTutorStudyTargetCandidate(20, null, "Spring", 7), commitAuthority(),
        )) }
        assertThat(selected.studyId).isEqualTo(20)
        assertThat(selected.revision).isEqualTo(2)
        transaction {
            assertThat(adapter.focusFromRealtimeModel(7, discovery.id, 10, 50, 2,
                VoiceTutorStudyTargetCandidate(10, null, "Redis", 3), commitAuthority())).isNull()
        }
        assertThat(focusCount()).isEqualTo(2)
    }

    @Test
    fun `each persisted structured learner submission authorizes one new focus after an earlier spoken focus`() = runBlocking<Unit> {
        val discovery = session(studyId = null).copy(topic = "", difficulty = 0)
        insertSession(discovery)
        insertStudy(10, topic = "Root", difficulty = 3)
        insertStudy(11, parentId = 10, topic = "Child", difficulty = 4)
        insertStudy(12, parentId = 11, topic = "Grandchild", difficulty = 5)
        insertTranscriptTurn(100, discovery.id, sequenceNumber = 1)
        val spoken = transaction { requireNotNull(adapter.focusFromRealtimeModel(
            7, discovery.id, 10, 100, 0,
            VoiceTutorStudyTargetCandidate(10, null, "Root", 3), commitAuthority(),
        )) }
        assertThat(spoken.revision).isEqualTo(1)

        insertTranscriptTurn(101, discovery.id, sequenceNumber = 2,
            providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "first-selection")
        val firstSelection = transaction { requireNotNull(adapter.focusFromRealtimeModel(
            7, discovery.id, 11, 101, 1,
            VoiceTutorStudyTargetCandidate(11, 10, "Child", 4), commitAuthority(),
            expectedParentStudyId = 10,
        )) }
        assertThat(firstSelection.revision).isEqualTo(2)
        transaction {
            assertThat(adapter.focusFromRealtimeModel(
                7, discovery.id, 12, 101, 2,
                VoiceTutorStudyTargetCandidate(12, 11, "Grandchild", 5), commitAuthority(),
                expectedParentStudyId = 11,
            )).isNull()
        }

        insertTranscriptTurn(102, discovery.id, sequenceNumber = 3,
            providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "second-selection")
        val secondSelection = transaction { requireNotNull(adapter.focusFromRealtimeModel(
            7, discovery.id, 12, 102, 2,
            VoiceTutorStudyTargetCandidate(12, 11, "Grandchild", 5), commitAuthority(),
            expectedParentStudyId = 11,
        )) }
        assertThat(secondSelection.revision).isEqualTo(3)
        assertThat(focusCount()).isEqualTo(3)
        assertThat(focusHeader()).containsExactly(12L, null, "Grandchild", 5)
        val learnerTurns = database.sql(
            "select learner_turn_id from voice_tutor_lesson_focuses order by revision",
        ).map { row, _ -> (row.get("learner_turn_id") as Number).toLong() }
            .all().collectList().awaitSingle()
        assertThat(learnerTurns).containsExactly(100L, 101L, 102L)
    }

    private suspend fun focusCount(): Long = database.sql("select count(*) as count from voice_tutor_lesson_focuses")
        .map { row, _ -> (row.get("count") as Number).toLong() }.one().awaitSingle()

    private suspend fun focusHeader(): List<Any?> = database.sql(
        "select study_id, accepted_study_id, topic_snapshot, difficulty_snapshot from voice_tutor_sessions",
    ).map { row, _ ->
        listOf((row.get("study_id") as? Number)?.toLong(), (row.get("accepted_study_id") as? Number)?.toLong(),
            row.get("topic_snapshot", String::class.java), (row.get("difficulty_snapshot") as Number).toInt())
    }.one().awaitSingle()

    private suspend fun <T : Any> transaction(block: suspend () -> T): T =
        requireNotNull(transactions.executeAndAwait { block() })

    private fun authorization() = VoiceTutorFocusAuthorization()

    private fun commitAuthority(providerCallId: String = "rtc_synthetic_call") = VoiceTutorFocusCommitAuthority(
        deviceId = "synthetic-device",
        authSessionId = 11,
        providerCallId = providerCallId,
    )

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private suspend fun snapshotCount(): Long = database.sql("select count(*) as count from voice_tutor_study_snapshots")
        .map { row, _ -> (row.get("count") as Number).toLong() }.one().awaitSingle()

    private suspend fun revisionCount(): Long = database.sql("select count(*) as count from voice_tutor_study_revisions")
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
                (id, user_id, study_id, accepted_study_id, topic_snapshot, difficulty_snapshot,
                 provider_session_id, status, hard_ends_at, ended_at, updated_at)
            values (:id, :userId, :studyId, :acceptedStudyId, :topic, :difficulty,
                    :providerCallId, :status, :hardEndsAt, :endedAt, :now)
            """.trimIndent(),
        ).bind("id", session.id).bind("userId", session.userId).bind("topic", session.topic)
            .bind("difficulty", session.difficulty).bind("status", session.status.name)
            .bind("hardEndsAt", session.hardEndsAt.utc()).bind("now", now.utc())
        insert = session.providerSessionId?.let { insert.bind("providerCallId", it) }
            ?: insert.bindNull("providerCallId", String::class.java)
        insert = session.studyId?.let { insert.bind("studyId", it) }
            ?: insert.bindNull("studyId", java.lang.Long::class.java)
        insert = session.acceptedStudyId?.let { insert.bind("acceptedStudyId", it) }
            ?: insert.bindNull("acceptedStudyId", java.lang.Long::class.java)
        insert = session.endedAt?.let { insert.bind("endedAt", it.utc()) }
            ?: insert.bindNull("endedAt", LocalDateTime::class.java)
        insert.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertTranscriptTurn(
        id: Long,
        sessionId: String,
        role: String = "USER",
        sequenceNumber: Long = id,
        providerItemId: String = "item-$id",
    ) {
        database.sql(
            """
            insert into voice_tutor_transcript_turns(id, session_id, provider_item_id, role, sequence_number)
            values (:id, :sessionId, :providerItemId, :role, :sequenceNumber)
            """.trimIndent(),
        ).bind("id", id).bind("sessionId", sessionId).bind("providerItemId", providerItemId)
            .bind("role", role).bind("sequenceNumber", sequenceNumber)
            .fetch().rowsUpdated().awaitSingle()
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
        providerSessionId = "rtc_synthetic_call", status = status, resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko", model = "synthetic-realtime", voice = "synthetic-voice",
        topic = "Accepted Redis", difficulty = 6,
        periodStartedAt = now.minusSeconds(60), periodEndsAt = now.plusSeconds(3_600),
        reservedSeconds = 60, chargedSeconds = 0, maxSessionSeconds = 60,
        hardEndsAt = hardEndsAt, connectedAt = now, relayHeartbeatAt = now, acceptedAudioBytes = 0,
        endedAt = endedAt, finalizedAt = null, endReason = null, failureCode = null, failureMessage = null,
        createdAt = now, updatedAt = now,
    )
}
