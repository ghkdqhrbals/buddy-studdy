package com.buddystudy.backend

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.community.application.port.inbound.TopicSubscriptionUseCase
import com.buddystudy.backend.common.application.error.ApiException
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    @Autowired lateinit var topicSubscriptions: TopicSubscriptionUseCase

    @Test
    fun `voice tutor read permission remains active-account-only after runtime seed reconciliation`(): Unit = runBlocking {
        assertThat(
            longValue("select requires_active_account from permissions where code = 'voice-tutor:read'"),
        ).isEqualTo(1)
        assertThat(
            longValue(
                """
                select count(*)
                from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code = 'voice-tutor:read' and r.code in ('REGISTERED_USER', 'ADMIN')
                """.trimIndent(),
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `withdrawal revokes access and async cleanup is idempotent without deleting new device data`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val deviceId = "withdrawal-device-$suffix"
        val withdrawnAt = Instant.parse("2032-07-27T00:00:00Z")
        val userId = insertUser(suffix, withdrawnAt.minusSeconds(60))
        val peerUserId = insertUser("$suffix-peer", withdrawnAt.minusSeconds(60))
        val appAccountToken = UUID.randomUUID().toString().lowercase()
        insertBillingAccount(userId, appAccountToken, withdrawnAt.minusSeconds(60))
        insertDevice(deviceId, userId, withdrawnAt.minusSeconds(60))
        insertSession(deviceId, userId, withdrawnAt.minusSeconds(60))
        insertNotification("old-$suffix", deviceId, withdrawnAt.minusSeconds(1))
        insertUserBlock(userId, peerUserId, withdrawnAt.minusSeconds(30))
        insertUserBlock(peerUserId, userId, withdrawnAt.minusSeconds(20))
        val voiceSessionId = insertVoiceTutorData(userId, withdrawnAt.minusSeconds(30))

        client.sql("insert into user_topic_subscriptions (user_id, topic_key, topic, sort_order) values (:userId, 'swift', 'Swift', 0)")
            .bind("userId", userId).fetch().rowsUpdated().awaitSingle()

        val snapshot = accountDeletion.beginWithdrawal(userId, withdrawnAt)
        assertThat(longValue("select count(*) from user_topic_subscriptions where user_id = $userId")).isZero()

        assertThat(snapshot.deviceIds).containsExactly(deviceId)
        assertThat(stringValue("select status from users where id = $userId")).isEqualTo("WITHDRAWN")
        assertThat(longValue("select count(*) from devices where device_id = '$deviceId' and user_id is null")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_devices where user_id = $userId and revoked_at is not null")).isEqualTo(1)

        insertNotification("new-$suffix", deviceId, withdrawnAt.plusSeconds(1))
        accountDeletion.deleteAccountData(userId, snapshot.deviceIds, withdrawnAt)
        accountDeletion.deleteAccountData(userId, snapshot.deviceIds, withdrawnAt)

        assertThat(longValue("select count(*) from users where id = $userId")).isZero()
        assertThat(longValue("select count(*) from users where id = $peerUserId")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_blocks where blocker_user_id = $userId or blocked_user_id = $userId")).isZero()
        assertThat(longValue("select count(*) from billing_accounts where app_account_token = '$appAccountToken' and user_id is null and status = 'ANONYMIZED'")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_devices where user_id = $userId")).isZero()
        assertThat(longValue("select count(*) from app_notifications where event_id = 'old-$suffix'")).isZero()
        assertThat(longValue("select count(*) from app_notifications where event_id = 'new-$suffix'")).isEqualTo(1)
        assertThat(longValue("select count(*) from user_voice_quota where user_id = $userId")).isZero()
        assertThat(longValue("select count(*) from voice_tutor_sessions where id = '$voiceSessionId'")).isZero()
        assertThat(longValue("select count(*) from voice_tutor_recordings where session_id = '$voiceSessionId'")).isZero()
        assertThat(longValue("select count(*) from voice_tutor_transcript_turns where session_id = '$voiceSessionId'")).isZero()
        assertThat(longValue("select count(*) from voice_tutor_results where session_id = '$voiceSessionId'")).isZero()
    }

    @Test
    fun `an authorized request delayed until after withdrawal cannot recreate private interests`(): Unit = runBlocking {
        val userId = insertUser(UUID.randomUUID().toString(), Instant.now())
        val stalePrincipal = Principal(userId, "withdrawal-subscription", 1, false)
        topicSubscriptions.replace(stalePrincipal, listOf("Swift"))
        accountDeletion.beginWithdrawal(userId, Instant.now())
        assertThatThrownBy { runBlocking { topicSubscriptions.replace(stalePrincipal, listOf("Kotlin")) } }
            .isInstanceOf(ApiException::class.java)
            .hasMessage("An active account is required.")
        assertThat(longValue("select count(*) from user_topic_subscriptions where user_id = $userId")).isZero()
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

    private suspend fun insertUserBlock(blockerUserId: Long, blockedUserId: Long, createdAt: Instant) {
        client.sql(
            """
            insert into user_blocks (blocker_user_id, blocked_user_id, created_at)
            values (:blockerUserId, :blockedUserId, :createdAt)
            """.trimIndent(),
        )
            .bind("blockerUserId", blockerUserId)
            .bind("blockedUserId", blockedUserId)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun insertVoiceTutorData(userId: Long, createdAt: Instant): String {
        val sessionId = UUID.randomUUID().toString()
        client.sql(
            """
            insert into user_voice_quota (
                user_id, tier_code, anchor_at, period_started_at, period_ends_at,
                base_seconds, used_seconds, reserved_seconds, version, created_at, updated_at
            ) values (
                :userId, 'TIER2', :createdAt, :createdAt, :periodEndsAt,
                18000, 60, 0, 1, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).bind("userId", userId).bind("createdAt", createdAt).bind("periodEndsAt", createdAt.plusSeconds(31L * 86_400))
            .fetch().rowsUpdated().awaitSingle()
        client.sql(
            """
            insert into voice_tutor_sessions (
                id, user_id, study_id, idempotency_key, provider_session_id,
                status, result_status, language, model, voice, topic_snapshot, difficulty_snapshot,
                period_started_at, period_ends_at, reserved_seconds, charged_seconds,
                max_session_seconds, hard_ends_at, connected_at, ended_at, finalized_at,
                finalization_key, end_reason, recording_consented_at, recording_consent_version,
                created_at, updated_at
            ) values (
                :id, :userId, null, :idempotencyKey, :providerSessionId,
                'COMPLETED', 'COMPLETED', 'ko', 'gpt-realtime-2.1', 'marin', 'Private topic', 5,
                :createdAt, :periodEndsAt, 60, 60,
                60, :endedAt, :createdAt, :endedAt, :endedAt,
                :finalizationKey, 'USER_ENDED', :createdAt, 'voice-recording-v1',
                :createdAt, :endedAt
            )
            """.trimIndent(),
        ).bind("id", sessionId).bind("userId", userId)
            .bind("idempotencyKey", "withdrawal-voice-$sessionId")
            .bind("providerSessionId", "provider-$sessionId")
            .bind("createdAt", createdAt).bind("endedAt", createdAt.plusSeconds(60))
            .bind("periodEndsAt", createdAt.plusSeconds(31L * 86_400))
            .bind("finalizationKey", "voice-session:$sessionId:finalize")
            .fetch().rowsUpdated().awaitSingle()
        client.sql(
            """
            insert into voice_tutor_recordings (
                session_id, user_id, object_key, status, content_type,
                expected_bytes, actual_bytes, sha256_hex, duration_milliseconds,
                consented_at, consent_version, upload_expires_at, retained_until,
                completed_at, created_at, updated_at
            ) values (
                :sessionId, :userId, :objectKey, 'AVAILABLE', 'audio/mp4',
                1024, 1024, :sha256, 60000,
                :createdAt, 'voice-recording-v1', :uploadExpiresAt, :retainedUntil,
                :completedAt, :createdAt, :completedAt
            )
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("userId", userId)
            .bind("objectKey", "voice-tutor-recordings/$userId/$sessionId/recording.m4a")
            .bind("sha256", "ab".repeat(32))
            .bind("createdAt", createdAt).bind("uploadExpiresAt", createdAt.plusSeconds(300))
            .bind("retainedUntil", createdAt.plusSeconds(30L * 86_400))
            .bind("completedAt", createdAt.plusSeconds(60))
            .fetch().rowsUpdated().awaitSingle()
        client.sql(
            """
            insert into voice_tutor_transcript_turns (
                session_id, provider_item_id, role, transcript, sequence_number, occurred_at, created_at
            ) values (:sessionId, 'private-item', 'USER', 'private transcript', 1, :createdAt, :createdAt)
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("createdAt", createdAt)
            .fetch().rowsUpdated().awaitSingle()
        client.sql(
            """
            insert into voice_tutor_results (
                session_id, status, summary_markdown, strengths_json, improvements_json,
                next_steps_json, model, prompt_version, created_at, updated_at
            ) values (
                :sessionId, 'COMPLETED', 'private summary', '[]', '[]', '[]',
                'gpt-5.4', 'voice-tutor-summary-v1', :createdAt, :createdAt
            )
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("createdAt", createdAt)
            .fetch().rowsUpdated().awaitSingle()
        return sessionId
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
