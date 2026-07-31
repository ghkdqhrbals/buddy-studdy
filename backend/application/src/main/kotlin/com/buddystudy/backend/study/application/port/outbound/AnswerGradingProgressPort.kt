package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import java.time.Instant

interface AnswerGradingProgressPort {
    suspend fun append(
        recordId: Long,
        userId: Long,
        requestId: String,
        status: AnswerGradingStatus,
        errorMessage: String?,
        occurredAt: Instant,
    ): AnswerGradingProgress

    suspend fun findAfter(
        recordId: Long,
        userId: Long,
        requestId: String,
        afterId: Long,
        limit: Int,
    ): List<AnswerGradingProgress>
}
