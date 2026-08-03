package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.service.QuestionGenerationProcessService
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class QuestionGenerationProcessServiceTest {
    @Test
    fun `failed process becomes terminal only after rollback completes`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val pendingRollback = failedSaga(now)
        val completedRollback = pendingRollback.copy(rollbackCompletedAt = now)
        val sagas = Mockito.mock(QuestionGenerationSagaPort::class.java)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stats = Mockito.mock(QuestionStatsPort::class.java)
        val users = Mockito.mock(UserPort::class.java)
        val localizations = Mockito.mock(ContentLocalizationPort::class.java)
        Mockito.`when`(sagas.findByCorrelationId(pendingRollback.correlationId))
            .thenReturn(pendingRollback, completedRollback)
        Mockito.`when`(users.findById(pendingRollback.userId)).thenReturn(null)
        val service = QuestionGenerationProcessService(sagas, questions, stats, users, localizations)
        val principal = Principal(pendingRollback.userId, "device-1", 1, anonymous = false)

        val pending = service.get(principal, pendingRollback.correlationId)
        val completed = service.get(principal, pendingRollback.correlationId)

        assertThat(pending.status).isEqualTo(QuestionGenerationStatus.FAILED)
        assertThat(pending.terminal).isFalse()
        assertThat(pending.pollAfterMs).isEqualTo(250)
        assertThat(completed.status).isEqualTo(QuestionGenerationStatus.FAILED)
        assertThat(completed.terminal).isTrue()
        assertThat(completed.pollAfterMs).isNull()
    }

    private fun failedSaga(now: Instant) = QuestionGenerationSaga(
        correlationId = "correlation-1",
        userId = 7,
        studyId = 11,
        topicId = 12,
        questionId = 42,
        source = QuestionGenerationSource.MANUAL,
        status = QuestionGenerationStatus.FAILED,
        currentStep = QuestionGenerationStep.TRANSLATING,
        idempotencyKey = "manual:test",
        quotaPeriodStartedAt = now.minusSeconds(86_400),
        quotaRefundedAt = null,
        failedStep = QuestionGenerationStep.TRANSLATING,
        errorCode = "QUESTION_TRANSLATION_FAILED",
        errorMessage = "Translation failed.",
        createdAt = now.minusSeconds(120),
        updatedAt = now.minusSeconds(60),
        completedAt = now.minusSeconds(60),
    )
}
