package com.buddystudy.backend.study

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.service.AnswerGradingTimeoutService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class AnswerGradingTimeoutServiceTest {
    @Test
    fun `watchdog atomically expires stalled grading and records terminal progress`() = runBlocking<Unit> {
        val now = Instant.parse("2026-07-29T00:10:00Z")
        val cutoff = now.minusSeconds(270)
        val questions = Mockito.mock(QuestionPort::class.java)
        val stalled = QuestionEntity(
            id = 31,
            userId = 7,
            gradingRequestId = "request-31",
            gradingStatus = AnswerGradingStatus.JUDGING,
            status = QuestionStatus.GRADING,
            gradingRequestedAt = now.minusSeconds(600),
        )
        Mockito.`when`(questions.findStalledGradings(cutoff, 100)).thenReturn(listOf(stalled))
        Mockito.`when`(
            questions.failStalledGrading(
                id = 31,
                requestId = "request-31",
                cutoff = cutoff,
                error = "채점 시간이 초과되었습니다. 다시 시도해 주세요.",
                now = now,
            ),
        ).thenReturn(true)
        Mockito.`when`(
            questions.updateGradingLastEventId(
                id = 31,
                requestId = "request-31",
                eventId = 1,
            ),
        ).thenReturn(true)
        var terminalStatus: AnswerGradingStatus? = null
        val progress = object : AnswerGradingProgressPort {
            override suspend fun append(
                recordId: Long,
                userId: Long,
                requestId: String,
                status: AnswerGradingStatus,
                questionStatus: QuestionStatus,
                errorMessage: String?,
                occurredAt: Instant,
            ): AnswerGradingProgress {
                terminalStatus = status
                return AnswerGradingProgress(
                    id = 1,
                    recordId = recordId,
                    requestId = requestId,
                    status = status,
                    questionStatus = questionStatus,
                    errorMessage = errorMessage,
                    occurredAt = occurredAt,
                )
            }

            override suspend fun findAfter(
                recordId: Long,
                userId: Long,
                requestId: String,
                afterId: Long,
                limit: Int,
            ): List<AnswerGradingProgress> = emptyList()
        }
        val properties = BuddyStudyProperties().apply {
            openai.gradingTimeoutSeconds = 600
        }
        val service = AnswerGradingTimeoutService(properties, questions, progress)

        assertThat(service.expireStalled(now)).isEqualTo(1)
        assertThat(terminalStatus).isEqualTo(AnswerGradingStatus.FAILED)
    }
}
