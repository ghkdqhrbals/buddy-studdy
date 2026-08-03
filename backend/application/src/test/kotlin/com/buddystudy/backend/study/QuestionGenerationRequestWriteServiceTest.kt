package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.SystemQuestionQuotaReservation
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.QuestionGenerationRequestWriteService
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import java.time.Instant

class QuestionGenerationRequestWriteServiceTest {
    @ParameterizedTest
    @EnumSource(value = QuestionStatus::class, names = ["FAILED", "GRADED", "SKIPPED"])
    fun `terminal latest question permits manual generation`(status: QuestionStatus): Unit = runBlocking {
        val fixture = fixture(status)

        val queued = fixture.writer.enqueueManual(7, "device-7", 11, "request-1", fixture.now)

        assertThat(queued.accepted.topicId).isEqualTo("11")
        assertThat(fixture.insertedSagas).hasSize(1)
    }

    @ParameterizedTest
    @EnumSource(value = QuestionStatus::class, names = ["UNGRADED", "GRADING"])
    fun `in-progress latest question rejects manual generation`(status: QuestionStatus): Unit = runBlocking {
        val fixture = fixture(status)

        val failure = runCatching {
            fixture.writer.enqueueManual(7, "device-7", 11, "request-1", fixture.now)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS)
        assertThat(fixture.insertedSagas).isEmpty()
    }

    @Test
    fun `study without a previous question permits manual generation`(): Unit = runBlocking {
        val fixture = fixture(null)

        fixture.writer.enqueueManual(7, "device-7", 11, "request-1", fixture.now)

        assertThat(fixture.insertedSagas).hasSize(1)
    }

    private fun fixture(latestStatus: QuestionStatus?): Fixture = runBlocking {
        val now = Instant.parse("2026-08-03T00:00:00Z")
        val study = StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "device-7",
            topic = "Redis",
            createdAt = now.minusSeconds(3600),
            updatedAt = now.minusSeconds(3600),
        )
        val user = UserEntity(id = 7, providerId = "user-7", status = UserStatus.ACTIVE, createdAt = now.minusSeconds(86400))
        val studies = Mockito.mock(StudyPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val users = Mockito.mock(UserPort::class.java)
        val questionKeys = Mockito.mock(OpenAIQuestionKeyProvider::class.java)
        val insertedSagas = mutableListOf<com.buddystudy.backend.study.application.model.QuestionGenerationSaga>()
        val sagas = object : QuestionGenerationSagaPort {
            override suspend fun insert(saga: com.buddystudy.backend.study.application.model.QuestionGenerationSaga): Boolean {
                insertedSagas += saga
                return true
            }

            override suspend fun findByCorrelationId(correlationId: String) = null
            override suspend fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String) = null
            override suspend fun findActiveByUserIdAndTopicId(userId: Long, topicId: Long) = null
            override suspend fun markGenerating(correlationId: String, now: Instant) = false
            override suspend fun markTranslating(correlationId: String, questionId: Long, now: Instant) = false
            override suspend fun markCompleted(correlationId: String, now: Instant) = false
            override suspend fun markFailed(
                correlationId: String,
                failedStep: com.buddystudy.backend.study.application.model.QuestionGenerationStep,
                errorCode: String,
                errorMessage: String,
                refundedAt: Instant?,
                now: Instant,
            ) = false
            override suspend fun markRollbackCompleted(correlationId: String, now: Instant) = false
        }
        Mockito.`when`(studies.findByIdAndUserId(11, 7)).thenReturn(study)
        Mockito.`when`(studies.findAllByUserId(7)).thenReturn(listOf(study))
        Mockito.`when`(users.findById(7)).thenReturn(user)
        Mockito.`when`(questions.findLatestStatusByStudyId(11)).thenReturn(latestStatus)
        Mockito.`when`(questionKeys.resolveForQuestionGeneration(user)).thenReturn(
            OpenAIQuestionKey(
                apiKey = "test-key",
                quotaReservation = SystemQuestionQuotaReservation(7, now.minusSeconds(86400)),
                user = user,
            ),
        )
        val writer = QuestionGenerationRequestWriteService(
            studies = studies,
            questions = questions,
            users = users,
            questionKeys = questionKeys,
            sagas = sagas,
            outbox = RecordingOutbox(),
        )
        Fixture(writer, insertedSagas, now)
    }

    private data class Fixture(
        val writer: QuestionGenerationRequestWriteService,
        val insertedSagas: List<com.buddystudy.backend.study.application.model.QuestionGenerationSaga>,
        val now: Instant,
    )

    private class RecordingOutbox : RedisEventOutboxAppendPort {
        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant) = 1L
        override suspend fun appendQuestionGenerationRequested(event: QuestionGenerationRequestedEvent, createdAt: Instant) = 2L
        override suspend fun appendQuestionGenerated(event: QuestionGeneratedEvent, createdAt: Instant) = 3L
        override suspend fun appendQuestionGenerationRollbackRequested(
            event: QuestionGenerationRollbackRequestedEvent,
            createdAt: Instant,
        ) = 4L
    }
}
