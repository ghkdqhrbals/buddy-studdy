package com.buddystudy.backend.admin.management

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.admin.management.application.service.AdminManagementService
import com.buddystudy.backend.common.application.error.ApiException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminManagementServiceTest {
    @Test
    fun `user search is paginated and input is normalized`() = runBlocking {
        val port = FakeAdminManagementPort()
        val service = AdminManagementService(port)

        service.users(query = "  jamma  ", limit = 500, offset = -10)

        assertThat(port.lastQuery).isEqualTo("jamma")
        assertThat(port.lastLimit).isEqualTo(100)
        assertThat(port.lastOffset).isZero()
    }

    @Test
    fun `administrator can assign plan and a personal monthly limit`() = runBlocking {
        val port = FakeAdminManagementPort()
        val service = AdminManagementService(port)

        val result = service.assignPlan(
            userId = 7,
            command = AssignUserPlanCommand(" TIER2 ", monthlyQuestionLimitOverride = 240),
        )

        assertThat(port.lastAssignment).isEqualTo(AssignUserPlanCommand("TIER2", 240))
        assertThat(result.monthlyLimit).isEqualTo(240)
    }

    @Test
    fun `invalid monthly limit is rejected before persistence`() {
        val service = AdminManagementService(FakeAdminManagementPort())

        assertThatThrownBy {
            runBlocking {
                service.updateTier("TIER1", -1)
            }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `administrator can override only the current quota period`() = runBlocking {
        val port = FakeAdminManagementPort()
        val service = AdminManagementService(port)

        val result = service.setCurrentPeriodQuestionLimit(userId = 7, questionLimitOverride = 45)

        assertThat(port.lastCurrentPeriodLimit).isEqualTo(45)
        assertThat(result.currentPeriodQuestionLimitOverride).isEqualTo(45)
        assertThat(result.monthlyLimit).isEqualTo(45)
    }

    private class FakeAdminManagementPort : AdminManagementPort {
        var lastQuery: String? = null
        var lastLimit = 0
        var lastOffset = 0
        var lastAssignment: AssignUserPlanCommand? = null
        var lastCurrentPeriodLimit: Int? = null

        override suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse {
            lastQuery = query
            lastLimit = limit
            lastOffset = offset
            return AdminUserPageResponse(emptyList(), 0, limit, offset)
        }

        override suspend fun user(userId: Long): AdminUserSummary = summary()

        override suspend fun tiers(): List<AdminMembershipTierResponse> =
            listOf(AdminMembershipTierResponse("TIER1", 30, "Free"))

        override suspend fun updateTier(
            tierCode: String,
            monthlyQuestionLimit: Int,
        ): AdminMembershipTierResponse = AdminMembershipTierResponse(tierCode, monthlyQuestionLimit, "Updated")

        override suspend fun assignPlan(
            userId: Long,
            command: AssignUserPlanCommand,
        ): AdminUserSummary {
            lastAssignment = command
            return summary(monthlyLimit = command.monthlyQuestionLimitOverride ?: 30)
        }

        override suspend fun setCurrentPeriodQuestionLimit(
            userId: Long,
            questionLimitOverride: Int?,
        ): AdminUserSummary {
            lastCurrentPeriodLimit = questionLimitOverride
            return summary(
                monthlyLimit = questionLimitOverride ?: 30,
                currentPeriodQuestionLimitOverride = questionLimitOverride,
            )
        }

        private fun summary(
            monthlyLimit: Int = 30,
            currentPeriodQuestionLimitOverride: Int? = null,
        ) = AdminUserSummary(
            id = 7,
            email = "user@example.com",
            displayName = "Jamma",
            provider = "GOOGLE",
            status = "ACTIVE",
            tierCode = "TIER1",
            tierDescription = "Free",
            monthlyLimit = monthlyLimit,
            monthlyLimitOverride = null,
            currentPeriodQuestionLimitOverride = currentPeriodQuestionLimitOverride,
            usedCount = 0,
            remainingCount = monthlyLimit,
            resetAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        )
    }
}
