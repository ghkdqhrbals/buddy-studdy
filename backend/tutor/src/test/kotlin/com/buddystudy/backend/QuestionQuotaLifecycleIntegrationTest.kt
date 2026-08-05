package com.buddystudy.backend

import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
class QuestionQuotaLifecycleIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var quota: QuestionMembershipPort
    @Autowired lateinit var database: DatabaseClient

    @Test
    fun `reservation commit and release are exactly once by correlation id`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val key = "quota-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
        assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
        assertThat(quota.quotaStatusForUser(userId, now)?.reservedCount).isEqualTo(1)

        quota.commitMonthlySystemQuestion(key, now.plusSeconds(1))
        quota.commitMonthlySystemQuestion(key, now.plusSeconds(2))
        assertThat(quota.quotaStatusForUser(userId, now)?.let { it.usedCount to it.reservedCount })
            .isEqualTo(1 to 0)

        quota.releaseMonthlySystemQuestion(userId, now, key, "rollback", now.plusSeconds(3))
        quota.releaseMonthlySystemQuestion(userId, now, key, "duplicate", now.plusSeconds(4))
        assertThat(quota.quotaStatusForUser(userId, now)?.let { it.usedCount to it.reservedCount })
            .isEqualTo(0 to 0)
        assertThat(longValue("select count(*) from quota_ledger where reservation_id = (select id from quota_reservations where reservation_key = '$key')"))
            .isEqualTo(3)
    }

    @Test
    fun `concurrent reservations never exceed the effective tier limit`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val accepted = coroutineScope {
            (1..40).map { index ->
                async(Dispatchers.Default) {
                    val key = "concurrent-$userId-$index"
                    quota.reserveMonthlySystemQuestion(userId, now, key, key, now)
                }
            }.awaitAll()
        }

        assertThat(accepted.count { it }).isEqualTo(30)
        val status = requireNotNull(quota.quotaStatusForUser(userId, now))
        assertThat(status.reservedCount).isEqualTo(30)
        assertThat(status.usedCount + status.reservedCount).isEqualTo(status.monthlyQuestionLimit)
    }

    private suspend fun insertUser(createdAt: Instant): Long {
        val suffix = UUID.randomUUID().toString()
        return database.sql(
            """
            insert into users (provider, provider_id, password_hash, status, email, display_name, created_at, updated_at)
            values ('EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName, :createdAt, :createdAt)
            """.trimIndent(),
        ).bind("providerId", "quota-$suffix").bind("email", "$suffix@example.com")
            .bind("displayName", "Quota-$suffix").bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
    }

    private suspend fun longValue(sql: String): Long = database.sql(sql)
        .map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
}
