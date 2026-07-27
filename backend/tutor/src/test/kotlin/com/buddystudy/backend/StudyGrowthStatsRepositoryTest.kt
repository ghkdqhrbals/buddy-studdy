package com.buddystudy.backend

import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthStatsPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
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
class StudyGrowthStatsRepositoryTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var growthStats: StudyGrowthStatsPort
    @Autowired lateinit var databaseClient: DatabaseClient

    @Test
    fun `period query keeps generated questions for completion and marks only graded answers completed`(): Unit =
        runBlocking {
            val userId = System.nanoTime()
            val deviceId = "growth-${UUID.randomUUID()}"
            insertQuestion(
                deviceId = deviceId,
                userId = userId,
                studyId = 501,
                createdAt = Instant.parse("2035-01-02T00:00:00Z"),
                answeredAt = Instant.parse("2035-01-03T00:00:00Z"),
                score = 84,
            )
            insertQuestion(
                deviceId = deviceId,
                userId = userId,
                studyId = 502,
                createdAt = Instant.parse("2035-01-04T00:00:00Z"),
            )
            insertQuestion(
                deviceId = deviceId,
                userId = userId,
                studyId = 503,
                createdAt = Instant.parse("2034-12-01T00:00:00Z"),
            )

            val records = growthStats.findByUser(
                userId = userId,
                startAt = Instant.parse("2035-01-01T00:00:00Z"),
                endAt = Instant.parse("2035-02-01T00:00:00Z"),
            )

            assertThat(records.map { it.studyId }).containsExactly(501, 502)
            assertThat(records.single { it.studyId == 501L }.completed).isTrue()
            assertThat(records.single { it.studyId == 501L }.score).isEqualTo(84)
            assertThat(records.single { it.studyId == 502L }.completed).isFalse()
        }

    private suspend fun insertQuestion(
        deviceId: String,
        userId: Long,
        studyId: Long,
        createdAt: Instant,
        answeredAt: Instant? = null,
        score: Int? = null,
    ) {
        var statement = databaseClient.sql(
            """
            insert into questions (
                device_id, user_id, study_id, question, topic, difficulty_level,
                scheduled_for, status, source, is_public, language, source_language, created_at, updated_at,
                answered_at, score
            ) values (
                :deviceId, :userId, :studyId, 'Question', 'Topic', 6,
                :createdAt, :status, 'manual', true, 'ko', 'ko', :createdAt, :createdAt,
                :answeredAt, :score
            )
            """.trimIndent(),
        )
            .bind("deviceId", deviceId)
            .bind("userId", userId)
            .bind("studyId", studyId)
            .bind("createdAt", createdAt)
            .bind("status", if (score == null) "ungraded" else "graded")
        statement = if (answeredAt == null) {
            statement.bindNull("answeredAt", Instant::class.java)
        } else {
            statement.bind("answeredAt", answeredAt)
        }
        statement = if (score == null) {
            statement.bindNull("score", Integer::class.java)
        } else {
            statement.bind("score", score)
        }
        statement.fetch().rowsUpdated().awaitSingle()
    }
}
