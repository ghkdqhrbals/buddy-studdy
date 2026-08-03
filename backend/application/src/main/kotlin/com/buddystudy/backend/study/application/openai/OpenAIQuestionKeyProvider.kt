package com.buddystudy.backend.study.application.openai

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

data class OpenAIQuestionKey(
    val apiKey: String,
    val quotaReservation: SystemQuestionQuotaReservation? = null,
    val user: UserEntity?,
) {
    val usesSystemMembershipQuota: Boolean get() = quotaReservation != null
}

data class SystemQuestionQuotaReservation(
    val userId: Long,
    val periodStartedAt: Instant,
)

@Component
class OpenAIQuestionKeyProvider(
    private val userContentKeys: UserContentOpenAIKeyProvider,
    private val memberships: QuestionMembershipPort,
) {
    suspend fun resolveForQuestionGeneration(user: UserEntity?): OpenAIQuestionKey {
        val userContentApiKey = userContentKeys.requireApiKey()
        val now = Instant.now()

        if (user == null) {
            throw monthlyQuotaExceeded(now, now)
        }

        val quotaPeriod = MonthlyQuotaWindow.periodAt(user.createdAt, now)
        val quota = memberships.quotaStatusForUser(user.id, quotaPeriod.startedAt)
            ?: throw monthlyQuotaExceeded(user.createdAt, now)
        val consumed = memberships.tryConsumeMonthlySystemQuestion(
            userId = user.id,
            periodStartedAt = quotaPeriod.startedAt,
            limit = quota.monthlyQuestionLimit,
            now = now,
        )
        if (!consumed) {
            throw monthlyQuotaExceeded(user.createdAt, now)
        }

        return OpenAIQuestionKey(
            apiKey = userContentApiKey,
            quotaReservation = SystemQuestionQuotaReservation(user.id, quotaPeriod.startedAt),
            user = user,
        )
    }

    fun resolveReservedQuestionGeneration(user: UserEntity, periodStartedAt: Instant): OpenAIQuestionKey {
        return OpenAIQuestionKey(
            apiKey = userContentKeys.requireApiKey(),
            quotaReservation = SystemQuestionQuotaReservation(user.id, periodStartedAt),
            user = user,
        )
    }

    suspend fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        if (!key.usesSystemMembershipQuota) return
    }

    suspend fun releaseQuestionReservation(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        val reservation = key.quotaReservation ?: return
        memberships.refundMonthlySystemQuestion(reservation.userId, reservation.periodStartedAt, now)
    }

    suspend fun releaseQuestionReservation(userId: Long, periodStartedAt: Instant, now: Instant = Instant.now()) {
        memberships.refundMonthlySystemQuestion(userId, periodStartedAt, now)
    }

    private fun monthlyQuotaExceeded(accountCreatedAt: Instant, now: Instant) =
        ApiException(
            status = HttpStatus.FORBIDDEN,
            code = ApiErrorCode.QUOTA_EXCEEDED,
            message = "Monthly question limit reached.",
            metadata = MonthlyQuotaWindow.exceededMetadata(accountCreatedAt, now),
        )
}
