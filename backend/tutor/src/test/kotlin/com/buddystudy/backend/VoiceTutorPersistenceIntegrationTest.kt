package com.buddystudy.backend

import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.model.VoiceTutorGeneratedResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    fun `multipart answer promotion rolls back the final row and every prior link on a database failure`() =
        runBlocking<Unit> {
            val now = Instant.parse("2031-09-01T00:00:00Z")
            val fixture = paidUser(now.minusSeconds(86_400))
            val suffix = fixture.suffix.replace("-", "")
            val questionItem = "atomic-question-$suffix"
            val firstPart = "atomic-answer-1-$suffix"
            val secondPart = "atomic-answer-2-$suffix"
            val finalItem = "atomic-final-$suffix"
            val checkName = "chk_voice_answer_${suffix.take(24)}"
            val reserved = voiceTutor.reserve(
                fixture.userId, fixture.studyId, "atomic-$suffix", "ko",
                "gpt-realtime-test", "marin", 300, now,
            ) as ReserveVoiceTutorSessionResult.Reserved
            val session = voiceTutor.markActive(fixture.userId, reserved.value.session.id, now.plusSeconds(1))!!
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, session.id, questionItem, VoiceTutorTranscriptRole.TUTOR,
                "DI의 장점은 무엇인가요?", now.plusSeconds(2), 4_000, 20,
                lessonRevision = 0, isStudyQuestion = true,
            )).isTrue()
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, session.id, firstPart, VoiceTutorTranscriptRole.USER,
                "결합도를 낮추고", now.plusSeconds(3), 4_000, 20,
            )).isTrue()
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, session.id, secondPart, VoiceTutorTranscriptRole.USER,
                "테스트를 쉽게 합니다.", now.plusSeconds(4), 4_000, 20,
            )).isTrue()

            database.sql(
                "alter table voice_tutor_transcript_turns add constraint $checkName " +
                    "check (provider_item_id <> '$secondPart' or study_question_turn_id is null)",
            ).fetch().rowsUpdated().awaitSingle()
            try {
                val failure = runCatching {
                    voiceTutor.appendTranscript(
                        fixture.userId, session.id, finalItem, VoiceTutorTranscriptRole.USER,
                        "음…", now.plusSeconds(5), 4_000, 20,
                        lessonRevision = 0,
                        studyQuestionProviderItemId = questionItem,
                        studyAnswerProviderItemIds = listOf(firstPart, secondPart),
                    )
                }.exceptionOrNull()
                assertThat(failure).isNotNull()
            } finally {
                database.sql(
                    "alter table voice_tutor_transcript_turns drop check $checkName",
                ).fetch().rowsUpdated().awaitSingle()
            }

            val turns = voiceTutor.transcript(fixture.userId, session.id, 4_000)
            assertThat(turns.map { it.providerItemId }).doesNotContain(finalItem)
            assertThat(turns.filter { it.providerItemId == firstPart || it.providerItemId == secondPart })
                .allSatisfy { assertThat(it.studyQuestionTurnId).isNull() }
        }

    @Test
    fun `summary recovery claims failed transcribed sessions once and preserves completed results`() = runBlocking<Unit> {
        val endedAt = Instant.parse("2031-08-31T10:00:00Z")
        val session = failedSummarySession(endedAt, transcript = true)
        val originalQuota = voiceTutor.quota(session.userId, endedAt)!!

        assertThat(session.status).isEqualTo(VoiceTutorSessionStatus.FAILED)
        assertThat(session.resultStatus).isEqualTo(VoiceTutorResultStatus.PENDING)
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt, 300).map { it.id }).contains(session.id)
        val claims = List(2) {
            async(Dispatchers.Default) { voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt, 300) }
        }.awaitAll()
        assertThat(claims.count { it != null }).isEqualTo(1)
        val expiredClaim = checkNotNull(claims.single { it != null })
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt.plusSeconds(299), 300).map { it.id })
            .doesNotContain(session.id)
        assertThat(voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt.plusSeconds(299), 300))
            .isNull()
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt.plusSeconds(300), 300).map { it.id }).contains(session.id)
        val replacementClaim = checkNotNull(voiceTutor.beginResult(
            session.userId,
            session.id,
            "summary-test-v1",
            endedAt.plusSeconds(300),
            300,
        ))
        assertThat(replacementClaim.claimToken).isNotEqualTo(expiredClaim.claimToken)

        val generated = VoiceTutorGeneratedResult("Saved synthetic summary", emptyList(), emptyList(), listOf("Review"), "gpt-test", "summary-test-v1")
        voiceTutor.completeResult(session.userId, expiredClaim, generated.copy(summaryMarkdown = "Expired worker result"), endedAt.plusSeconds(301))
        voiceTutor.failClaimedResult(
            session.userId,
            expiredClaim,
            "summary-test-v1",
            "Expired worker failure",
            endedAt.plusSeconds(302),
        )
        assertThat(voiceTutor.result(session.userId, session.id)!!.status).isEqualTo(VoiceTutorResultStatus.PROCESSING)
        assertThat(voiceTutor.findSession(session.userId, session.id)!!.resultStatus).isEqualTo(VoiceTutorResultStatus.PROCESSING)

        voiceTutor.completeResult(session.userId, replacementClaim, generated, endedAt.plusSeconds(303))
        val completed = voiceTutor.result(session.userId, session.id)!!
        voiceTutor.completeResult(
            session.userId,
            expiredClaim,
            generated.copy(summaryMarkdown = "Late obsolete result"),
            endedAt.plusSeconds(304),
        )
        voiceTutor.failClaimedResult(
            session.userId,
            expiredClaim,
            "summary-test-v1",
            "Late failure",
            endedAt.plusSeconds(305),
        )
        voiceTutor.finalize(session.userId, session.id, "LATE_END", false, null, endedAt.plusSeconds(306))

        assertThat(voiceTutor.result(session.userId, session.id)).isEqualTo(completed)
        assertThat(voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt.plusSeconds(900), 300))
            .isNull()
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt.plusSeconds(900), 300).map { it.id }).doesNotContain(session.id)
        val preserved = voiceTutor.findSession(session.userId, session.id)!!
        assertThat(preserved.resultStatus).isEqualTo(VoiceTutorResultStatus.COMPLETED)
        assertThat(preserved.status).isEqualTo(session.status)
        assertThat(preserved.failureCode).isEqualTo(session.failureCode)
        assertThat(preserved.failureMessage).isEqualTo(session.failureMessage)
        assertThat(preserved.endReason).isEqualTo(session.endReason)
        assertThat(preserved.chargedSeconds).isEqualTo(session.chargedSeconds)
        assertThat(voiceTutor.quota(session.userId, endedAt.plusSeconds(900))!!.usedSeconds).isEqualTo(originalQuota.usedSeconds)
    }

    @Test
    fun `old binary reclaim changing only updated at fences the prior token owner`() = runBlocking<Unit> {
        val firstClaimAt = Instant.parse("2031-08-31T10:30:00.123456789Z")
        val session = failedSummarySession(firstClaimAt, transcript = true)
        val staleClaim = checkNotNull(
            voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", firstClaimAt, 300),
        )
        assertThat(staleClaim.claimedAt).isEqualTo(firstClaimAt.truncatedTo(ChronoUnit.MICROS))

        // Emulate the pre-token binary's PROCESSING reclaim exactly: it renews
        // updated_at but neither knows nor replaces V108's claim_token.
        val oldBinaryReclaimedAt = firstClaimAt.plusSeconds(300)
        val oldReclaimRows = database.sql(
            """
            update voice_tutor_results
            set status = 'PROCESSING', error_message = null,
                prompt_version = 'old-binary-v1', updated_at = :reclaimedAt
            where session_id = :sessionId and status = 'PROCESSING'
            """.trimIndent(),
        ).bind("reclaimedAt", oldBinaryReclaimedAt).bind("sessionId", session.id)
            .fetch().rowsUpdated().awaitSingle()
        assertThat(oldReclaimRows).isEqualTo(1L)
        val preservedToken = database.sql(
            "select claim_token from voice_tutor_results where session_id = :sessionId",
        ).bind("sessionId", session.id)
            .map { row, _ -> row.get("claim_token", String::class.java)!! }
            .one().awaitSingle()
        assertThat(preservedToken).isEqualTo(staleClaim.claimToken.toString())

        val generated = VoiceTutorGeneratedResult(
            "Replacement summary",
            emptyList(),
            emptyList(),
            listOf("Review"),
            "gpt-test",
            "summary-test-v1",
        )
        voiceTutor.completeResult(
            session.userId,
            staleClaim,
            generated.copy(summaryMarkdown = "Stale completion"),
            oldBinaryReclaimedAt.plusSeconds(1),
        )
        voiceTutor.failClaimedResult(
            session.userId,
            staleClaim,
            "summary-test-v1",
            "Stale failure",
            oldBinaryReclaimedAt.plusSeconds(2),
        )
        assertThat(voiceTutor.result(session.userId, session.id)!!.status)
            .isEqualTo(VoiceTutorResultStatus.PROCESSING)
        assertThat(voiceTutor.findSession(session.userId, session.id)!!.resultStatus)
            .isEqualTo(VoiceTutorResultStatus.PROCESSING)

        val replacementClaim = checkNotNull(
            voiceTutor.beginResult(
                session.userId,
                session.id,
                "summary-test-v1",
                oldBinaryReclaimedAt.plusSeconds(300),
                300,
            ),
        )
        assertThat(replacementClaim.claimToken).isNotEqualTo(staleClaim.claimToken)
        assertThat(replacementClaim.claimedAt)
            .isEqualTo(oldBinaryReclaimedAt.plusSeconds(300).truncatedTo(ChronoUnit.MICROS))

        voiceTutor.completeResult(
            session.userId,
            replacementClaim,
            generated,
            oldBinaryReclaimedAt.plusSeconds(301),
        )
        assertThat(voiceTutor.result(session.userId, session.id)!!.summaryMarkdown)
            .isEqualTo("Replacement summary")
        assertThat(voiceTutor.findSession(session.userId, session.id)!!.resultStatus)
            .isEqualTo(VoiceTutorResultStatus.COMPLETED)
    }

    @Test
    fun `summary recovery only retries the exact historical copied call failure once`() = runBlocking<Unit> {
        val endedAt = Instant.parse("2031-08-31T11:00:00Z")
        val session = failedSummarySession(endedAt, transcript = true)
        // Simulate the old settlement/service combination, only in the isolated
        // Testcontainers database. No real provider or existing user is involved.
        voiceTutor.failUnclaimedResult(session.userId, session.id, "summary-test-v1", session.failureMessage!!, endedAt)
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt, 300).map { it.id }).contains(session.id)
        val claim = voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt, 300)
        assertThat(claim).isNotNull()

        voiceTutor.failClaimedResult(
            session.userId,
            claim!!,
            "summary-test-v1",
            "Voice Tutor summary generation failed.",
            endedAt.plusSeconds(1),
        )
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt.plusSeconds(900), 300).map { it.id }).doesNotContain(session.id)
        assertThat(voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt.plusSeconds(900), 300)).isNull()
        // MySQL's case-insensitive default collation must not broaden the
        // narrowly approved legacy recovery signature.
        voiceTutor.failUnclaimedResult(
            session.userId,
            session.id,
            "summary-test-v1",
            session.failureMessage!!.lowercase(),
            endedAt.plusSeconds(901),
        )
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt.plusSeconds(902), 300).map { it.id }).doesNotContain(session.id)
        assertThat(voiceTutor.beginResult(session.userId, session.id, "summary-test-v1", endedAt.plusSeconds(902), 300)).isNull()
    }

    @Test
    fun `summary recovery never claims failed calls without saved speech or another owner's session`() = runBlocking<Unit> {
        val endedAt = Instant.parse("2031-08-31T12:00:00Z")
        val empty = failedSummarySession(endedAt, transcript = false)
        val recorded = failedSummarySession(endedAt, transcript = true)
        assertThat(empty.resultStatus).isEqualTo(VoiceTutorResultStatus.FAILED)
        assertThat(voiceTutor.sessionsAwaitingResult(100, endedAt, 300).map { it.id }).doesNotContain(empty.id)
        assertThat(voiceTutor.beginResult(empty.userId, empty.id, "summary-test-v1", endedAt, 300)).isNull()
        assertThat(voiceTutor.beginResult(empty.userId, recorded.id, "summary-test-v1", endedAt, 300)).isNull()
        assertThat(voiceTutor.findSession(recorded.userId, recorded.id)!!.resultStatus).isEqualTo(VoiceTutorResultStatus.PENDING)
    }

    private suspend fun failedSummarySession(endedAt: Instant, transcript: Boolean): VoiceTutorSession {
        val startedAt = endedAt.minusSeconds(109)
        val fixture = paidUser(startedAt.minusSeconds(86_400))
        val reserved = voiceTutor.reserve(
            fixture.userId, fixture.studyId, "summary-${fixture.suffix}", "ko", "gpt-realtime-test", "marin", 3_600, startedAt,
        ) as ReserveVoiceTutorSessionResult.Reserved
        val session = voiceTutor.markActive(fixture.userId, reserved.value.session.id, startedAt)!!
        assertThat(voiceTutor.attachProviderSession(fixture.userId, session.id, "provider-${fixture.suffix}", startedAt)).isTrue()
        if (transcript) {
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, session.id, "synthetic-item", VoiceTutorTranscriptRole.USER,
                "Synthetic study question", endedAt.minusSeconds(1), 4_000, 10,
            )).isTrue()
        }
        return voiceTutor.finalize(
            fixture.userId, session.id, "PROVIDER_ERROR", true, "Realtime provider connection failed.", endedAt,
        )!!
    }

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
        assertThat(reserved.value.quota.remainingSeconds).isZero()
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
    fun `WebRTC first attach anchors billing to provider call creation and duplicate attach never extends it`() =
        runBlocking<Unit> {
            val reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val fixture = paidUser(reservedAt.minusSeconds(86_400))
            admin.setVoiceLimit(fixture.userId, 3_600)
            val reserved = voiceTutor.reserve(
                fixture.userId,
                fixture.studyId,
                "webrtc-anchor-${fixture.suffix}",
                "ko",
                "gpt-realtime-2.1",
                "marin",
                3_600,
                reservedAt,
            ) as ReserveVoiceTutorSessionResult.Reserved
            assertThat(reserved.value.session.monthlyQuotaExhaustsAtHardEnd).isTrue()
            voiceTutor.markActive(fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(1))
            val callId = "rtc_${fixture.suffix.replace("-", "")}"
            val providerCreatedAt = reservedAt.plusSeconds(1)
            webRtcCleanup.recordPending(
                callId,
                fixture.userId,
                reserved.value.session.id,
                recoverAfter = reservedAt.plusSeconds(300),
                now = providerCreatedAt,
            )
            val attachedAt = reservedAt.plusSeconds(47)

            assertThat(webRtcCleanup.attachSession(
                callId,
                fixture.userId,
                reserved.value.session.id,
                attachedAt,
            )).isTrue()
            val attached = voiceTutor.findSession(fixture.userId, reserved.value.session.id)!!
            assertThat(attached.connectedAt).isEqualTo(providerCreatedAt)
            assertThat(attached.hardEndsAt).isEqualTo(providerCreatedAt.plusSeconds(3_600))
            assertThat(attached.monthlyQuotaExhaustsAtHardEnd).isTrue()

            assertThat(webRtcCleanup.attachSession(
                callId,
                fixture.userId,
                reserved.value.session.id,
                attachedAt.plusSeconds(120),
            )).isTrue()
            val duplicate = voiceTutor.findSession(fixture.userId, reserved.value.session.id)!!
            assertThat(duplicate.connectedAt).isEqualTo(providerCreatedAt)
            assertThat(duplicate.hardEndsAt).isEqualTo(providerCreatedAt.plusSeconds(3_600))
            assertThat(duplicate.monthlyQuotaExhaustsAtHardEnd).isTrue()
        }

    @Test
    fun `monthly exhaustion notice closes learner persistence and settles exact reserved boundary after grace`() =
        runBlocking<Unit> {
            val reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val fixture = paidUser(reservedAt.minusSeconds(86_400))
            admin.setVoiceLimit(fixture.userId, 120)
            val reserved = voiceTutor.reserve(
                fixture.userId, fixture.studyId, "quota-notice-${fixture.suffix}", "ko",
                "gpt-realtime-2.1", "marin", 3_600, reservedAt,
            ) as ReserveVoiceTutorSessionResult.Reserved
            voiceTutor.markActive(fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(1))
            val callId = "rtc_${fixture.suffix.replace("-", "")}"
            val providerCreatedAt = reservedAt.plusSeconds(1)
            webRtcCleanup.recordPending(
                callId, fixture.userId, reserved.value.session.id,
                recoverAfter = reservedAt.plusSeconds(300), now = providerCreatedAt,
            )
            assertThat(webRtcCleanup.attachSession(
                callId, fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(2),
            )).isTrue()
            val hardEnd = providerCreatedAt.plusSeconds(120)

            assertThat(voiceTutor.appendTranscript(
                fixture.userId, reserved.value.session.id, "active-untrusted", VoiceTutorTranscriptRole.USER,
                "신뢰되지 않은 경계 전 입력", hardEnd.minusSeconds(1), 4_000, 20,
            )).isFalse()
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, reserved.value.session.id, "active-at-boundary", VoiceTutorTranscriptRole.USER,
                "신뢰된 경계 입력", hardEnd, 4_000, 20, acceptedBeforeQuotaCutoff = true,
            )).isTrue()
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, reserved.value.session.id, "active-after-boundary", VoiceTutorTranscriptRole.USER,
                "신뢰 표식이 있어도 늦은 입력", hardEnd.plusMillis(1), 4_000, 20,
                acceptedBeforeQuotaCutoff = true,
            )).isFalse()

            val ending = voiceTutor.beginQuotaExhaustionNotice(
                fixture.userId,
                reserved.value.session.id,
                hardEnd,
                noticeLeadSeconds = 8,
            )!!
            assertThat(ending.status).isEqualTo(VoiceTutorSessionStatus.ENDING)
            assertThat(ending.endReason).isEqualTo("QUOTA_EXHAUSTED")
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, ending.id, "ending-at-boundary", VoiceTutorTranscriptRole.USER,
                "지연 저장된 경계 입력", hardEnd, 4_000, 20, acceptedBeforeQuotaCutoff = true,
            )).isTrue()
            assertThat(voiceTutor.appendTranscript(
                fixture.userId, ending.id, "late-learner", VoiceTutorTranscriptRole.USER,
                "경계 뒤 학습 입력", hardEnd.plusMillis(1), 4_000, 20,
                acceptedBeforeQuotaCutoff = true,
            )).isFalse()
            assertThat(voiceTutor.reconcileExpired(
                fixture.userId, hardEnd.plusSeconds(19), 30, 60,
            )).isEmpty()

            val settled = voiceTutor.reconcileExpired(
                fixture.userId, hardEnd.plusSeconds(20), 30, 60,
            ).single()
            assertThat(settled.status).isEqualTo(VoiceTutorSessionStatus.COMPLETED)
            assertThat(settled.endReason).isEqualTo("QUOTA_EXHAUSTED")
            assertThat(settled.chargedSeconds).isEqualTo(120)
            val quota = voiceTutor.quota(fixture.userId, hardEnd.plusSeconds(20))!!
            assertThat(quota.usedSeconds).isEqualTo(120)
            assertThat(quota.reservedSeconds).isZero()
            assertThat(quota.remainingSeconds).isZero()
        }

    @Test
    fun `raised monthly limit downgrades frozen exhaustion boundary to ordinary time limit`() = runBlocking<Unit> {
        val reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val fixture = paidUser(reservedAt.minusSeconds(86_400))
        admin.setVoiceLimit(fixture.userId, 120)
        val reserved = voiceTutor.reserve(
            fixture.userId, fixture.studyId, "quota-raised-${fixture.suffix}", "ko",
            "gpt-realtime-2.1", "marin", 3_600, reservedAt,
        ) as ReserveVoiceTutorSessionResult.Reserved
        voiceTutor.markActive(fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(1))
        val callId = "rtc_${fixture.suffix.replace("-", "")}"
        val providerCreatedAt = reservedAt.plusSeconds(1)
        webRtcCleanup.recordPending(
            callId, fixture.userId, reserved.value.session.id,
            recoverAfter = reservedAt.plusSeconds(300), now = providerCreatedAt,
        )
        assertThat(webRtcCleanup.attachSession(
            callId, fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(2),
        )).isTrue()
        val hardEnd = providerCreatedAt.plusSeconds(120)
        admin.setVoiceLimit(fixture.userId, 240)
        assertThat(voiceTutor.heartbeat(fixture.userId, reserved.value.session.id, hardEnd.minusSeconds(1)))
            .isEqualTo(VoiceTutorSessionStatus.ACTIVE)

        assertThat(voiceTutor.beginQuotaExhaustionNotice(
            fixture.userId, reserved.value.session.id, hardEnd, 8,
        )).isNull()
        val settled = voiceTutor.reconcileExpired(fixture.userId, hardEnd, 30, 60).single()
        assertThat(settled.endReason).isEqualTo("TIME_LIMIT")
        assertThat(settled.chargedSeconds).isEqualTo(120)
        assertThat(voiceTutor.quota(fixture.userId, hardEnd)!!.remainingSeconds).isEqualTo(120)
    }

    @Test
    fun `late recovery preserves verified monthly exhaustion reason without reopening spoken grace`() = runBlocking<Unit> {
        val reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val fixture = paidUser(reservedAt.minusSeconds(86_400))
        admin.setVoiceLimit(fixture.userId, 60)
        val reserved = voiceTutor.reserve(
            fixture.userId, fixture.studyId, "quota-late-${fixture.suffix}", "ko",
            "gpt-realtime-2.1", "marin", 3_600, reservedAt,
        ) as ReserveVoiceTutorSessionResult.Reserved
        voiceTutor.markActive(fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(1))
        val callId = "rtc_${fixture.suffix.replace("-", "")}"
        val providerCreatedAt = reservedAt.plusSeconds(1)
        webRtcCleanup.recordPending(
            callId, fixture.userId, reserved.value.session.id,
            recoverAfter = reservedAt.plusSeconds(300), now = providerCreatedAt,
        )
        assertThat(webRtcCleanup.attachSession(
            callId, fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(2),
        )).isTrue()
        val hardEnd = providerCreatedAt.plusSeconds(60)
        assertThat(voiceTutor.heartbeat(fixture.userId, reserved.value.session.id, hardEnd.minusSeconds(1)))
            .isEqualTo(VoiceTutorSessionStatus.ACTIVE)

        val settled = voiceTutor.reconcileExpired(
            fixture.userId,
            hardEnd.plusSeconds(21),
            readyTimeoutSeconds = 30,
            heartbeatLeaseSeconds = 60,
        ).single()
        assertThat(settled.status).isEqualTo(VoiceTutorSessionStatus.COMPLETED)
        assertThat(settled.endReason).isEqualTo("QUOTA_EXHAUSTED")
        assertThat(settled.chargedSeconds).isEqualTo(60)
    }

    @Test
    fun `monthly exhaustion notice idempotency cannot reopen after the absolute grace boundary`() =
        runBlocking<Unit> {
            val reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val fixture = paidUser(reservedAt.minusSeconds(86_400))
            admin.setVoiceLimit(fixture.userId, 60)
            val reserved = voiceTutor.reserve(
                fixture.userId, fixture.studyId, "quota-idempotent-grace-${fixture.suffix}", "ko",
                "gpt-realtime-2.1", "marin", 3_600, reservedAt,
            ) as ReserveVoiceTutorSessionResult.Reserved
            voiceTutor.markActive(fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(1))
            val callId = "rtc_${fixture.suffix.replace("-", "")}"
            val providerCreatedAt = reservedAt.plusSeconds(1)
            webRtcCleanup.recordPending(
                callId, fixture.userId, reserved.value.session.id,
                recoverAfter = reservedAt.plusSeconds(300), now = providerCreatedAt,
            )
            assertThat(webRtcCleanup.attachSession(
                callId, fixture.userId, reserved.value.session.id, reservedAt.plusSeconds(2),
            )).isTrue()
            val hardEnd = providerCreatedAt.plusSeconds(60)

            assertThat(voiceTutor.beginQuotaExhaustionNotice(
                fixture.userId, reserved.value.session.id, hardEnd, 8,
            )?.endReason).isEqualTo("QUOTA_EXHAUSTED")
            admin.setVoiceLimit(fixture.userId, 120)
            assertThat(voiceTutor.quota(fixture.userId, hardEnd.plusSeconds(1))!!.remainingSeconds)
                .isEqualTo(60)
            assertThat(voiceTutor.beginQuotaExhaustionNotice(
                fixture.userId, reserved.value.session.id, hardEnd.plusSeconds(19), 8,
            )?.endReason).isEqualTo("QUOTA_EXHAUSTED")
            assertThat(voiceTutor.beginQuotaExhaustionNotice(
                fixture.userId, reserved.value.session.id, hardEnd.plusSeconds(20), 8,
            )).isNull()
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
