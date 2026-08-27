package com.buddystudy.backend.profile

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.profile.application.model.ReferralAccountSummary
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import com.buddystudy.backend.profile.application.service.ReferralCodeGenerator
import com.buddystudy.backend.profile.application.service.ReferralService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReferralServiceTest {
    private val now = Instant.parse("2026-08-27T00:00:00Z")
    private val users = FakeUsers(
        UserEntity(id = 1, status = UserStatus.ACTIVE),
        UserEntity(id = 2, status = UserStatus.ACTIVE),
    )
    private val referrals = FakeReferrals()
    private val service = ReferralService(
        users = users,
        referrals = referrals,
        codeGenerator = ReferralCodeGenerator { "BS-ABCDEFGH" },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `summary creates one stable referral code`() = runBlocking {
        val first = service.summary(principal(1))
        val second = service.summary(principal(1))

        assertThat(first.code).isEqualTo("BS-ABCDEFGH")
        assertThat(second.code).isEqualTo(first.code)
        assertThat(referrals.codes).hasSize(1)
    }

    @Test
    fun `redeeming gives both active users one cumulative Tier 2 month`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        val inviterPaidUntil = now.plusSeconds(10 * 24 * 60 * 60)
        referrals.rewardBases[1] = inviterPaidUntil

        val referredSummary = service.redeem(principal(2), " bs-abcdefgh ")
        val inviterSummary = service.summary(principal(1))

        assertThat(referredSummary.hasRedeemedReferral).isTrue()
        assertThat(referredSummary.rewardMonthsEarned).isEqualTo(1)
        assertThat(inviterSummary.successfulReferralCount).isEqualTo(1)
        assertThat(inviterSummary.rewardMonthsEarned).isEqualTo(1)
        assertThat(referrals.grants).hasSize(2)
        assertThat(referrals.grants.single { it.userId == 1L }.startsAt).isEqualTo(inviterPaidUntil)
        assertThat(referrals.grants.single { it.userId == 1L }.endsAt)
            .isEqualTo(Instant.parse("2026-10-06T00:00:00Z"))
        assertThat(referrals.grants.single { it.userId == 2L }.startsAt).isEqualTo(now)
        assertThat(referrals.grants.single { it.userId == 2L }.endsAt)
            .isEqualTo(Instant.parse("2026-09-27T00:00:00Z"))
    }

    @Test
    fun `a user cannot redeem twice or redeem their own code`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        service.redeem(principal(2), "BS-ABCDEFGH")

        assertThatThrownBy { runBlocking { service.redeem(principal(2), "BS-ABCDEFGH") } }
            .isInstanceOf(ApiException::class.java)
        assertThatThrownBy { runBlocking { service.redeem(principal(1), "BS-ABCDEFGH") } }
            .isInstanceOf(ApiException::class.java)
        assertThat(referrals.referrals).hasSize(1)
        assertThat(referrals.grants).hasSize(2)
    }

    @Test
    fun `anonymous accounts cannot access referral rewards`() {
        assertThatThrownBy { runBlocking { service.summary(principal(1, anonymous = true)) } }
            .isInstanceOf(ApiException::class.java)
    }

    private fun principal(userId: Long, anonymous: Boolean = false) = Principal(
        userId = userId,
        deviceId = "device-$userId",
        sessionId = userId,
        anonymous = anonymous,
        status = if (anonymous) "ANONYMOUS" else "ACTIVE",
    )

    private class FakeUsers(vararg rows: UserEntity) : UserPort {
        private val users = rows.associateBy { it.id }.toMutableMap()

        override suspend fun save(entity: UserEntity): UserEntity = entity.also { users[it.id] = it }
        override suspend fun findById(id: Long): UserEntity? = users[id]
        override suspend fun findAllById(ids: Iterable<Long>): List<UserEntity> = ids.mapNotNull(users::get)
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeReferrals : ReferralPort {
        val codes = mutableMapOf<Long, String>()
        val referrals = mutableListOf<Referral>()
        val grants = mutableListOf<Grant>()
        val rewardBases = mutableMapOf<Long, Instant>()

        override suspend fun codeForUser(userId: Long): String? = codes[userId]

        override suspend fun createCode(userId: Long, code: String, now: Instant): Boolean {
            if (code in codes.values || userId in codes) return false
            codes[userId] = code
            return true
        }

        override suspend fun inviterUserId(code: String): Long? = codes.entries.firstOrNull { it.value == code }?.key
        override suspend fun lockUsers(userIds: List<Long>): Int = userIds.distinct().size
        override suspend fun hasRedeemed(userId: Long): Boolean = referrals.any { it.referredUserId == userId }

        override suspend fun createReferral(
            inviterUserId: Long,
            referredUserId: Long,
            code: String,
            now: Instant,
        ): Long? {
            if (hasRedeemed(referredUserId)) return null
            val row = Referral((referrals.size + 1).toLong(), inviterUserId, referredUserId)
            referrals += row
            return row.id
        }

        override suspend fun rewardBase(userId: Long, now: Instant): Instant = rewardBases[userId] ?: now

        override suspend fun grantTier2Month(
            referralId: Long,
            beneficiaryUserId: Long,
            startsAt: Instant,
            endsAt: Instant,
            now: Instant,
        ): Boolean {
            if (grants.any { it.referralId == referralId && it.userId == beneficiaryUserId }) return true
            grants += Grant(referralId, beneficiaryUserId, startsAt, endsAt)
            rewardBases[beneficiaryUserId] = endsAt
            return true
        }

        override suspend fun accountSummary(userId: Long): ReferralAccountSummary {
            val userGrants = grants.filter { it.userId == userId }
            return ReferralAccountSummary(
                successfulReferralCount = referrals.count { it.inviterUserId == userId },
                rewardMonthsEarned = userGrants.size,
                rewardStartsAt = userGrants.minOfOrNull { it.startsAt },
                rewardEndsAt = userGrants.maxOfOrNull { it.endsAt },
                hasRedeemedReferral = referrals.any { it.referredUserId == userId },
            )
        }
    }

    data class Referral(val id: Long, val inviterUserId: Long, val referredUserId: Long)
    data class Grant(val referralId: Long, val userId: Long, val startsAt: Instant, val endsAt: Instant)
}
