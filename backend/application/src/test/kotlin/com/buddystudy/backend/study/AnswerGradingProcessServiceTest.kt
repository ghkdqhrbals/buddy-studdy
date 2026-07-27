package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.service.AnswerGradingProcessService
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class AnswerGradingProcessServiceTest {
    @Test
    fun `poll returns only durable events after the client cursor`() = runBlocking<Unit> {
        val questions = Mockito.mock(QuestionPort::class.java)
        Mockito.`when`(
            questions.findByGradingRequestIdAndUserIdAndDeletedAtIsNull("current-request", 7),
        ).thenReturn(
            QuestionEntity(
                id = 10,
                userId = 7,
                gradingRequestId = "current-request",
                gradingStatus = AnswerGradingStatus.COMPLETED.name,
                updatedAt = Instant.parse("2026-07-27T00:00:02Z"),
            ),
        )
        var observedRequestId: String? = null
        var observedAfterId: Long? = null
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
                observedAfterId = afterId
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
        val service = AnswerGradingProcessService(questions, progress)

        val process = service.get(
            principal = Principal(userId = 7, deviceId = "device-1", sessionId = 1, anonymous = false),
            correlationId = "current-request",
            afterId = 3,
        )

        assertThat(observedRequestId).isEqualTo("current-request")
        assertThat(observedAfterId).isEqualTo(3)
        assertThat(process.correlationId).isEqualTo("current-request")
        assertThat(process.terminal).isTrue()
        assertThat(process.pollAfterMs).isNull()
        assertThat(process.events).hasSize(1)
        assertThat(process.events.single().correlationId).isEqualTo("current-request")
        assertThat(process.events.single().status).isEqualTo(AnswerGradingStatus.COMPLETED)
    }
}
