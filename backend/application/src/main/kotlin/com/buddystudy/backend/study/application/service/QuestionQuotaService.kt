package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
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
    private val users: UserPort,
) : QuestionQuotaUseCase {
    @Transactional(readOnly = true)
    override suspend fun status(principal: Principal): QuestionQuotaResponse {
        val now = Instant.now()
        val user = users.findById(principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "User was not found.")
        val quotaPeriod = MonthlyQuotaWindow.periodAt(user.createdAt, now)
        val status = memberships.quotaStatusForUser(principal.userId, now)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Question quota was not found.")
        return QuestionQuotaResponse(
            usedCount = status.usedCount,
            monthlyLimit = status.monthlyQuestionLimit,
            remainingCount = (status.monthlyQuestionLimit - status.usedCount - status.reservedCount).coerceAtLeast(0),
            resetAt = status.resetAt ?: quotaPeriod.resetAt,
            tierCode = status.tierCode,
            periodStartedAt = status.periodStartedAt ?: quotaPeriod.startedAt,
            reservedCount = status.reservedCount,
            baseLimit = status.baseLimit,
            bonusLimit = status.bonusLimit,
            anchorType = status.anchorType,
            policyVersion = status.policyVersion,
        )
    }
}
