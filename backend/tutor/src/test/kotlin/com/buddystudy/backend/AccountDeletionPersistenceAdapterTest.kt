package com.buddystudy.backend

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
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
class AccountDeletionPersistenceAdapterTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var accountDeletion: AccountDeletionPort
    @Autowired lateinit var client: DatabaseClient

    @Test
    fun `withdrawal revokes access and async cleanup is idempotent without deleting new device data`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val deviceId = "withdrawal-device-$suffix"
        val withdrawnAt = Instant.parse("2032-07-27T00:00:00Z")
        val userId = insertUser(suffix, withdrawnAt.minusSeconds(60))
        val appAccountToken = UUID.randomUUID().toString().lowercase()
        insertBillingAccount(userId, appAccountToken, withdrawnAt.minusSeconds(60))
        insertDevice(deviceId, userId, withdrawnAt.minusSeconds(60))
        insertSession(deviceId, userId, withdrawnAt.minusSeconds(60))
        insertNotification("old-$suffix", deviceId, withdrawnAt.minusSeconds(1))

        val snapshot = accountDeletion.beginWithdrawal(userId, withdrawnAt)

        assertThat(snapshot.deviceIds).containsExactly(deviceId)
        assertThat(stringValue("select status from users where id = $userId")).isEqualTo("WITHDRAWN")
        assertThat(longValue("select count(*) from devices where device_id = '$deviceId' and user_id is null")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_devices where user_id = $userId and revoked_at is not null")).isEqualTo(1)

        insertNotification("new-$suffix", deviceId, withdrawnAt.plusSeconds(1))
        accountDeletion.deleteAccountData(userId, snapshot.deviceIds, withdrawnAt)
        accountDeletion.deleteAccountData(userId, snapshot.deviceIds, withdrawnAt)

        assertThat(longValue("select count(*) from users where id = $userId")).isZero()
        assertThat(longValue("select count(*) from billing_accounts where app_account_token = '$appAccountToken' and user_id is null and status = 'ANONYMIZED'")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_devices where user_id = $userId")).isZero()
        assertThat(longValue("select count(*) from app_notifications where event_id = 'old-$suffix'")).isZero()
        assertThat(longValue("select count(*) from app_notifications where event_id = 'new-$suffix'")).isEqualTo(1)
    }

    private suspend fun insertBillingAccount(userId: Long, token: String, createdAt: Instant) {
        client.sql(
            """
            insert into billing_accounts (user_id, app_account_token, status, created_at, updated_at)
            values (:userId, :token, 'ACTIVE', :createdAt, :createdAt)
            """.trimIndent(),
        ).bind("userId", userId).bind("token", token).bind("createdAt", createdAt)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertUser(suffix: String, createdAt: Instant): Long =
        client.sql(
            """
            insert into users (
                provider, provider_id, password_hash, status, email, display_name,
                created_at, updated_at
            ) values (
                'EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName,
                :createdAt, :createdAt
            )
            """.trimIndent(),
        )
            .bind("providerId", "withdrawal-$suffix")
            .bind("email", "$suffix@example.com")
            .bind("displayName", "Withdrawal-$suffix")
            .bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun insertDevice(deviceId: String, userId: Long, createdAt: Instant) {
        client.sql(
            """
            insert into devices (
                device_id, client_secret_hash, user_id, apns_token, platform,
                apns_environment, language, timezone, created_at, updated_at, last_seen_at
            ) values (
                :deviceId, 'hash', :userId, '', 'IOS', 'SANDBOX',
                'ko', 'Asia/Seoul', :createdAt, :createdAt, :createdAt
            )
            """.trimIndent(),
        )
            .bind("deviceId", deviceId)
            .bind("userId", userId)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun insertSession(deviceId: String, userId: Long, createdAt: Instant) {
        client.sql(
            """
            insert into user_devices (
                user_id, device_id, last_seen_at, created_at, updated_at
            ) values (
                :userId, :deviceId, :createdAt, :createdAt, :createdAt
            )
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("deviceId", deviceId)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun insertNotification(eventId: String, deviceId: String, createdAt: Instant) {
        client.sql(
            """
            insert into app_notifications (
                event_id, device_id, type, title, body, created_at, updated_at
            ) values (
                :eventId, :deviceId, 'QUESTION_READY', 'Title', 'Body', :createdAt, :createdAt
            )
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("deviceId", deviceId)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun longValue(sql: String): Long =
        client.sql(sql)
            .map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun stringValue(sql: String): String =
        client.sql(sql)
            .map { row -> row.get(0, String::class.java)!! }
            .one()
            .awaitSingle()
}
