package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import com.buddystudy.backend.study.application.service.QuestionQuotaService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth

class QuestionQuotaServiceTest {
    private val principal = Principal(userId = 7, deviceId = "device-1", sessionId = 1, anonymous = false)

    @Test
    fun `quota response exposes remaining limit and next reset`() = runBlocking {
        val before = Instant.now()
        val service = QuestionQuotaService(FakeMemberships(usedCount = 12, monthlyLimit = 30))

        val response = service.status(principal)

        assertThat(response.usedCount).isEqualTo(12)
        assertThat(response.monthlyLimit).isEqualTo(30)
        assertThat(response.remainingCount).isEqualTo(18)
        assertThat(response.resetAt).isAfter(before)
        assertThat(response.resetAt).isBefore(before.plusSeconds(32L * 24 * 60 * 60))
    }

    @Test
    fun `quota remaining count never becomes negative`() = runBlocking {
        val service = QuestionQuotaService(FakeMemberships(usedCount = 35, monthlyLimit = 30))

        val response = service.status(principal)

        assertThat(response.remainingCount).isZero()
    }

    private class FakeMemberships(
        private val usedCount: Int,
        private val monthlyLimit: Int,
    ) : QuestionMembershipPort {
        override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan =
            QuestionMembershipPlan("TIER1", monthlyLimit)

        override suspend fun quotaStatusForUser(userId: Long, yearMonth: YearMonth): QuestionQuotaStatus =
            QuestionQuotaStatus("TIER1", usedCount, monthlyLimit)

        override suspend fun tryConsumeMonthlySystemQuestion(
            userId: Long,
            yearMonth: YearMonth,
            limit: Int,
            now: Instant,
        ): Boolean = false

        override suspend fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) = Unit
    }
}
