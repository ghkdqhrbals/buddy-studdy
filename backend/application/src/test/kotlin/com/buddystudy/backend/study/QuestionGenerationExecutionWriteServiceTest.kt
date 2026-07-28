package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.study.application.service.QuestionGenerationExecutionWriteService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class QuestionGenerationExecutionWriteServiceTest {
    @Test
    fun `terminal failure refunds a quota reservation only once`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val saga = saga(now)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val writer = writer(sagas, inbox, memberships)
        val event = event(saga, now)
        val firstClaim = StreamInboxClaim(event.eventId, "generation", "claim-1", 3)
        val duplicateClaim = StreamInboxClaim(event.eventId, "generation", "claim-2", 4)

        Mockito.`when`(sagas.findByCorrelationId(saga.correlationId))
            .thenReturn(saga, saga.copy(status = QuestionGenerationStatus.FAILED, quotaRefundedAt = now))
        Mockito.`when`(
            sagas.markFailed(
                saga.correlationId,
                QuestionGenerationStep.GENERATING,
                "QUESTION_GENERATION_FAILED",
                "Generation failed.",
                now,
                now,
            ),
        ).thenReturn(true)
        Mockito.`when`(inbox.markSucceeded(firstClaim, now)).thenReturn(true)
        Mockito.`when`(inbox.markSucceeded(duplicateClaim, now)).thenReturn(true)

        writer.fail(
            event,
            firstClaim,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            now,
        )
        writer.fail(
            event,
            duplicateClaim,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            now,
        )

        Mockito.verify(memberships, Mockito.times(1))
            .refundMonthlySystemQuestion(saga.userId, saga.quotaPeriodStartedAt, now)
        Mockito.verify(sagas, Mockito.times(1)).markFailed(
            saga.correlationId,
            QuestionGenerationStep.GENERATING,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            now,
            now,
        )
    }

    @Test
    fun `retry releases only the inbox lease and does not refund quota`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val writer = writer(sagas, inbox, memberships)
        val claim = StreamInboxClaim("event-1", "generation", "claim-1", 1)
        Mockito.`when`(inbox.releaseForRetry(claim, "Temporary failure", now)).thenReturn(true)

        writer.retry(claim, "Temporary failure", now)

        Mockito.verify(inbox).releaseForRetry(claim, "Temporary failure", now)
        Mockito.verifyNoInteractions(memberships)
        Mockito.verifyNoInteractions(sagas)
    }

    private fun writer(
        sagas: QuestionGenerationSagaPort,
        inbox: StreamInboxPort,
        memberships: QuestionMembershipPort,
    ) = QuestionGenerationExecutionWriteService(
        sagas = sagas,
        inbox = inbox,
        questions = Mockito.mock(QuestionPort::class.java),
        questionStats = Mockito.mock(QuestionStatsPort::class.java),
        questionEmbeddings = Mockito.mock(QuestionEmbeddingPort::class.java),
        questionCoverage = Mockito.mock(QuestionCoveragePort::class.java),
        questionKeys = OpenAIQuestionKeyProvider(
            UserContentOpenAIKeyProvider(
                BuddyStudyProperties(openai = BuddyStudyProperties.OpenAI(userContentApiKey = "test-key")),
            ),
            memberships,
        ),
        outbox = Mockito.mock(RedisEventOutboxAppendPort::class.java),
    )

    private fun saga(now: Instant) = QuestionGenerationSaga(
        correlationId = "correlation-1",
        userId = 7,
        studyId = 11,
        topicId = 12,
        questionId = null,
        source = QuestionGenerationSource.MANUAL,
        status = QuestionGenerationStatus.GENERATING,
        currentStep = QuestionGenerationStep.GENERATING,
        idempotencyKey = "manual:test",
        quotaPeriodStartedAt = now.minusSeconds(86_400),
        quotaRefundedAt = null,
        failedStep = null,
        errorCode = null,
        errorMessage = null,
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(30),
        completedAt = null,
    )

    private fun event(saga: QuestionGenerationSaga, now: Instant) = QuestionGenerationRequestedEvent(
        eventId = "event-1",
        correlationId = saga.correlationId,
        userId = saga.userId,
        studyId = saga.studyId,
        topicId = saga.topicId,
        source = saga.source,
        occurredAt = now.minusSeconds(60),
    )
}
