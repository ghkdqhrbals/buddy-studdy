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
    fun `voice only tier patch preserves the existing question limit`() = runBlocking {
        val port = FakeAdminManagementPort()
        val service = AdminManagementService(port)

        val result = service.updateTier("TIER2", monthlyQuestionLimit = null, monthlyVoiceSecondsLimit = 18_000)

        assertThat(port.lastTierQuestionLimit).isNull()
        assertThat(port.lastTierVoiceLimit).isEqualTo(18_000)
        assertThat(result.monthlyQuestionLimit).isEqualTo(30)
        assertThat(result.monthlyVoiceSecondsLimit).isEqualTo(18_000)
    }

    @Test
    fun `empty tier patch is rejected`() {
        val service = AdminManagementService(FakeAdminManagementPort())

        assertThatThrownBy { runBlocking { service.updateTier("TIER2", null, null) } }
            .isInstanceOf(ApiException::class.java)
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

    @Test
    fun `administrator can lower a paid users persistent voice cap and restore the tier default`() = runBlocking {
        val port = FakeAdminManagementPort()
        val service = AdminManagementService(port)

        val capped = service.setVoiceLimit(userId = 7, monthlyVoiceSecondsLimitOverride = 900)
        assertThat(port.lastVoiceLimit).isEqualTo(900)
        assertThat(capped.monthlyVoiceSecondsLimit).isEqualTo(900)
        assertThat(capped.monthlyVoiceSecondsLimitOverride).isEqualTo(900)

        val restored = service.setVoiceLimit(userId = 7, monthlyVoiceSecondsLimitOverride = null)
        assertThat(port.voiceLimitWasSet).isTrue()
        assertThat(port.lastVoiceLimit).isNull()
        assertThat(restored.monthlyVoiceSecondsLimit).isEqualTo(18_000)
        assertThat(restored.monthlyVoiceSecondsLimitOverride).isNull()
    }

    private class FakeAdminManagementPort : AdminManagementPort {
        var lastQuery: String? = null
        var lastLimit = 0
        var lastOffset = 0
        var lastAssignment: AssignUserPlanCommand? = null
        var lastCurrentPeriodLimit: Int? = null
        var lastTierQuestionLimit: Int? = null
        var lastTierVoiceLimit: Int? = null
        var lastVoiceLimit: Int? = null
        var voiceLimitWasSet = false

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
        ): AdminMembershipTierResponse {
            lastTierQuestionLimit = monthlyQuestionLimit
            return AdminMembershipTierResponse(tierCode, monthlyQuestionLimit, "Updated")
        }

        override suspend fun updateTierVoiceSecondsLimit(
            tierCode: String,
            monthlyVoiceSecondsLimit: Int,
        ): AdminMembershipTierResponse {
            lastTierVoiceLimit = monthlyVoiceSecondsLimit
            return AdminMembershipTierResponse(tierCode, 30, "Updated", monthlyVoiceSecondsLimit)
        }

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

        override suspend fun setVoiceLimit(
            userId: Long,
            monthlyVoiceSecondsLimitOverride: Int?,
        ): AdminUserSummary {
            lastVoiceLimit = monthlyVoiceSecondsLimitOverride
            voiceLimitWasSet = true
            val limit = monthlyVoiceSecondsLimitOverride ?: 18_000
            return summary(
                monthlyVoiceSecondsLimit = limit,
                monthlyVoiceSecondsLimitOverride = monthlyVoiceSecondsLimitOverride,
            )
        }

        private fun summary(
            monthlyLimit: Int = 30,
            currentPeriodQuestionLimitOverride: Int? = null,
            monthlyVoiceSecondsLimit: Int = 0,
            monthlyVoiceSecondsLimitOverride: Int? = null,
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
            periodStartedAt = Instant.parse("2026-07-01T00:00:00Z"),
            resetAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdAt = Instant.parse("2026-07-01T00:00:00Z"),
            monthlyVoiceSecondsLimit = monthlyVoiceSecondsLimit,
            monthlyVoiceSecondsLimitOverride = monthlyVoiceSecondsLimitOverride,
        )
    }
}
