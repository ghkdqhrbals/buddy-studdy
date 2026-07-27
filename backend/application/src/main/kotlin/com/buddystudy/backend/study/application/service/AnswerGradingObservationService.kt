package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.AnswerGradingProgressResponse
import com.buddystudy.backend.study.application.model.toResponse
import com.buddystudy.backend.study.application.port.inbound.ObserveAnswerGradingUseCase
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class AnswerGradingObservationService(
    private val questions: QuestionPort,
    private val progress: AnswerGradingProgressPort,
) : ObserveAnswerGradingUseCase {
    override suspend fun observe(
        principal: Principal,
        recordId: Long,
        afterId: Long,
    ): Flow<AnswerGradingProgressResponse> {
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val requestId = question.gradingRequestId
            ?: throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.VALIDATION_ERROR,
                "Answer grading has not been requested.",
            )

        return flow {
            var cursor = afterId.coerceAtLeast(0)
            while (true) {
                val events = progress.findAfter(recordId, principal.userId, requestId, cursor, 50)
                if (events.isEmpty()) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                for (event in events) {
                    emit(event.toResponse())
                    cursor = event.id
                    if (event.status.terminal) return@flow
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
