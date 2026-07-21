package com.buddystudy.backend.study.application.openai

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

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
    fun resolveForQuestionGeneration(user: UserEntity?): OpenAIQuestionKey {
        val systemApiKey = properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")

        if (user == null) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.QUOTA_EXCEEDED, "Monthly question limit reached.")
        }

        val plan = memberships.activePlanForUser(user.id)
            ?: throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.QUOTA_EXCEEDED, "Monthly question limit reached.")
        val yearMonth = YearMonth.now(ZoneOffset.UTC)
        val consumed = memberships.tryConsumeMonthlySystemQuestion(
            userId = user.id,
            yearMonth = yearMonth,
            limit = plan.monthlyQuestionLimit,
            now = Instant.now(),
        )
        if (!consumed) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.QUOTA_EXCEEDED, "Monthly question limit reached.")
        }

        return OpenAIQuestionKey(
            apiKey = systemApiKey,
            quotaReservation = SystemQuestionQuotaReservation(user.id, yearMonth),
            user = user,
        )
    }

    fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        if (!key.usesSystemMembershipQuota) return
    }

    fun releaseQuestionReservation(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        val reservation = key.quotaReservation ?: return
        memberships.refundMonthlySystemQuestion(reservation.userId, reservation.yearMonth, now)
    }
}
