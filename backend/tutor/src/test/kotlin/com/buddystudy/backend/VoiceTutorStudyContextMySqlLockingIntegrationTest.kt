package com.buddystudy.backend

import com.buddystudy.backend.voice.adapter.outbound.persistence.VoiceTutorStudyContextAdapter
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
@Timeout(30)
class VoiceTutorStudyContextMySqlLockingIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var adapter: VoiceTutorStudyContextAdapter
    @Autowired lateinit var database: DatabaseClient
    @Autowired lateinit var connectionFactory: ConnectionFactory
    @Autowired lateinit var environment: Environment

    private val transactions by lazy {
        TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))
    }

    @Test
    fun `single-child topology lock serializes a concurrent sibling insert`() = runBlocking<Unit> {
        val fixture = fixture()

        assertTopologyInsertWaitsForFocusCommit(fixture, insertParentId = fixture.rootStudyId)

        assertThat(childrenOf(fixture.rootStudyId)).containsExactly(fixture.childStudyId, fixture.insertedStudyId)
    }

    @Test
    fun `terminal-leaf topology lock serializes a concurrent child insert`() = runBlocking<Unit> {
        val fixture = fixture()

        assertTopologyInsertWaitsForFocusCommit(fixture, insertParentId = fixture.childStudyId)

        assertThat(childrenOf(fixture.childStudyId)).containsExactly(fixture.insertedStudyId)
    }

    private suspend fun assertTopologyInsertWaitsForFocusCommit(fixture: Fixture, insertParentId: Long) {
        val focusLocked = CompletableDeferred<Unit>()
        val releaseFocus = CompletableDeferred<Unit>()
        val insertStarted = CompletableDeferred<Unit>()
        val focus = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(Dispatchers.Default) {
            transaction {
                val isolation = database.sql("select @@transaction_isolation as isolation_level")
                    .map { row, _ -> row.get("isolation_level", String::class.java)!! }
                    .one().awaitSingle()
                assertThat(isolation).isEqualTo("REPEATABLE-READ")
                val selected = requireNotNull(
                    adapter.focus(
                        userId = fixture.userId,
                        sessionId = fixture.sessionId,
                        studyId = fixture.childStudyId,
                        learnerTurnId = fixture.learnerTurnId,
                        expectedCurrentRevision = 0,
                        authorization = VoiceTutorFocusAuthorization(),
                        expectedCandidate = VoiceTutorStudyTargetCandidate(
                            fixture.childStudyId,
                            fixture.rootStudyId,
                            fixture.childTopic,
                        ),
                        commitAuthority = VoiceTutorFocusCommitAuthority(
                            deviceId = fixture.deviceId,
                            authSessionId = fixture.authSessionId,
                            providerCallId = fixture.providerCallId,
                        ),
                        expectedTraversal = VoiceTutorStudyTargetTraversal(
                            singleChildEdges = listOf(
                                VoiceTutorStudyTargetSingleChildEdge(fixture.rootStudyId, fixture.childStudyId),
                            ),
                            terminalLeafStudyId = fixture.childStudyId,
                        ),
                    ),
                )
                assertThat(selected.studyId).isEqualTo(fixture.childStudyId)
                focusLocked.complete(Unit)
                releaseFocus.await()
                selected
            }
        }
        val insert = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(Dispatchers.IO) {
            withTimeout(10_000) { focusLocked.await() }
            insertStudyWithoutForeignKeyLock(fixture, insertParentId, insertStarted)
        }

        try {
            withTimeout(10_000) { insertStarted.await() }
            assertThat(withTimeoutOrNull(250) { insert.await() }).isNull()

            releaseFocus.complete(Unit)
            withTimeout(10_000) { focus.await() }
            withTimeout(10_000) { insert.await() }
        } finally {
            releaseFocus.complete(Unit)
        }
    }

    /**
     * The production schema's self-FK would also wait on the parent row locked by focus. Disable
     * that check only on this isolated inserter connection so waiting here specifically exercises
     * the `(user_id, parent_study_id, sort_order, id)` next-key range held by `FOR UPDATE`.
     */
    private fun insertStudyWithoutForeignKeyLock(
        fixture: Fixture,
        parentStudyId: Long,
        insertStarted: CompletableDeferred<Unit>,
    ) {
        val jdbcUrl = requireNotNull(environment.getProperty("spring.flyway.url"))
        val username = requireNotNull(environment.getProperty("spring.flyway.user"))
        val password = requireNotNull(environment.getProperty("spring.flyway.password"))
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("set session innodb_lock_wait_timeout = 5")
                statement.execute("set session foreign_key_checks = 0")
            }
            try {
                connection.prepareStatement(
                    """
                    insert into studies (
                        id, device_id, user_id, parent_study_id, sort_order, topic, difficulty_level,
                        interval_minutes, enabled, active_for_questions, notification_sound,
                        custom_prompt, openai_model, max_history_count, created_at, updated_at
                    ) values (?, ?, ?, ?, 1, ?, 5, 60, true, true, null, '', 'gpt-5.4', 10, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, fixture.insertedStudyId)
                    statement.setString(2, fixture.deviceId)
                    statement.setLong(3, fixture.userId)
                    statement.setLong(4, parentStudyId)
                    statement.setString(5, "Concurrent ${fixture.suffix}")
                    statement.setObject(6, fixture.createdAt)
                    statement.setObject(7, fixture.createdAt)
                    insertStarted.complete(Unit)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.createStatement().use { statement ->
                    statement.execute("set session foreign_key_checks = 1")
                }
            }
        }
    }

    private suspend fun fixture(): Fixture {
        val suffix = UUID.randomUUID().toString()
        val now = Instant.now()
        val createdAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        val deviceId = "topology-device-$suffix"
        val providerCallId = "rtc_$suffix"
        val userId = database.sql(
            """
            insert into users (
                provider, provider_id, password_hash, status, email, display_name, created_at, updated_at
            ) values ('EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName, :now, :now)
            """.trimIndent(),
        ).bind("providerId", "topology-$suffix").bind("email", "$suffix@example.com")
            .bind("displayName", "Topology $suffix").bind("now", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        val authSessionId = database.sql(
            """
            insert into user_devices (
                user_id, device_id, session_expires_at, last_login_at, last_seen_at, created_at, updated_at
            ) values (:userId, :deviceId, :expiresAt, :now, :now, :now, :now)
            """.trimIndent(),
        ).bind("userId", userId).bind("deviceId", deviceId)
            .bind("expiresAt", createdAt.plusHours(2)).bind("now", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        val rootStudyId = insertStudy(userId, deviceId, null, "Root $suffix", createdAt)
        val childTopic = "Child $suffix"
        val childStudyId = insertStudy(userId, deviceId, rootStudyId, childTopic, createdAt)
        val insertedStudyId = childStudyId + 1_000_000_000L
        val sessionId = UUID.randomUUID().toString()
        database.sql(
            """
            insert into voice_tutor_sessions (
                id, user_id, study_id, idempotency_key, provider_session_id, status, result_status,
                language, model, voice, topic_snapshot, difficulty_snapshot,
                period_started_at, period_ends_at, reserved_seconds, charged_seconds,
                max_session_seconds, hard_ends_at, created_at, updated_at
            ) values (
                :sessionId, :userId, null, :idempotencyKey, :providerCallId, 'ACTIVE', 'PENDING',
                'ko', 'gpt-realtime-test', 'marin', '', 0,
                :periodStartedAt, :periodEndsAt, 300, 0, 300, :hardEndsAt, :now, :now
            )
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("userId", userId)
            .bind("idempotencyKey", "topology-$suffix").bind("providerCallId", providerCallId)
            .bind("periodStartedAt", createdAt.minusMinutes(1)).bind("periodEndsAt", createdAt.plusMonths(1))
            .bind("hardEndsAt", createdAt.plusHours(1)).bind("now", createdAt)
            .fetch().rowsUpdated().awaitSingle()
        val learnerTurnId = database.sql(
            """
            insert into voice_tutor_transcript_turns (
                session_id, provider_item_id, role, transcript, sequence_number, occurred_at, created_at
            ) values (:sessionId, :providerItemId, 'USER', 'Choose the child', 1, :now, :now)
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("providerItemId", "item-$suffix").bind("now", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return Fixture(
            suffix = suffix,
            createdAt = createdAt,
            userId = userId,
            deviceId = deviceId,
            authSessionId = authSessionId,
            providerCallId = providerCallId,
            sessionId = sessionId,
            rootStudyId = rootStudyId,
            childStudyId = childStudyId,
            childTopic = childTopic,
            learnerTurnId = learnerTurnId,
            insertedStudyId = insertedStudyId,
        )
    }

    private suspend fun insertStudy(
        userId: Long,
        deviceId: String,
        parentStudyId: Long?,
        topic: String,
        createdAt: LocalDateTime,
    ): Long {
        var insert = database.sql(
            """
            insert into studies (
                device_id, user_id, parent_study_id, sort_order, topic, difficulty_level,
                interval_minutes, enabled, active_for_questions, notification_sound,
                custom_prompt, openai_model, max_history_count, created_at, updated_at
            ) values (
                :deviceId, :userId, :parentStudyId, 0, :topic, 5,
                60, true, true, null, '', 'gpt-5.4', 10, :now, :now
            )
            """.trimIndent(),
        ).bind("deviceId", deviceId).bind("userId", userId).bind("topic", topic).bind("now", createdAt)
        insert = parentStudyId?.let { insert.bind("parentStudyId", it) }
            ?: insert.bindNull("parentStudyId", java.lang.Long::class.java)
        return insert.filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
    }

    private suspend fun childrenOf(parentStudyId: Long): List<Long> = database.sql(
        """
        select id from studies where parent_study_id = :parentStudyId order by sort_order, id
        """.trimIndent(),
    ).bind("parentStudyId", parentStudyId)
        .map { row, _ -> (row.get("id") as Number).toLong() }
        .all().collectList().awaitSingle()

    private suspend fun <T : Any> transaction(block: suspend () -> T): T =
        requireNotNull(transactions.executeAndAwait { block() })

    private data class Fixture(
        val suffix: String,
        val createdAt: LocalDateTime,
        val userId: Long,
        val deviceId: String,
        val authSessionId: Long,
        val providerCallId: String,
        val sessionId: String,
        val rootStudyId: Long,
        val childStudyId: Long,
        val childTopic: String,
        val learnerTurnId: Long,
        val insertedStudyId: Long,
    )
}
