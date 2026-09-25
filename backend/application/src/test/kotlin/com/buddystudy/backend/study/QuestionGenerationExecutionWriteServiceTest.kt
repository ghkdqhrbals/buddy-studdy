package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.model.PreparedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.study.application.service.QuestionGenerationExecutionWriteService
import com.buddystudy.backend.localization.application.service.ContentTranslationRequestManager
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.backend.test.RecordingContentTranslationEventPort
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import org.assertj.core.api.Assertions.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import java.time.Instant

class QuestionGenerationExecutionWriteServiceTest {
    @Test
    fun `completion retry after an observed commit reuses saved question without another quota or outbox write`() = runBlocking<Unit> {
        val now = Instant.parse("2026-09-12T00:00:00Z")
        for (status in listOf(QuestionGenerationStatus.TRANSLATING, QuestionGenerationStatus.COMPLETED)) {
            val storedSaga = saga(now).copy(status = status, questionId = 42)
            val saved = QuestionEntity(id = 42, userId = storedSaga.userId, studyId = storedSaga.topicId)
            val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
            val questions = Mockito.mock(QuestionPort::class.java)
            val memberships = Mockito.mock(QuestionMembershipPort::class.java)
            val outbox = Mockito.mock(RedisEventOutboxAppendPort::class.java)
            Mockito.`when`(sagas.findByCorrelationId(storedSaga.correlationId)).thenReturn(storedSaga)
            Mockito.`when`(questions.findQuestionById(42)).thenReturn(saved)
            val result = writer(sagas, Mockito.mock(StreamInboxPort::class.java), memberships, outbox, questions)
                .complete(event(storedSaga, now), PreparedQuestionGeneration(
                    QuestionEntity(userId = storedSaga.userId, studyId = storedSaga.studyId), emptyList(), null,
                    OpenAIQuestionKey("test-key", user = null)), now)
            assertThat(result.question).isSameAs(saved)
            assertThat(result.outboxes).isEmpty()
            Mockito.verify(questions).findQuestionById(42)
            Mockito.verifyNoMoreInteractions(questions)
            Mockito.verifyNoInteractions(memberships, outbox)
        }
    }

    @Test
    fun `generation failure after an ambiguous commit cannot roll back translating or completed questions`() = runBlocking<Unit> {
        val now = Instant.parse("2026-09-12T00:00:00Z")
        for (status in listOf(QuestionGenerationStatus.TRANSLATING, QuestionGenerationStatus.COMPLETED)) {
            val committed = saga(now).copy(status = status, questionId = 42)
            val saved = QuestionEntity(id = 42, userId = committed.userId, studyId = committed.topicId)
            val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
            val questions = Mockito.mock(QuestionPort::class.java)
            val memberships = Mockito.mock(QuestionMembershipPort::class.java)
            val outbox = Mockito.mock(RedisEventOutboxAppendPort::class.java)
            Mockito.`when`(sagas.findByCorrelationId(committed.correlationId)).thenReturn(committed)
            Mockito.`when`(questions.findQuestionById(42)).thenReturn(saved)
            val writer = writer(sagas, Mockito.mock(StreamInboxPort::class.java), memberships, outbox, questions)

            val rollback = writer.fail(event(committed, now), "QUESTION_GENERATION_FAILED", "Commit reply lost.", now)
            val recovered = writer.findCommitted(event(committed, now))

            assertThat(rollback).isNull()
            assertThat(recovered?.question).isSameAs(saved)
            assertThat(recovered?.outboxes).isEmpty()
            Mockito.verify(sagas, Mockito.times(2)).findByCorrelationId(committed.correlationId)
            Mockito.verifyNoMoreInteractions(sagas)
            Mockito.verify(questions).findQuestionById(42)
            Mockito.verifyNoMoreInteractions(questions)
            Mockito.verifyNoInteractions(memberships, outbox)
        }
    }
    @ParameterizedTest
    @EnumSource(QuestionGenerationSource::class)
    fun `completed manual or scheduled generation appends localization outboxes`(
        source: QuestionGenerationSource,
    ) = runBlocking<Unit> {
        val now = Instant.parse("2026-07-31T12:00:00Z")
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val questionStats = Mockito.mock(QuestionStatsPort::class.java)
        val embeddings = Mockito.mock(QuestionEmbeddingPort::class.java)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val outbox = RecordingGeneratedQuestionOutbox()
        val translationEvents = RecordingContentTranslationEventPort()
        val event = event(saga(now, source), now)
        val claim = StreamInboxClaim(event.eventId, "generation", "claim-1", 1)
        val saved = QuestionEntity(
            id = 42,
            userId = event.userId,
            studyId = event.studyId,
            topic = "Redis",
            question = "컨슈머 그룹을 설명하세요.",
            sourceLanguage = SupportedLanguage.KOREAN,
            createdAt = now,
            updatedAt = now,
        )
        val prepared = PreparedQuestionGeneration(
            question = saved,
            embedding = listOf(0.1f, 0.2f),
            coverage = null,
            questionKey = OpenAIQuestionKey("test-key", user = null),
        )
        Mockito.`when`(questions.save(saved)).thenReturn(saved)
        Mockito.`when`(sagas.findByCorrelationId(event.correlationId)).thenReturn(saga(now, source))
        Mockito.`when`(sagas.markTranslating(event.correlationId, saved.id, now)).thenReturn(true)
        val writer = QuestionGenerationExecutionWriteService(
            sagas = sagas,
            inbox = inbox,
            questions = questions,
            questionStats = questionStats,
            questionEmbeddings = embeddings,
            questionCoverage = coverage,
            questionKeys = OpenAIQuestionKeyProvider(
                UserContentOpenAIKeyProvider(
                    BuddyStudyProperties(openai = BuddyStudyProperties.OpenAI(userContentApiKey = "test-key")),
                ),
                memberships,
            ),
            outbox = outbox,
            translationRequests = ContentTranslationRequestManager(
                EmptyContentLocalizationPort(),
                translationEvents,
            ),
        )

        val result = writer.complete(event, prepared, now)

        assertThat(result.outboxes.map { it.id }).containsExactly(90L, 1L, 2L)
        assertThat(translationEvents.events.map { it.contentType to it.targetLanguage })
            .containsExactlyInAnyOrder(
                LocalizableContentType.QUESTION to "en",
                LocalizableContentType.QUESTION to "ja",
            )
        Mockito.verifyNoInteractions(inbox)

        Mockito.`when`(inbox.markSucceeded(claim, now)).thenReturn(true)
        writer.succeed(claim, now)

        Mockito.verify(inbox).markSucceeded(claim, now)
    }

    @Test
    fun `terminal failure publishes one rollback event without refunding quota directly`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val saga = saga(now)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val outbox = RecordingGeneratedQuestionOutbox()
        val writer = writer(sagas, inbox, memberships, outbox)
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
                null,
                now,
            ),
        ).thenReturn(true)
        Mockito.`when`(
            inbox.markFailed(firstClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now),
        ).thenReturn(true)
        Mockito.`when`(
            inbox.markFailed(duplicateClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now),
        ).thenReturn(true)

        val first = writer.fail(
            event,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            now,
        )
        val duplicate = writer.fail(
            event,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            now,
        )

        assertThat(first?.id).isEqualTo(91L)
        assertThat(duplicate).isNull()
        assertThat(outbox.rollbackEvents).hasSize(1)
        val rollback = outbox.rollbackEvents.single()
        assertThat(rollback.correlationId).isEqualTo(saga.correlationId)
        assertThat(rollback.causationId).isEqualTo(event.eventId)
        assertThat(rollback.userId).isEqualTo(saga.userId)
        assertThat(rollback.questionId).isNull()
        assertThat(rollback.quotaPeriodStartedAt).isEqualTo(saga.quotaPeriodStartedAt)
        assertThat(rollback.failedStep).isEqualTo(QuestionGenerationStep.GENERATING)
        Mockito.verifyNoInteractions(memberships)
        Mockito.verify(sagas, Mockito.times(1)).markFailed(
            saga.correlationId,
            QuestionGenerationStep.GENERATING,
            "QUESTION_GENERATION_FAILED",
            "Generation failed.",
            null,
            now,
        )
        Mockito.verifyNoInteractions(inbox)

        writer.completeFailure(firstClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now)
        writer.completeFailure(duplicateClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now)

        Mockito.verify(inbox).markFailed(firstClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now)
        Mockito.verify(inbox).markFailed(duplicateClaim, "QUESTION_GENERATION_FAILED", "Generation failed.", now)
    }

    @Test
    fun `retry releases only the inbox lease and does not refund quota`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val inbox = Mockito.mock(StreamInboxPort::class.java)
        val memberships = Mockito.mock(QuestionMembershipPort::class.java)
        val writer = writer(sagas, inbox, memberships)
        val claim = StreamInboxClaim("event-1", "generation", "claim-1", 1)
        Mockito.`when`(
            inbox.releaseForRetry(claim, "QUESTION_GENERATION_FAILED", "Temporary failure", now),
        ).thenReturn(true)

        writer.retry(claim, "Temporary failure", now)

        Mockito.verify(inbox).releaseForRetry(
            claim,
            "QUESTION_GENERATION_FAILED",
            "Temporary failure",
            now,
        )
        Mockito.verifyNoInteractions(memberships)
        Mockito.verifyNoInteractions(sagas)
    }

    @Test
    fun `deletion during AI generation prevents saving the follow-up or charging quota`(): Unit = runBlocking {
        val now = Instant.parse("2026-09-24T00:00:00Z")
        for (deletedRoot in listOf(true, false)) {
            val questions = Mockito.mock(QuestionPort::class.java)
            val memberships = Mockito.mock(QuestionMembershipPort::class.java)
            val original = QuestionEntity(id = 20, userId = 7)
            Mockito.`when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(20, 7))
                .thenReturn(if (deletedRoot) null else original)
            Mockito.`when`(questions.lockThreadByRootAndUser(20, 7))
                .thenReturn(listOf(original, QuestionEntity(id = 21, userId = 7, deletedAt = now)))
            val prepared = PreparedQuestionGeneration(
                QuestionEntity(userId = 7, source = com.buddystudy.study.domain.entity.QuestionSource.FOLLOW_UP,
                    parentRecordId = if (deletedRoot) 20 else 21, rootRecordId = 20, followUpDepth = if (deletedRoot) 1 else 2),
                listOf(0.1f), null, OpenAIQuestionKey("test", user = null),
            )
            val storedSaga = saga(now, QuestionGenerationSource.FOLLOW_UP)
            val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
            Mockito.`when`(sagas.findByCorrelationId(storedSaga.correlationId)).thenReturn(storedSaga)
            val writer = writer(sagas, Mockito.mock(StreamInboxPort::class.java), memberships, questions = questions)
            val failure = runCatching { writer.complete(event(storedSaga, now), prepared, now) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).contains(if (deletedRoot) "original record was deleted" else "context changed")
            Mockito.verify(questions).lockByIdAndUserIdAndDeletedAtIsNull(20, 7)
            Mockito.verify(questions, Mockito.never()).save(prepared.question)
            Mockito.verifyNoInteractions(memberships)
        }
    }

    private fun writer(
        sagas: QuestionGenerationSagaPort,
        inbox: StreamInboxPort,
        memberships: QuestionMembershipPort,
        outbox: RedisEventOutboxAppendPort = Mockito.mock(RedisEventOutboxAppendPort::class.java),
        questions: QuestionPort = Mockito.mock(QuestionPort::class.java),
    ) = QuestionGenerationExecutionWriteService(
        sagas = sagas,
        inbox = inbox,
        questions = questions,
        questionStats = Mockito.mock(QuestionStatsPort::class.java),
        questionEmbeddings = Mockito.mock(QuestionEmbeddingPort::class.java),
        questionCoverage = Mockito.mock(QuestionCoveragePort::class.java),
        questionKeys = OpenAIQuestionKeyProvider(
            UserContentOpenAIKeyProvider(
                BuddyStudyProperties(openai = BuddyStudyProperties.OpenAI(userContentApiKey = "test-key")),
            ),
            memberships,
        ),
        outbox = outbox,
        translationRequests = ContentTranslationRequestManager(
            EmptyContentLocalizationPort(),
            RecordingContentTranslationEventPort(),
        ),
    )

    private fun saga(
        now: Instant,
        source: QuestionGenerationSource = QuestionGenerationSource.MANUAL,
    ) = QuestionGenerationSaga(
        correlationId = "correlation-1",
        userId = 7,
        studyId = 11,
        topicId = 12,
        questionId = null,
        source = source,
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

    private class RecordingGeneratedQuestionOutbox : RedisEventOutboxAppendPort {
        val events = mutableListOf<QuestionGeneratedEvent>()
        val rollbackEvents = mutableListOf<QuestionGenerationRollbackRequestedEvent>()

        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long =
            error("Not expected.")

        override suspend fun appendQuestionGenerated(event: QuestionGeneratedEvent, createdAt: Instant): Long {
            events += event
            return 90L
        }

        override suspend fun appendQuestionGenerationRollbackRequested(
            event: QuestionGenerationRollbackRequestedEvent,
            createdAt: Instant,
        ): Long {
            rollbackEvents += event
            return 91L
        }
    }
}
