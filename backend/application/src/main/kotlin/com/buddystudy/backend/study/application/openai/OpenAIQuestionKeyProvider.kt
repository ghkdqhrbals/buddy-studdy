package com.buddystudy.backend.study.application.openai

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.YearMonth

data class OpenAIQuestionKey(
    val apiKey: String,
    val quotaReservation: SystemQuestionQuotaReservation? = null,
    val user: UserEntity?,
) {
    val usesSystemMembershipQuota: Boolean get() = quotaReservation != null
}

data class SystemQuestionQuotaReservation(
    val userId: Long,
    val yearMonth: YearMonth,
)

@Component
class OpenAIQuestionKeyProvider(
    private val properties: BuddyStudyProperties,
    private val memberships: QuestionMembershipPort,
) {
    suspend fun resolveForQuestionGeneration(user: UserEntity?): OpenAIQuestionKey {
        val systemApiKey = properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")
        val now = Instant.now()

        if (user == null) {
            throw monthlyQuotaExceeded(now)
        }

        val plan = memberships.activePlanForUser(user.id)
            ?: throw monthlyQuotaExceeded(now)
        val yearMonth = MonthlyQuotaWindow.periodAt(now)
        val consumed = memberships.tryConsumeMonthlySystemQuestion(
            userId = user.id,
            yearMonth = yearMonth,
            limit = plan.monthlyQuestionLimit,
            now = now,
        )
        if (!consumed) {
            throw monthlyQuotaExceeded(now)
        }

        return OpenAIQuestionKey(
            apiKey = systemApiKey,
            quotaReservation = SystemQuestionQuotaReservation(user.id, yearMonth),
            user = user,
        )
    }

    suspend fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        if (!key.usesSystemMembershipQuota) return
    }

    suspend fun releaseQuestionReservation(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        val reservation = key.quotaReservation ?: return
        memberships.refundMonthlySystemQuestion(reservation.userId, reservation.yearMonth, now)
    }

    private fun monthlyQuotaExceeded(now: Instant) =
        ApiException(
            status = HttpStatus.FORBIDDEN,
            code = ApiErrorCode.QUOTA_EXCEEDED,
            message = "Monthly question limit reached.",
            metadata = MonthlyQuotaWindow.exceededMetadata(now),
        )
}
