package com.buddystudy.backend

import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
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
class VoiceTutorPersistenceIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var voiceTutor: VoiceTutorPersistencePort
    @Autowired lateinit var admin: AdminManagementPort
    @Autowired lateinit var database: DatabaseClient

    @Test
    fun `reservation and audio-aware finalization settle quota exactly once`() = runBlocking<Unit> {
        val now = Instant.parse("2031-08-30T00:00:00Z")
        val fixture = paidUser(now.minusSeconds(86_400))

        val reserved = voiceTutor.reserve(
            userId = fixture.userId,
            studyId = fixture.studyId,
            idempotencyKey = "voice-${fixture.suffix}",
            language = "ko",
            model = "gpt-realtime-2.1",
            voice = "marin",
            maxSessionSeconds = 3_600,
            now = now,
        ) as ReserveVoiceTutorSessionResult.Reserved
        assertThat(reserved.value.session.reservedSeconds).isEqualTo(3_600)
        assertThat(reserved.value.quota.remainingSeconds).isEqualTo(14_400)

        val active = voiceTutor.markActive(fixture.userId, reserved.value.session.id, now.plusSeconds(1))!!
        assertThat(active.connectedAt).isNull()
        assertThat(
            voiceTutor.attachProviderSession(
                fixture.userId,
                active.id,
                "provider-${fixture.suffix}",
                now.plusSeconds(2),
            ),
        ).isTrue()
        assertThat(voiceTutor.addAcceptedAudioBytes(fixture.userId, active.id, 96_001)).isTrue()

        val finalized = voiceTutor.finalize(
            fixture.userId,
            active.id,
            "USER_ENDED",
            failed = false,
            failureMessage = null,
            now = now.plusSeconds(2).plusMillis(1),
        )!!
        assertThat(finalized.chargedSeconds).isEqualTo(3)
        val quota = voiceTutor.quota(fixture.userId, now.plusSeconds(3))!!
        assertThat(quota.usedSeconds).isEqualTo(3)
        assertThat(quota.reservedSeconds).isZero()
        assertThat(quota.remainingSeconds).isEqualTo(17_997)

        val retried = voiceTutor.finalize(
            fixture.userId,
            active.id,
            "DUPLICATE_RETRY",
            failed = true,
            failureMessage = "must not overwrite",
            now = now.plusSeconds(30),
        )!!
        assertThat(retried.chargedSeconds).isEqualTo(3)
        assertThat(retried.endReason).isEqualTo("USER_ENDED")
        val afterRetry = voiceTutor.quota(fixture.userId, now.plusSeconds(31))!!
        assertThat(afterRetry.usedSeconds).isEqualTo(3)
        assertThat(afterRetry.reservedSeconds).isZero()
    }

    @Test
    fun `persistent user voice cap can lower a paid tier and null restores its default`() = runBlocking<Unit> {
        val now = Instant.now()
        val fixture = paidUser(now.minusSeconds(86_400))

        val capped = admin.setVoiceLimit(fixture.userId, 900)!!
        assertThat(capped.tierMonthlyVoiceSecondsLimit).isEqualTo(18_000)
        assertThat(capped.monthlyVoiceSecondsLimitOverride).isEqualTo(900)
        assertThat(capped.monthlyVoiceSecondsLimit).isEqualTo(900)
        assertThat(voiceTutor.quota(fixture.userId, now)!!.baseSeconds).isEqualTo(900)

        database.sql(
            "update user_voice_quota set used_seconds = 400, reserved_seconds = 250 where user_id = :userId",
        ).bind("userId", fixture.userId).fetch().rowsUpdated().awaitSingle()
        val lowered = admin.setVoiceLimit(fixture.userId, 300)!!
        assertThat(lowered.monthlyVoiceSecondsLimit).isEqualTo(300)
        assertThat(lowered.monthlyVoiceSecondsLimitOverride).isEqualTo(300)
        assertThat(lowered.voiceUsedSeconds).isEqualTo(400)
        assertThat(lowered.voiceReservedSeconds).isEqualTo(250)
        assertThat(lowered.voiceRemainingSeconds).isZero()

        database.sql(
            """
            update user_voice_quota
            set period_started_at = :periodStartedAt,
                period_ends_at = :periodEndsAt,
                used_seconds = 400,
                reserved_seconds = 0
            where user_id = :userId
            """.trimIndent(),
        ).bind("periodStartedAt", now.minusSeconds(3_456_000))
            .bind("periodEndsAt", now.minusSeconds(1))
            .bind("userId", fixture.userId)
            .fetch().rowsUpdated().awaitSingle()

        val rolledOver = voiceTutor.quota(fixture.userId, now)!!
        assertThat(rolledOver.baseSeconds).isEqualTo(300)
        assertThat(rolledOver.usedSeconds).isZero()
        assertThat(rolledOver.reservedSeconds).isZero()

        val restored = admin.setVoiceLimit(fixture.userId, null)!!
        assertThat(restored.monthlyVoiceSecondsLimitOverride).isNull()
        assertThat(restored.monthlyVoiceSecondsLimit).isEqualTo(18_000)
        assertThat(voiceTutor.quota(fixture.userId, now.plusMillis(1))!!.baseSeconds).isEqualTo(18_000)
    }

    @Test
    fun `positive free-tier override never grants Voice Tutor entitlement`() = runBlocking<Unit> {
        val now = Instant.now()
        val fixture = userWithTier("TIER1", now.minusSeconds(86_400))
        admin.setVoiceLimit(fixture.userId, 900)

        val quota = voiceTutor.quota(fixture.userId, now)!!
        val reserve = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            "free-override-${fixture.suffix}",
            "ko",
            "gpt-realtime-2.1",
            "marin",
            3_600,
            now,
        )

        assertThat(quota.planEligible).isFalse()
        assertThat(quota.baseSeconds).isEqualTo(900)
        assertThat(reserve).isInstanceOf(ReserveVoiceTutorSessionResult.NotEligible::class.java)
    }

    @Test
    fun `zero paid-tier override remains entitled but has no allowance`() = runBlocking<Unit> {
        val now = Instant.now()
        val fixture = paidUser(now.minusSeconds(86_400))
        admin.setVoiceLimit(fixture.userId, 0)

        val quota = voiceTutor.quota(fixture.userId, now)!!
        val reserve = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            "paid-zero-${fixture.suffix}",
            "ko",
            "gpt-realtime-2.1",
            "marin",
            3_600,
            now,
        )

        assertThat(quota.planEligible).isTrue()
        assertThat(quota.baseSeconds).isZero()
        assertThat(reserve).isInstanceOf(ReserveVoiceTutorSessionResult.Exhausted::class.java)
    }

    @Test
    fun `expired paid membership cannot keep advertising a stale paid voice tier`() = runBlocking<Unit> {
        val now = Instant.now()
        val fixture = paidUser(now.minusSeconds(86_400))
        val materialized = admin.setVoiceLimit(fixture.userId, null)!!
        assertThat(materialized.tierMonthlyVoiceSecondsLimit).isEqualTo(18_000)

        database.sql(
            "update user_memberships set status = 'INACTIVE', updated_at = :now where user_id = :userId",
        ).bind("now", now).bind("userId", fixture.userId).fetch().rowsUpdated().awaitSingle()

        val staleTier = database.sql(
            "select tier_code from user_voice_quota where user_id = :userId",
        ).bind("userId", fixture.userId)
            .map { row, _ -> row.get("tier_code", String::class.java)!! }
            .one().awaitSingle()
        val adminSnapshot = admin.user(fixture.userId)!!

        assertThat(staleTier).isEqualTo("TIER2")
        assertThat(adminSnapshot.tierMonthlyVoiceSecondsLimit).isZero()
        assertThat(adminSnapshot.monthlyVoiceSecondsLimit).isZero()

        val reconciled = voiceTutor.quota(fixture.userId, now.plusMillis(1))!!
        assertThat(reconciled.tierCode).isEqualTo("TIER1")
        assertThat(reconciled.planEligible).isFalse()
        assertThat(reconciled.baseSeconds).isZero()
    }

    private suspend fun paidUser(createdAt: Instant): Fixture = userWithTier("TIER2", createdAt)

    private suspend fun userWithTier(tierCode: String, createdAt: Instant): Fixture {
        val suffix = UUID.randomUUID().toString()
        val userId = database.sql(
            """
            insert into users (
                provider, provider_id, password_hash, status, email, display_name, created_at, updated_at
            ) values (
                'EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).bind("providerId", "voice-$suffix").bind("email", "$suffix@example.com")
            .bind("displayName", "Voice-$suffix").bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        database.sql(
            """
            insert into user_memberships (
                user_id, tier, monthly_question_limit_override, status, source,
                started_at, expires_at, created_at, updated_at
            ) values (
                :userId, :tierCode, null, 'ACTIVE', 'ADMIN',
                :createdAt, null, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).bind("userId", userId).bind("tierCode", tierCode).bind("createdAt", createdAt)
            .fetch().rowsUpdated().awaitSingle()
        val studyId = database.sql(
            """
            insert into studies (
                device_id, user_id, parent_study_id, sort_order, topic, difficulty_level,
                interval_minutes, enabled, active_for_questions, notification_sound,
                custom_prompt, openai_model, max_history_count, created_at, updated_at
            ) values (
                :deviceId, :userId, null, 0, 'Voice Tutor integration', 5,
                60, true, true, null, '', 'gpt-5.4', 10, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).bind("deviceId", "voice-device-$suffix").bind("userId", userId).bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return Fixture(suffix, userId, studyId)
    }

    private data class Fixture(val suffix: String, val userId: Long, val studyId: Long)
}
