package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import com.buddystudy.backend.study.application.service.QuestionQuotaService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class QuestionQuotaServiceTest {
    private val principal = Principal(userId = 7, deviceId = "device-1", sessionId = 1, anonymous = false)

    @Test
    fun `quota response exposes remaining limit and next reset`() = runBlocking {
        val before = Instant.now()
        val service = service(usedCount = 12, monthlyLimit = 30)

        val response = service.status(principal)

        assertThat(response.usedCount).isEqualTo(12)
        assertThat(response.monthlyLimit).isEqualTo(30)
        assertThat(response.remainingCount).isEqualTo(18)
        assertThat(response.resetAt).isAfter(before)
        assertThat(response.resetAt).isBefore(before.plusSeconds(32L * 24 * 60 * 60))
    }

    @Test
    fun `quota remaining count never becomes negative`() = runBlocking {
        val service = service(usedCount = 35, monthlyLimit = 30)

        val response = service.status(principal)

        assertThat(response.remainingCount).isZero()
    }

    private fun service(usedCount: Int, monthlyLimit: Int) =
        QuestionQuotaService(
            memberships = FakeMemberships(usedCount, monthlyLimit),
            users = FakeUsers(
                UserEntity(
                    id = principal.userId,
                    createdAt = Instant.now().minusSeconds(10L * 24 * 60 * 60),
                ),
            ),
        )

    private class FakeMemberships(
        private val usedCount: Int,
        private val monthlyLimit: Int,
    ) : QuestionMembershipPort {
        override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan =
            QuestionMembershipPlan("TIER1", monthlyLimit)

        override suspend fun quotaStatusForUser(userId: Long, periodStartedAt: Instant): QuestionQuotaStatus =
            QuestionQuotaStatus("TIER1", usedCount, monthlyLimit)

        override suspend fun tryConsumeMonthlySystemQuestion(
            userId: Long,
            periodStartedAt: Instant,
            limit: Int,
            now: Instant,
        ): Boolean = false

        override suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant) = Unit
    }

    private class FakeUsers(private val user: UserEntity) : UserPort {
        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? = user.takeIf { it.id == id }
        override suspend fun findAllById(ids: Iterable<Long>): List<UserEntity> =
            listOfNotNull(user.takeIf { it.id in ids })
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }
}
