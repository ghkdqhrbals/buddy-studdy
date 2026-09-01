package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class VoiceTutorRootStudyConfirmationsTest {
    @Test
    fun `preview is exact identity and payload bound and identical preparation is idempotent`() {
        val tickets = VoiceTutorRootStudyConfirmations(clock)

        val ticket = tickets.prepare(requestContext(), "운영체제", 6, learnerTurnId = 11, lessonRevision = 0)

        assertThat(ticket).isNotNull()
        assertThat(tickets.prepare(requestContext(), "운영체제", 6, 11, 0)).isEqualTo(ticket)
        assertThat(tickets.prepare(requestContext(), "운영체제", 7, 11, 0)).isNull()
        assertThat(tickets.prepare(requestContext().copy(callId = "other-call"), "운영체제", 6, 11, 0)).isNull()
        assertThat(tickets.consume(confirmationContext(), "다른 주제", 6, ticket!!.token, 12, 0)).isNull()
        assertThat(tickets.consume(confirmationContext(), "운영체제", 7, ticket.token, 12, 0)).isNull()
        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, "wrong", 12, 0)).isNull()
        assertThat(tickets.consume(
            confirmationContext().copy(principal = principal.copy(deviceId = "other-device")),
            "운영체제", 6, ticket.token, 12, 0,
        )).isNull()
    }

    @Test
    fun `confirmation requires a newer persisted root intent after completed tutor speech and is one shot`() {
        val tickets = VoiceTutorRootStudyConfirmations(clock)
        val ticket = requireNotNull(tickets.prepare(requestContext(), "운영체제", 6, 11, 0))

        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, ticket.token, 11, 0)).isNull()
        assertThat(tickets.consume(
            confirmationContext().copy(dialogueBoundary = confirmationBoundary().copy(
                precedingSpokenResponseGeneration = 2,
            )),
            "운영체제", 6, ticket.token, 12, 0,
        )).isNull()
        assertThat(tickets.consume(
            confirmationContext().copy(dialogueBoundary = confirmationBoundary().copy(
                latestAcceptedLearnerSpeechStartedOrder = 6,
            )),
            "운영체제", 6, ticket.token, 12, 0,
        )).isNull()
        assertThat(tickets.consume(
            confirmationContext().copy(dialogueBoundary = confirmationBoundary().copy(
                latestAcceptedLearnerIntent = VoiceTutorInputIntent.NONE,
            )),
            "운영체제", 6, ticket.token, 12, 0,
        )).isNull()

        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, ticket.token, 12, 0))
            .isEqualTo(ticket)
        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, ticket.token, 13, 0)).isNull()
    }

    @Test
    fun `direct replacement cannot consume old offer and supersedes it with a fresh preview`() {
        val tickets = VoiceTutorRootStudyConfirmations(clock)
        val old = requireNotNull(tickets.prepare(requestContext(), "운영체제", 6, 11, 0))
        val correction = requestContext().copy(dialogueBoundary = requestBoundary().copy(
            responseGeneration = 4,
            latestAcceptedLearnerSpeechStartedOrder = 7,
            precedingTutorSpeechStoppedOrder = 6,
            precedingSpokenResponseGeneration = 3,
            precedingTutorProviderItemId = "root-a-preview",
            latestAcceptedLearnerProviderItemId = "root-b-request",
            latestAcceptedLearnerRootStudyCreationOffer = rootOffer(
                "운영체제", 6, previewGeneration = 2, tutorGeneration = 3,
                stoppedOrder = 6, tutorItemId = "root-a-preview",
            ),
        ))

        assertThat(tickets.consume(correction, "운영체제", 6, old.token, 12, 0)).isNull()

        val replacement = tickets.prepare(correction, "Redis", 8, 12, 0)
        assertThat(replacement).isNotNull()
        assertThat(replacement!!.token).isNotEqualTo(old.token)
        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, old.token, 13, 0)).isNull()

        val redisConfirmation = confirmationContext(
            topic = "Redis", difficulty = 8, previewGeneration = 4,
            tutorGeneration = 5, stoppedOrder = 10, responseGeneration = 6,
        )
        assertThat(tickets.consume(redisConfirmation, "Redis", 8, replacement.token, 13, 0))
            .isEqualTo(replacement)
    }

    @Test
    fun `checkpoint target intent invalid topic and expired preview fail closed`() {
        val mutableClock = MutableClock(now)
        val tickets = VoiceTutorRootStudyConfirmations(mutableClock)

        assertThat(tickets.prepare(
            requestContext().copy(dialogueBoundary = requestBoundary().copy(
                latestAcceptedLearnerIntent = VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC,
            )),
            "운영체제", 6, 11, 0,
        )).isNull()
        assertThat(tickets.prepare(requestContext(), " 운영체제", 6, 11, 0)).isNull()
        assertThat(tickets.prepare(requestContext(), "운영체제", 0, 11, 0)).isNull()

        val ticket = requireNotNull(tickets.prepare(requestContext(), "운영체제", 6, 11, 0))
        mutableClock.advance(Duration.ofSeconds(120))
        assertThat(tickets.consume(confirmationContext(), "운영체제", 6, ticket.token, 12, 0)).isNull()
    }

    private fun requestContext() = VoiceTutorWebRtcControlContext(
        session(), "rtc-call", principal, dialogueBoundary = requestBoundary(),
    )

    private fun confirmationContext(
        topic: String = "운영체제",
        difficulty: Int = 6,
        previewGeneration: Long = 2,
        tutorGeneration: Long = 3,
        stoppedOrder: Long = 6,
        responseGeneration: Long = 4,
    ) = requestContext().copy(dialogueBoundary = confirmationBoundary(
        topic, difficulty, previewGeneration, tutorGeneration, stoppedOrder, responseGeneration,
    ))

    private fun requestBoundary() = VoiceTutorDialogueBoundary(
        responseGeneration = 2,
        latestAcceptedLearnerSpeechStartedOrder = 3,
        precedingTutorSpeechStoppedOrder = 2,
        precedingSpokenResponseGeneration = 1,
        latestAcceptedLearnerProviderItemId = "root-request",
        latestAcceptedLearnerLessonRevision = 0,
        latestAcceptedLearnerIntent = VoiceTutorInputIntent.CREATE_ROOT_STUDY,
    )

    private fun confirmationBoundary(
        topic: String = "운영체제",
        difficulty: Int = 6,
        previewGeneration: Long = 2,
        tutorGeneration: Long = 3,
        stoppedOrder: Long = 6,
        responseGeneration: Long = 4,
    ) = VoiceTutorDialogueBoundary(
        responseGeneration = responseGeneration,
        latestAcceptedLearnerSpeechStartedOrder = stoppedOrder + 1,
        precedingTutorSpeechStoppedOrder = stoppedOrder,
        precedingSpokenResponseGeneration = tutorGeneration,
        precedingTutorProviderItemId = "root-preview-$tutorGeneration",
        latestAcceptedLearnerProviderItemId = "root-confirmation",
        latestAcceptedLearnerLessonRevision = 0,
        latestAcceptedLearnerIntent = VoiceTutorInputIntent.CONFIRM_ROOT_STUDY,
        latestAcceptedLearnerRootStudyCreationOffer = rootOffer(
            topic, difficulty, previewGeneration, tutorGeneration, stoppedOrder,
            "root-preview-$tutorGeneration",
        ),
    )

    private fun rootOffer(
        topic: String,
        difficulty: Int,
        previewGeneration: Long,
        tutorGeneration: Long,
        stoppedOrder: Long,
        tutorItemId: String,
    ) = VoiceTutorRootStudyCreationOffer(
        topic = topic,
        difficulty = difficulty,
        lessonRevision = 0,
        previewResponseGeneration = previewGeneration,
        tutorResponseGeneration = tutorGeneration,
        tutorSpeechStoppedOrder = stoppedOrder,
        tutorProviderItemId = tutorItemId,
        tutorAudioTranscript = "$topic 주제를 레벨 $difficulty 루트로 만들까요?",
    )

    private fun session() = VoiceTutorSession(
        id = "00000000-0000-0000-0000-000000000007",
        userId = principal.userId,
        studyId = null,
        acceptedStudyId = null,
        idempotencyKey = "synthetic-call",
        providerSessionId = "rtc-call",
        status = VoiceTutorSessionStatus.ACTIVE,
        resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko",
        model = "gpt-realtime",
        voice = "marin",
        topic = "",
        difficulty = 0,
        periodStartedAt = now.minusSeconds(60),
        periodEndsAt = now.plusSeconds(86_400),
        reservedSeconds = 3_600,
        chargedSeconds = 0,
        maxSessionSeconds = 3_600,
        hardEndsAt = now.plusSeconds(3_600),
        connectedAt = now.minusSeconds(5),
        relayHeartbeatAt = now,
        acceptedAudioBytes = 0,
        endedAt = null,
        finalizedAt = null,
        endReason = null,
        failureCode = null,
        failureMessage = null,
        createdAt = now.minusSeconds(10),
        updatedAt = now,
    )

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
        fun advance(duration: Duration) { instant = instant.plus(duration) }
    }

    private companion object {
        val now: Instant = Instant.parse("2026-08-31T00:00:00Z")
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        val principal = Principal(7L, "synthetic-device", 11L, false)
    }
}
