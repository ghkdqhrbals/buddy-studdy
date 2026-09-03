package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorSummaryRecoveryPolicyTest {
    private val now = Instant.parse("2026-08-31T10:00:00Z")

    @Test
    fun `transport settlement never becomes a learning result failure`() {
        VoiceTutorResultStatus.entries.forEach { status ->
            assertThat(voiceTutorResultStatusAfterSettlement(status)).isEqualTo(status)
        }
    }

    @Test
    fun `settlement cannot erase a claimed completed or genuinely failed summary`() {
        listOf(VoiceTutorResultStatus.PROCESSING, VoiceTutorResultStatus.COMPLETED, VoiceTutorResultStatus.FAILED)
            .forEach { status ->
                assertThat(voiceTutorResultStatusAfterSettlement(status)).isEqualTo(status)
            }
    }

    @Test
    fun `normally ended and failed calls can claim a terminal learning result`() {
        assertThat(claim(session(), null)).isTrue()
        assertThat(claim(session(VoiceTutorSessionStatus.FAILED), null)).isTrue()
    }

    @Test
    fun `unfinalized and active sessions cannot claim a summary`() {
        assertThat(claim(session().copy(finalizedAt = null), null)).isFalse()
        listOf(VoiceTutorSessionStatus.READY, VoiceTutorSessionStatus.ACTIVE, VoiceTutorSessionStatus.ENDING)
            .forEach { assertThat(claim(session(it), null)).isFalse() }
    }

    @Test
    fun `only the exact historical unattempted call failure is recoverable`() {
        val failedSession = session(VoiceTutorSessionStatus.FAILED, VoiceTutorResultStatus.FAILED)
        val failedResult = result(VoiceTutorResultStatus.FAILED, error = CALL_FAILURE)
        assertThat(claim(failedSession, failedResult)).isTrue()
        assertThat(claim(failedSession, failedResult.copy(model = "gpt-test"))).isFalse()
        assertThat(claim(failedSession, failedResult.copy(errorMessage = "$CALL_FAILURE "))).isFalse()
        assertThat(claim(failedSession, failedResult.copy(errorMessage = CALL_FAILURE.lowercase()))).isFalse()
        assertThat(claim(failedSession.copy(failureMessage = null), failedResult.copy(errorMessage = null))).isFalse()
        assertThat(claim(failedSession.copy(status = VoiceTutorSessionStatus.COMPLETED), failedResult)).isFalse()
    }

    @Test
    fun `a scheduler with a stale pending list cannot retry a real summary failure`() {
        val result = result(VoiceTutorResultStatus.FAILED, error = "Voice Tutor summary generation failed.")
        for (status in listOf(VoiceTutorSessionStatus.COMPLETED, VoiceTutorSessionStatus.FAILED)) {
            assertThat(claim(session(status, VoiceTutorResultStatus.FAILED), result)).isFalse()
            assertThat(claim(session(status), result)).isFalse()
        }
    }

    @Test
    fun `fresh processing leases reject concurrent claim and expired leases recover exactly at boundary`() {
        val session = session(VoiceTutorSessionStatus.FAILED, VoiceTutorResultStatus.PROCESSING)
        val processing = result(VoiceTutorResultStatus.PROCESSING).copy(updatedAt = now.minusSeconds(300))
        assertThat(voiceTutorSummaryCanBeClaimed(session, processing, now.minusNanos(1), 300)).isFalse()
        assertThat(voiceTutorSummaryCanBeClaimed(session, processing, now, 300)).isTrue()
        assertThat(voiceTutorSummaryCanBeClaimed(session, processing.copy(updatedAt = now), now, 300)).isFalse()
    }

    @Test
    fun `minimum lease and matching processing statuses are both required`() {
        val processing = result(VoiceTutorResultStatus.PROCESSING).copy(updatedAt = now.minusSeconds(29))
        assertThat(voiceTutorSummaryCanBeClaimed(session(resultStatus = VoiceTutorResultStatus.PROCESSING), processing, now, 1))
            .isFalse()
        assertThat(claim(session(), processing.copy(updatedAt = now.minusSeconds(600)))).isFalse()
    }

    @Test
    fun `completed rows or completed session projections always reject replay claims`() {
        val processing = result(VoiceTutorResultStatus.PROCESSING).copy(updatedAt = now.minusSeconds(600))
        assertThat(claim(session(resultStatus = VoiceTutorResultStatus.COMPLETED), processing)).isFalse()
        assertThat(claim(session(resultStatus = VoiceTutorResultStatus.PROCESSING), result(VoiceTutorResultStatus.COMPLETED))).isFalse()
        assertThat(claim(session(), result(VoiceTutorResultStatus.COMPLETED))).isFalse()
        assertThat(claim(session(resultStatus = VoiceTutorResultStatus.FAILED), null)).isFalse()
    }

    private fun claim(session: VoiceTutorSession, result: VoiceTutorResult?) =
        voiceTutorSummaryCanBeClaimed(session, result, now, 300)

    private fun result(status: VoiceTutorResultStatus, error: String? = null) = VoiceTutorResult(
        sessionId = "summary-policy-session", status = status, summaryMarkdown = null,
        strengths = emptyList(), improvements = emptyList(), nextSteps = emptyList(), model = null,
        promptVersion = "voice-summary-v1", errorMessage = error, createdAt = now, updatedAt = now,
    )

    private fun session(
        status: VoiceTutorSessionStatus = VoiceTutorSessionStatus.COMPLETED,
        resultStatus: VoiceTutorResultStatus = VoiceTutorResultStatus.PENDING,
    ) = VoiceTutorSession(
        id = "summary-policy-session", userId = 7, studyId = 42, idempotencyKey = "summary-policy-attempt",
        providerSessionId = null, status = status, resultStatus = resultStatus, language = "ko",
        model = "gpt-realtime-test", voice = "marin", topic = "Synthetic topic", difficulty = 3,
        periodStartedAt = now.minusSeconds(86_400), periodEndsAt = now.plusSeconds(86_400),
        reservedSeconds = 3_600, chargedSeconds = 109, maxSessionSeconds = 3_600,
        hardEndsAt = now.plusSeconds(3_600), connectedAt = now.minusSeconds(109), relayHeartbeatAt = now,
        acceptedAudioBytes = 0, endedAt = now, finalizedAt = now, endReason = "PROVIDER_ERROR",
        failureCode = if (status == VoiceTutorSessionStatus.FAILED) "REALTIME_RELAY_FAILED" else null,
        failureMessage = if (status == VoiceTutorSessionStatus.FAILED) CALL_FAILURE else null,
        createdAt = now.minusSeconds(110), updatedAt = now,
    )

    private companion object {
        const val CALL_FAILURE = "Realtime provider connection failed."
    }
}
