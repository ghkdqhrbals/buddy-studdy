package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.AnswerGradingProcessResponse
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.backend.study.application.model.toResponse
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class AnswerGradingProcessService(
    private val questions: QuestionPort,
    private val progress: AnswerGradingProgressPort,
) : GetAnswerGradingProcessUseCase {
    override suspend fun get(
        principal: Principal,
        correlationId: String,
        afterId: Long,
    ): AnswerGradingProcessResponse {
        val question = questions.findByGradingRequestIdAndUserIdAndDeletedAtIsNull(
            correlationId,
            principal.userId,
        ) ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Answer grading process not found.")
        val status = question.gradingStatus
            ?: throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.VALIDATION_ERROR,
                "Answer grading has not been requested.",
            )
        val events = progress.findAfter(
            recordId = question.id,
            userId = principal.userId,
            requestId = correlationId,
            afterId = afterId.coerceAtLeast(0),
            limit = EVENT_PAGE_SIZE,
        )
        return AnswerGradingProcessResponse(
            correlationId = correlationId,
            recordId = question.id.toString(),
            status = status,
            terminal = status.terminal,
            pollAfterMs = if (status.terminal) null else POLL_INTERVAL_MS,
            events = events.map { it.toResponse() },
            errorMessage = question.gradingError,
            updatedAt = question.updatedAt,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val EVENT_PAGE_SIZE = 50
    }
}
