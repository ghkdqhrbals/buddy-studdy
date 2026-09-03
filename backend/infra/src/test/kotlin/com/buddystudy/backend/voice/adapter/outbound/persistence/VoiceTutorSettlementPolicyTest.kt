package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorSettlementPolicyTest {
    private val connectedAt = Instant.parse("2026-08-30T00:00:00Z")

    @Test
    fun `reservation is a monthly exhaustion boundary only when it owns every remaining second`() {
        assertThat(voiceTutorReservationExhaustsMonthlyQuota(3_600, 3_600)).isTrue()
        assertThat(voiceTutorReservationExhaustsMonthlyQuota(3_600, 31_536_000)).isFalse()
        assertThat(voiceTutorReservationExhaustsMonthlyQuota(30, 3_600)).isFalse()
        assertThat(voiceTutorReservationExhaustsMonthlyQuota(0, 0)).isFalse()
    }

    @Test
    fun `stale relay charges only through the last persisted server relay heartbeat`() {
        val session = session(
            relayHeartbeatAt = connectedAt.plusMillis(31_100),
            hardEndsAt = connectedAt.plusSeconds(3_600),
        )
        val recoveryAt = connectedAt.plusSeconds(900)

        val effectiveEnd = staleRelayUsageEnd(session, recoveryAt)

        assertThat(effectiveEnd).isEqualTo(connectedAt.plusMillis(31_100))
        assertThat(voiceTutorChargedSeconds(session, effectiveEnd)).isEqualTo(32)
    }

    @Test
    fun `stale relay settlement never exceeds provider hard deadline or reservation`() {
        val hardEndsAt = connectedAt.plusSeconds(3_600)
        val session = session(
            relayHeartbeatAt = hardEndsAt.plusSeconds(30),
            hardEndsAt = hardEndsAt,
        )

        val effectiveEnd = staleRelayUsageEnd(session, hardEndsAt.plusSeconds(90))

        assertThat(effectiveEnd).isEqualTo(hardEndsAt)
        assertThat(voiceTutorChargedSeconds(session, effectiveEnd)).isEqualTo(3_600)
    }

    @Test
    fun `accepted PCM cost closes short-session burst underbilling`() {
        val session = session(
            relayHeartbeatAt = connectedAt.plusMillis(1),
            hardEndsAt = connectedAt.plusSeconds(3_600),
        ).copy(acceptedAudioBytes = 48_001)

        assertThat(voiceTutorChargedSeconds(session, connectedAt.plusMillis(1))).isEqualTo(2)
    }

    @Test
    fun `stale ending session stops wall clock at the persisted end request`() {
        val requestedAt = connectedAt.plusSeconds(45)
        val session = session(
            relayHeartbeatAt = connectedAt.plusSeconds(40),
            hardEndsAt = connectedAt.plusSeconds(3_600),
        ).copy(status = VoiceTutorSessionStatus.ENDING, updatedAt = requestedAt)

        val effectiveEnd = endingSessionUsageEnd(session, connectedAt.plusSeconds(90))

        assertThat(effectiveEnd).isEqualTo(requestedAt)
        assertThat(voiceTutorChargedSeconds(session, effectiveEnd)).isEqualTo(45)
    }

    @Test
    fun `transcript persistence rejects a final turn that would cross the configured character boundary`() {
        val bounded = boundedVoiceTutorTranscript(
            transcript = "1234567890",
            capacity = TranscriptCapacity(turnCount = 8, characterCount = 96),
            maxSessionCharacters = 100,
            maxSessionTurns = 10,
        )

        assertThat(bounded).isNull()
    }

    @Test
    fun `transcript persistence rejects turns once either durable session boundary is exhausted`() {
        assertThat(
            boundedVoiceTutorTranscript("next", TranscriptCapacity(10, 50), 100, 10),
        ).isNull()
        assertThat(
            boundedVoiceTutorTranscript("next", TranscriptCapacity(2, 100), 100, 10),
        ).isNull()
    }

    private fun session(relayHeartbeatAt: Instant, hardEndsAt: Instant) = VoiceTutorSession(
        id = "00000000-0000-4000-8000-000000000001",
        userId = 1,
        studyId = 2,
        idempotencyKey = "attempt-1",
        providerSessionId = "provider-1",
        status = VoiceTutorSessionStatus.ACTIVE,
        resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko",
        model = "gpt-realtime-2.1",
        voice = "marin",
        topic = "Concurrency",
        difficulty = 5,
        periodStartedAt = connectedAt.minusSeconds(86_400),
        periodEndsAt = connectedAt.plusSeconds(86_400),
        reservedSeconds = 3_600,
        chargedSeconds = 0,
        maxSessionSeconds = 3_600,
        hardEndsAt = hardEndsAt,
        connectedAt = connectedAt,
        relayHeartbeatAt = relayHeartbeatAt,
        acceptedAudioBytes = 0,
        endedAt = null,
        finalizedAt = null,
        endReason = null,
        failureCode = null,
        failureMessage = null,
        createdAt = connectedAt.minusSeconds(1),
        updatedAt = relayHeartbeatAt,
    )
}
