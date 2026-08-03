package com.buddystudy.backend.study

import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.study.application.service.QuestionGenerationRollbackWriteService
import com.buddystudy.study.domain.entity.QuestionEntity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class QuestionGenerationRollbackServiceTest {
    @Test
    fun `rollback removes generated data and refunds the quota reservation once`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val saga = failedSaga(now, questionId = 42)
        val event = rollbackEvent(saga, now)
        val claim = StreamInboxClaim(event.eventId, "rollback", "claim-1", 1)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stats = Mockito.mock(QuestionStatsPort::class.java)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val question = QuestionEntity(
            id = 42,
            userId = saga.userId,
            studyId = saga.studyId,
            conceptId = 91,
            angleKey = "tradeoffs",
            topic = "Redis",
            question = "Explain consumer groups.",
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )
        Mockito.`when`(sagas.findByCorrelationId(saga.correlationId)).thenReturn(saga)
        Mockito.`when`(questions.findQuestionById(question.id)).thenReturn(question)
        Mockito.`when`(stats.deleteByQuestionId(question.id)).thenReturn(1)
        Mockito.`when`(questions.deleteGeneratedForRollback(question.id, saga.userId)).thenReturn(1)
        Mockito.`when`(sagas.markRollbackCompleted(saga.correlationId, now)).thenReturn(true)
        Mockito.`when`(inbox.markSucceeded(claim, now)).thenReturn(true)
        val writer = QuestionGenerationRollbackWriteService(sagas, inbox, questions, stats, coverage, memberships)

        writer.complete(event, claim, now)

        Mockito.verify(coverage).rollbackAsked(91, "tradeoffs", now)
        Mockito.verify(stats).deleteByQuestionId(question.id)
        Mockito.verify(questions).deleteGeneratedForRollback(question.id, saga.userId)
        Mockito.verify(memberships).refundMonthlySystemQuestion(saga.userId, saga.quotaPeriodStartedAt, now)
        Mockito.verify(sagas).markRollbackCompleted(saga.correlationId, now)
        Mockito.verify(inbox).markSucceeded(claim, now)
    }

    @Test
    fun `generation failure without a saved question still refunds quota`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val saga = failedSaga(now, questionId = null)
        val event = rollbackEvent(saga, now)
        val claim = StreamInboxClaim(event.eventId, "rollback", "claim-1", 1)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stats = Mockito.mock(QuestionStatsPort::class.java)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        Mockito.`when`(sagas.findByCorrelationId(saga.correlationId)).thenReturn(saga)
        Mockito.`when`(sagas.markRollbackCompleted(saga.correlationId, now)).thenReturn(true)
        Mockito.`when`(inbox.markSucceeded(claim, now)).thenReturn(true)
        val writer = QuestionGenerationRollbackWriteService(sagas, inbox, questions, stats, coverage, memberships)

        writer.complete(event, claim, now)

        Mockito.verifyNoInteractions(questions, stats, coverage)
        Mockito.verify(memberships).refundMonthlySystemQuestion(saga.userId, saga.quotaPeriodStartedAt, now)
        Mockito.verify(sagas).markRollbackCompleted(saga.correlationId, now)
        Mockito.verify(inbox).markSucceeded(claim, now)
    }

    @Test
    fun `completed rollback event is acknowledged without compensating twice`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val saga = failedSaga(now, questionId = 42).copy(rollbackCompletedAt = now.minusSeconds(1))
        val event = rollbackEvent(saga, now)
        val claim = StreamInboxClaim(event.eventId, "rollback", "claim-1", 2)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stats = Mockito.mock(QuestionStatsPort::class.java)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        Mockito.`when`(
            inbox.claim(
                eventId = event.eventId,
                consumerGroup = QuestionGenerationRollbackWriteService.CONSUMER_GROUP,
                correlationId = event.correlationId,
                leaseDuration = java.time.Duration.ofMinutes(3),
                now = now,
                streamKey = "study.question-generation.rollback-requested.v1",
            ),
        ).thenReturn(claim)
        Mockito.`when`(sagas.findByCorrelationId(saga.correlationId)).thenReturn(saga)
        Mockito.`when`(inbox.markSucceeded(claim, now)).thenReturn(true)
        val writer = QuestionGenerationRollbackWriteService(sagas, inbox, questions, stats, coverage, memberships)

        val result = writer.claim(event, now, "study.question-generation.rollback-requested.v1")

        assertThat(result).isNull()
        Mockito.verify(inbox).markSucceeded(claim, now)
        Mockito.verifyNoInteractions(questions, stats, coverage, memberships)
    }

    @Test
    fun `rollback rejects a generated question owned by another user`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val saga = failedSaga(now, questionId = 42)
        val event = rollbackEvent(saga, now)
        val claim = StreamInboxClaim(event.eventId, "rollback", "claim-1", 1)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stats = Mockito.mock(QuestionStatsPort::class.java)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val otherUsersQuestion = QuestionEntity(
            id = 42,
            userId = saga.userId + 1,
            studyId = saga.studyId,
            topic = "Redis",
            question = "Explain consumer groups.",
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )
        Mockito.`when`(sagas.findByCorrelationId(saga.correlationId)).thenReturn(saga)
        Mockito.`when`(questions.findQuestionById(otherUsersQuestion.id)).thenReturn(otherUsersQuestion)
        val writer = QuestionGenerationRollbackWriteService(sagas, inbox, questions, stats, coverage, memberships)

        assertThatThrownBy { runBlocking { writer.complete(event, claim, now) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("owner does not match")

        Mockito.verifyNoInteractions(stats, coverage, memberships)
        Mockito.verify(sagas, Mockito.never()).markRollbackCompleted(saga.correlationId, now)
        Mockito.verify(inbox, Mockito.never()).markSucceeded(claim, now)
    }

    private fun failedSaga(now: Instant, questionId: Long?) = QuestionGenerationSaga(
        correlationId = "correlation-1",
        userId = 7,
        studyId = 11,
        topicId = 12,
        questionId = questionId,
        source = QuestionGenerationSource.MANUAL,
        status = QuestionGenerationStatus.FAILED,
        currentStep = QuestionGenerationStep.GENERATING,
        idempotencyKey = "manual:test",
        quotaPeriodStartedAt = now.minusSeconds(86_400),
        quotaRefundedAt = null,
        failedStep = QuestionGenerationStep.GENERATING,
        errorCode = "QUESTION_GENERATION_FAILED",
        errorMessage = "Generation failed.",
        createdAt = now.minusSeconds(120),
        updatedAt = now.minusSeconds(60),
        completedAt = now.minusSeconds(60),
    )

    private fun rollbackEvent(saga: QuestionGenerationSaga, now: Instant) =
        QuestionGenerationRollbackRequestedEvent(
            eventId = "rollback-event-1",
            correlationId = saga.correlationId,
            causationId = "generation-event-1",
            userId = saga.userId,
            questionId = saga.questionId,
            quotaPeriodStartedAt = saga.quotaPeriodStartedAt,
            failedStep = saga.failedStep ?: QuestionGenerationStep.GENERATING,
            occurredAt = now,
        )
}
