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
    val reservationKey: String,
)

@Component
class OpenAIQuestionKeyProvider(
    private val userContentKeys: UserContentOpenAIKeyProvider,
    private val memberships: QuestionMembershipPort,
) {
    suspend fun resolveForQuestionGeneration(
        user: UserEntity?,
        reservationKey: String = java.util.UUID.randomUUID().toString(),
    ): OpenAIQuestionKey {
        val userContentApiKey = userContentKeys.requireApiKey()
        val now = Instant.now()

        if (user == null) {
            throw monthlyQuotaExceeded(now, now)
        }

        val quotaPeriod = MonthlyQuotaWindow.periodAt(user.createdAt, now)
        val quota = memberships.quotaStatusForUser(user.id, now)
            ?: throw monthlyQuotaExceeded(user.createdAt, now)
        val activePeriodStartedAt = quota.periodStartedAt ?: quotaPeriod.startedAt
        val consumed = memberships.reserveMonthlySystemQuestion(
            userId = user.id,
            periodStartedAt = activePeriodStartedAt,
            reservationKey = reservationKey,
            correlationId = reservationKey,
            now = now,
        )
        if (!consumed) {
            throw ApiException(
                status = HttpStatus.FORBIDDEN,
                code = ApiErrorCode.QUOTA_EXCEEDED,
                message = "Monthly question limit reached.",
                metadata = mapOf(
                    "quotaPeriod" to "MONTHLY",
                    "quotaPeriodStartedAt" to activePeriodStartedAt.toString(),
                    "quotaResetAt" to quota.resetAt?.toString(),
                    "quotaTimeZone" to "Z",
                    "tierCode" to quota.tierCode,
                    "remainingCount" to 0,
                ),
            )
        }

        return OpenAIQuestionKey(
            apiKey = userContentApiKey,
            quotaReservation = SystemQuestionQuotaReservation(user.id, activePeriodStartedAt, reservationKey),
            user = user,
        )
    }

    fun resolveReservedQuestionGeneration(
        user: UserEntity,
        periodStartedAt: Instant,
        reservationKey: String,
    ): OpenAIQuestionKey {
        return OpenAIQuestionKey(
            apiKey = userContentKeys.requireApiKey(),
            quotaReservation = SystemQuestionQuotaReservation(user.id, periodStartedAt, reservationKey),
            user = user,
        )
    }

    suspend fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        val reservation = key.quotaReservation ?: return
        memberships.commitMonthlySystemQuestion(reservation.reservationKey, now)
    }

    suspend fun releaseQuestionReservation(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        val reservation = key.quotaReservation ?: return
        memberships.releaseMonthlySystemQuestion(
            reservation.userId,
            reservation.periodStartedAt,
            reservation.reservationKey,
            "Question generation did not complete",
            now,
        )
    }

    suspend fun releaseQuestionReservation(
        userId: Long,
        periodStartedAt: Instant,
        reservationKey: String,
        reason: String?,
        now: Instant = Instant.now(),
    ) {
        memberships.releaseMonthlySystemQuestion(userId, periodStartedAt, reservationKey, reason, now)
    }

    private fun monthlyQuotaExceeded(accountCreatedAt: Instant, now: Instant) =
        ApiException(
            status = HttpStatus.FORBIDDEN,
            code = ApiErrorCode.QUOTA_EXCEEDED,
            message = "Monthly question limit reached.",
            metadata = MonthlyQuotaWindow.exceededMetadata(accountCreatedAt, now),
        )
}
