package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.model.QuestionQuotaResponse
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class QuestionQuotaService(
    private val memberships: QuestionMembershipPort,
) : QuestionQuotaUseCase {
    @Transactional(readOnly = true)
    override suspend fun status(principal: Principal): QuestionQuotaResponse {
        val now = Instant.now()
        val status = memberships.quotaStatusForUser(principal.userId, MonthlyQuotaWindow.periodAt(now))
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Question quota was not found.")
        return QuestionQuotaResponse(
            usedCount = status.usedCount,
            monthlyLimit = status.monthlyQuestionLimit,
            remainingCount = (status.monthlyQuestionLimit - status.usedCount).coerceAtLeast(0),
            resetAt = MonthlyQuotaWindow.resetAt(now),
        )
    }
}
