package com.buddystudy.backend

import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
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
    @Autowired lateinit var webRtcCleanup: VoiceTutorWebRtcCleanupPort
    @Autowired lateinit var recordings: VoiceTutorRecordingPersistencePort
    @Autowired lateinit var admin: AdminManagementPort
    @Autowired lateinit var database: DatabaseClient

    @Test
    fun `withdrawal prefix tombstone survives without a user and remains after its deadline`() = runBlocking<Unit> {
        val nonexistentUserId = 8_000_000_007L
        val now = Instant.parse("2031-08-30T00:00:00Z")
        val initialCleanupUntil = now.plusSeconds(120)
        val cleanupUntil = now.plusSeconds(180)
        database.sql("delete from voice_tutor_recording_prefix_cleanups where user_id = :userId")
            .bind("userId", nonexistentUserId).fetch().rowsUpdated().awaitSingle()

        recordings.schedulePrefixCleanup(nonexistentUserId, initialCleanupUntil, now)
        recordings.schedulePrefixCleanup(nonexistentUserId, cleanupUntil, now.plusSeconds(1))
        val scheduled = recordings.pendingPrefixCleanups(now, 1_000).single { it.userId == nonexistentUserId }
        val deferred = recordings.deferPrefixCleanup(
            userId = nonexistentUserId,
            expectedCleanupUntil = cleanupUntil,
            attemptedAt = now,
            nextAttemptAt = now.plusSeconds(60),
            failureMessage = "S3Exception",
        )
        val dueBeforeRetry = recordings.pendingPrefixCleanups(now.plusSeconds(59), 1_000)
            .any { it.userId == nonexistentUserId }
        val dueAtRetry = recordings.pendingPrefixCleanups(now.plusSeconds(60), 1_000)
            .any { it.userId == nonexistentUserId }
        val deferredForever = recordings.deferPrefixCleanup(
            userId = nonexistentUserId,
            expectedCleanupUntil = cleanupUntil,
            attemptedAt = cleanupUntil,
            nextAttemptAt = cleanupUntil.plusSeconds(86_400),
            failureMessage = null,
        )
        val dueBeforeDailyRetry = recordings.pendingPrefixCleanups(cleanupUntil.plusSeconds(86_399), 1_000)
            .any { it.userId == nonexistentUserId }
        val dueAtDailyRetry = recordings.pendingPrefixCleanups(cleanupUntil.plusSeconds(86_400), 1_000)
            .any { it.userId == nonexistentUserId }

        assertThat(scheduled.cleanupUntil).isEqualTo(cleanupUntil)
        assertThat(deferred).isTrue()
        assertThat(dueBeforeRetry).isFalse()
        assertThat(dueAtRetry).isTrue()
        assertThat(deferredForever).isTrue()
        assertThat(dueBeforeDailyRetry).isFalse()
        assertThat(dueAtDailyRetry).isTrue()

        database.sql("delete from voice_tutor_recording_prefix_cleanups where user_id = :userId")
            .bind("userId", nonexistentUserId).fetch().rowsUpdated().awaitSingle()
    }

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
            recordingConsentedAt = now,
            recordingConsentVersion = "voice-recording-v1",
        ) as ReserveVoiceTutorSessionResult.Reserved
        assertThat(reserved.value.session.reservedSeconds).isEqualTo(3_600)
        assertThat(reserved.value.quota.remainingSeconds).isEqualTo(14_400)
        assertThat(reserved.value.session.recordingConsentedAt).isEqualTo(now)
        assertThat(reserved.value.session.recordingConsentVersion).isEqualTo("voice-recording-v1")

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
        assertThat(quota.remainingSeconds).isEqualTo(3_597)

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
    fun `terminal WebRTC calls remain selectable until successful cleanup clears only their rtc marker`() = runBlocking<Unit> {
        val now = Instant.parse("2031-08-30T00:00:00Z")
        val fixture = paidUser(now.minusSeconds(86_400))
        val rtc = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            "rtc-cleanup-${fixture.suffix}",
            "ko",
            "gpt-realtime-2.1",
            "marin",
            300,
            now,
        ) as ReserveVoiceTutorSessionResult.Reserved
        voiceTutor.markActive(fixture.userId, rtc.value.session.id, now.plusSeconds(1))
        val rtcCallId = "rtc_${fixture.suffix.replace("-", "")}"
        assertThat(
            voiceTutor.attachProviderSession(fixture.userId, rtc.value.session.id, rtcCallId, now.plusSeconds(2)),
        ).isTrue()
        voiceTutor.finalize(fixture.userId, rtc.value.session.id, "USER_ENDED", false, null, now.plusSeconds(3))

        val legacy = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            "legacy-cleanup-${fixture.suffix}",
            "ko",
            "gpt-realtime-2.1",
            "marin",
            300,
            now.plusSeconds(4),
        ) as ReserveVoiceTutorSessionResult.Reserved
        voiceTutor.markActive(fixture.userId, legacy.value.session.id, now.plusSeconds(5))
        val legacyId = "provider-${fixture.suffix}"
        assertThat(
            voiceTutor.attachProviderSession(fixture.userId, legacy.value.session.id, legacyId, now.plusSeconds(6)),
        ).isTrue()
        voiceTutor.finalize(fixture.userId, legacy.value.session.id, "USER_ENDED", false, null, now.plusSeconds(7))

        val awaiting = voiceTutor.terminalWebRtcSessionsAwaitingHangup(1)
        assertThat(awaiting).extracting<String> { it.providerSessionId }.containsExactly(rtcCallId)
        assertThat(
            voiceTutor.clearWebRtcProviderSession(
                fixture.userId,
                rtc.value.session.id,
                rtcCallId,
                now.plusSeconds(8),
            ),
        ).isTrue()
        assertThat(
            voiceTutor.clearWebRtcProviderSession(
                fixture.userId,
                legacy.value.session.id,
                legacyId,
                now.plusSeconds(8),
            ),
        ).isFalse()
        assertThat(voiceTutor.findSession(fixture.userId, rtc.value.session.id)!!.providerSessionId).isNull()
        assertThat(voiceTutor.findSession(fixture.userId, legacy.value.session.id)!!.providerSessionId).isEqualTo(legacyId)
        assertThat(voiceTutor.terminalWebRtcSessionsAwaitingHangup(10)).isEmpty()
    }

    @Test
    fun `Voice Tutor idempotency key rejects immutable request mismatch without comparing cap or consent time`() = runBlocking<Unit> {
        val now = Instant.parse("2031-08-30T00:00:00Z")
        val fixture = paidUser(now.minusSeconds(86_400))
        val key = "immutable-${fixture.suffix}"
        val original = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            key,
            "ko",
            "gpt-realtime-2.1",
            "marin",
            300,
            now,
            recordingConsentedAt = now,
            recordingConsentVersion = "voice-recording-v1",
        )
        assertThat(original).isInstanceOf(ReserveVoiceTutorSessionResult.Reserved::class.java)

        val sameImmutableRequest = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            key,
            "ko",
            "gpt-realtime-2.1",
            "marin",
            600,
            now.plusSeconds(30),
            recordingConsentedAt = now.plusSeconds(30),
            recordingConsentVersion = "voice-recording-v1",
        )
        assertThat(sameImmutableRequest).isInstanceOf(ReserveVoiceTutorSessionResult.Reserved::class.java)

        val conflicts = listOf(
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId + 1,
                key,
                "ko",
                "gpt-realtime-2.1",
                "marin",
                300,
                now.plusSeconds(31),
                now.plusSeconds(31),
                "voice-recording-v1",
            ),
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                key,
                "en",
                "gpt-realtime-2.1",
                "marin",
                300,
                now.plusSeconds(32),
                now.plusSeconds(32),
                "voice-recording-v1",
            ),
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                key,
                "ko",
                "gpt-realtime-other",
                "marin",
                300,
                now.plusSeconds(33),
                now.plusSeconds(33),
                "voice-recording-v1",
            ),
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                key,
                "ko",
                "gpt-realtime-2.1",
                "cedar",
                300,
                now.plusSeconds(34),
                now.plusSeconds(34),
                "voice-recording-v1",
            ),
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                key,
                "ko",
                "gpt-realtime-2.1",
                "marin",
                300,
                now.plusSeconds(35),
                null,
                null,
            ),
            voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                key,
                "ko",
                "gpt-realtime-2.1",
                "marin",
                300,
                now.plusSeconds(36),
                now.plusSeconds(36),
                "voice-recording-v2",
            ),
        )
        assertThat(conflicts).allSatisfy {
            assertThat(it).isEqualTo(ReserveVoiceTutorSessionResult.IdempotencyConflict)
        }
        assertThat(voiceTutor.quota(fixture.userId, now.plusSeconds(37))!!.reservedSeconds).isEqualTo(300)
    }

    @Test
    fun `FK-free cleanup marker is protected while attached and claimable after its session row disappears`() = runBlocking<Unit> {
        val now = Instant.parse("2031-08-30T00:00:00Z")
        val fixture = paidUser(now.minusSeconds(86_400))
        val reserved = voiceTutor.reserve(
            fixture.userId,
            fixture.studyId,
            "marker-cleanup-${fixture.suffix}",
            "ko",
            "gpt-realtime-2.1",
            "marin",
            300,
            now,
        ) as ReserveVoiceTutorSessionResult.Reserved
        voiceTutor.markActive(fixture.userId, reserved.value.session.id, now.plusSeconds(1))
        val callId = "rtc_marker${fixture.suffix.replace("-", "")}"
        webRtcCleanup.recordPending(
            callId,
            fixture.userId,
            reserved.value.session.id,
            recoverAfter = now,
            now = now,
        )
        assertThat(
            webRtcCleanup.attachSession(callId, fixture.userId, reserved.value.session.id, now.plusSeconds(2)),
        ).isTrue()

        assertThat(webRtcCleanup.claimOrphaned(10, now.plusSeconds(3), 60)).isEmpty()

        database.sql("delete from voice_tutor_sessions where id = :sessionId")
            .bind("sessionId", reserved.value.session.id)
            .fetch().rowsUpdated().awaitSingle()

        val claimed = webRtcCleanup.claimOrphaned(10, now.plusSeconds(4), 60)
        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().callId).isEqualTo(callId)
        assertThat(claimed.single().userId).isEqualTo(fixture.userId)
        assertThat(webRtcCleanup.completeClaim(callId, claimed.single().claimToken)).isTrue()
        assertThat(webRtcCleanup.claimOrphaned(10, now.plusSeconds(5), 60)).isEmpty()
    }

    @Test
    fun `persistent user voice cap can lower a paid tier and null restores its default`() = runBlocking<Unit> {
        val now = Instant.now()
        val fixture = paidUser(now.minusSeconds(86_400))

        val capped = admin.setVoiceLimit(fixture.userId, 900)!!
        assertThat(capped.tierMonthlyVoiceSecondsLimit).isEqualTo(3_600)
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
        assertThat(restored.monthlyVoiceSecondsLimit).isEqualTo(3_600)
        assertThat(voiceTutor.quota(fixture.userId, now.plusMillis(1))!!.baseSeconds).isEqualTo(3_600)
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
        assertThat(materialized.tierMonthlyVoiceSecondsLimit).isEqualTo(3_600)

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
