package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.service.AnswerGradingObservationService
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class AnswerGradingObservationServiceTest {
    @Test
    fun `observation only reads events from the current grading request`() = runBlocking<Unit> {
        val questions = Mockito.mock(QuestionPort::class.java)
        Mockito.`when`(questions.findByIdAndUserIdAndDeletedAtIsNull(10, 7))
            .thenReturn(
                QuestionEntity(
                    id = 10,
                    userId = 7,
                    gradingRequestId = "current-request",
                    gradingStatus = AnswerGradingStatus.COMPLETED.name,
                ),
            )
        var observedRequestId: String? = null
        val progress = object : AnswerGradingProgressPort {
            override suspend fun append(
                recordId: Long,
                userId: Long,
                requestId: String,
                status: AnswerGradingStatus,
                errorMessage: String?,
                occurredAt: Instant,
            ): AnswerGradingProgress = error("Not used")

            override suspend fun findAfter(
                recordId: Long,
                userId: Long,
                requestId: String,
                afterId: Long,
                limit: Int,
            ): List<AnswerGradingProgress> {
                observedRequestId = requestId
                return listOf(
                    AnswerGradingProgress(
                        id = 4,
                        recordId = recordId,
                        requestId = requestId,
                        status = AnswerGradingStatus.COMPLETED,
                        occurredAt = Instant.parse("2026-07-27T00:00:00Z"),
                    ),
                )
            }
        }
        val service = AnswerGradingObservationService(questions, progress)

        val events = service.observe(
            principal = Principal(userId = 7, deviceId = "device-1", sessionId = 1, anonymous = false),
            recordId = 10,
            afterId = 3,
        ).toList()

        assertThat(observedRequestId).isEqualTo("current-request")
        assertThat(events).hasSize(1)
        assertThat(events.single().requestId).isEqualTo("current-request")
        assertThat(events.single().status).isEqualTo(AnswerGradingStatus.COMPLETED)
    }
}
