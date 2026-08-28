package com.buddystudy.backend.profile

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.PendingReferralAttribution
import com.buddystudy.backend.profile.application.model.PendingReferralAttributionStatus
import com.buddystudy.backend.profile.application.model.ReferralAccountSummary
import com.buddystudy.backend.profile.application.model.ReferralRecord
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import com.buddystudy.backend.profile.application.service.ReferralCodeGenerator
import com.buddystudy.backend.profile.application.service.ReferralLinkProvider
import com.buddystudy.backend.profile.application.service.ReferralRewardManager
import com.buddystudy.backend.profile.application.service.ReferralService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReferralServiceTest {
    private val now = Instant.parse("2026-08-27T00:00:00Z")
    private val users = FakeUsers(
        activeUser(1),
        activeUser(2),
        activeUser(3),
        UserEntity(id = 4, status = UserStatus.PENDING_TERMS, createdAt = now, updatedAt = now),
    )
    private val referrals = FakeReferrals()
    private val properties = BuddyStudyProperties().apply {
        referral.publicBaseUrl = "https://api.ghkdqhrbals.org/"
        referral.manualRedemptionGraceHours = 24
    }
    private var generatedCodeIndex = 0
    private val links = ReferralLinkProvider(properties)
    private val rewards = ReferralRewardManager(users, referrals, properties)
    private val service = ReferralService(
        users = users,
        referrals = referrals,
        codeGenerator = ReferralCodeGenerator { GENERATED_CODES[generatedCodeIndex++] },
        rewards = rewards,
        links = links,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `summary creates one stable canonical referral URL`() = runBlocking {
        val first = service.summary(principal(1))
        val second = service.summary(principal(1))

        assertThat(first.code).isEqualTo("BS-ABCDEFGH")
        assertThat(first.referralUrl).isEqualTo("https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
        assertThat(second).isEqualTo(first)
        assertThat(referrals.codes).hasSize(1)
    }

    @Test
    fun `manual signup recovery gives both active users one cumulative Tier 2 month`() = runBlocking {
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
    fun `same manual code is idempotent while another code conflicts`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        referrals.codes[3] = "BS-JKLMNPQR"
        service.redeem(principal(2), "BS-ABCDEFGH")

        service.redeem(principal(2), "BS-ABCDEFGH")

        assertThat(referrals.referralRows).hasSize(1)
        assertThat(referrals.grants).hasSize(2)
        assertThatThrownBy { runBlocking { service.redeem(principal(2), "BS-JKLMNPQR") } }
            .isInstanceOf(ApiException::class.java)
            .extracting("status")
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `an old active account cannot create a manual referral attribution`() {
        referrals.codes[1] = "BS-ABCDEFGH"
        users.row(2)!!.createdAt = now.minusSeconds(25 * 60 * 60)

        assertThatThrownBy { runBlocking { service.redeem(principal(2), "BS-ABCDEFGH") } }
            .isInstanceOf(ApiException::class.java)
            .extracting("status")
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(referrals.referralRows).isEmpty()
        assertThat(referrals.grants).isEmpty()
    }

    @Test
    fun `pending signup keeps first valid attribution without failing later promo codes`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        referrals.codes[3] = "BS-JKLMNPQR"

        assertThat(rewards.capturePendingAttribution(4, "not-a-code", now)).isFalse()
        assertThat(rewards.capturePendingAttribution(4, "BS-ABCDEFGH", now)).isTrue()
        assertThat(rewards.capturePendingAttribution(4, "BS-JKLMNPQR", now)).isTrue()
        assertThat(rewards.capturePendingAttribution(4, "invalid-referral", now)).isTrue()
        assertThat(rewards.capturePendingAttribution(4, null, now)).isTrue()

        assertThat(referrals.pendingRows.getValue(4).referralCode).isEqualTo("BS-ABCDEFGH")
    }

    @Test
    fun `pending attribution insert race converges through a locking current read`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        referrals.simulateConcurrentPendingInsert = true

        assertThat(rewards.capturePendingAttribution(4, "BS-ABCDEFGH", now)).isTrue()

        assertThat(referrals.lockedPendingReads).isEqualTo(1)
        assertThat(referrals.pendingRows.getValue(4).referralCode).isEqualTo("BS-ABCDEFGH")
    }

    @Test
    fun `manual redemption persists a rewarded claim`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"

        service.redeem(principal(2), "BS-ABCDEFGH")

        assertThat(referrals.pendingRows.getValue(2).status).isEqualTo(PendingReferralAttributionStatus.REWARDED)
        assertThat(referrals.pendingRows.getValue(2).referralCode).isEqualTo("BS-ABCDEFGH")
    }

    @Test
    fun `deleted inviter tombstone keeps same code idempotent and another code conflicting`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        referrals.codes[3] = "BS-JKLMNPQR"
        service.redeem(principal(2), "BS-ABCDEFGH")
        referrals.codes.remove(1)
        referrals.referralRows.replaceAll { row ->
            if (row.referredUserId == 2L) row.copy(inviterUserId = null) else row
        }
        referrals.grants.removeIf { it.userId == 1L }

        val summary = service.redeem(principal(2), "BS-ABCDEFGH")

        assertThat(summary.hasRedeemedReferral).isTrue()
        assertThat(referrals.grants.count { it.userId == 2L }).isEqualTo(1)
        assertThatThrownBy { runBlocking { service.redeem(principal(2), "BS-JKLMNPQR") } }
            .isInstanceOf(ApiException::class.java)
            .extracting("status")
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `legacy untracked referral membership blocks another redemption conservatively`() {
        referrals.codes[1] = "BS-ABCDEFGH"
        referrals.untrackedMembershipUsers += 2

        assertThatThrownBy { runBlocking { service.redeem(principal(2), "BS-ABCDEFGH") } }
            .isInstanceOf(ApiException::class.java)
            .extracting("status")
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(runBlocking { service.summary(principal(2)) }.hasRedeemedReferral).isTrue()
    }

    @Test
    fun `activation rewards both accounts exactly once and resolves pending attribution`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"
        rewards.capturePendingAttribution(4, "BS-ABCDEFGH", now)
        users.row(4)!!.status = UserStatus.ACTIVE

        rewards.activatePendingAttribution(4, now)
        rewards.activatePendingAttribution(4, now.plusSeconds(1))

        assertThat(referrals.pendingRows.getValue(4).status).isEqualTo(PendingReferralAttributionStatus.REWARDED)
        assertThat(referrals.referralRows).hasSize(1)
        assertThat(referrals.grants.map { it.userId }).containsExactlyInAnyOrder(1, 4)
    }

    @Test
    fun `public landing resolves active code and hides malformed code as not found`() = runBlocking {
        referrals.codes[1] = "BS-ABCDEFGH"

        val landing = service.landing("bs-abcdefgh")

        assertThat(landing.referralUrl).isEqualTo("https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
        assertThat(landing.appDeepLink).isEqualTo("buddystudy://referrals/BS-ABCDEFGH")
        assertThat(landing.appStoreAppId).isEqualTo(6774108938)
        assertThatThrownBy { runBlocking { service.landing("bad") } }
            .isInstanceOf(ApiException::class.java)
            .extracting("status")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `anonymous accounts cannot access referral rewards`() {
        assertThatThrownBy { runBlocking { service.summary(principal(1, anonymous = true)) } }
            .isInstanceOf(ApiException::class.java)
    }

    private fun activeUser(id: Long) = UserEntity(
        id = id,
        status = UserStatus.ACTIVE,
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(60),
    )

    private fun principal(userId: Long, anonymous: Boolean = false) = Principal(
        userId = userId,
        deviceId = "device-$userId",
        sessionId = userId,
        anonymous = anonymous,
        status = if (anonymous) "ANONYMOUS" else "ACTIVE",
    )

    private class FakeUsers(vararg rows: UserEntity) : UserPort {
        private val users = rows.associateBy { it.id }.toMutableMap()

        fun row(id: Long): UserEntity? = users[id]
        override suspend fun save(entity: UserEntity): UserEntity = entity.also { users[it.id] = it }
        override suspend fun findById(id: Long): UserEntity? = users[id]
        override suspend fun findAllById(ids: Iterable<Long>): List<UserEntity> = ids.mapNotNull(users::get)
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeReferrals : ReferralPort {
        val codes = mutableMapOf<Long, String>()
        val referralRows = mutableListOf<ReferralRecord>()
        val pendingRows = mutableMapOf<Long, PendingReferralAttribution>()
        val grants = mutableListOf<Grant>()
        val rewardBases = mutableMapOf<Long, Instant>()
        val untrackedMembershipUsers = mutableSetOf<Long>()
        var lockedPendingReads = 0
        var simulateConcurrentPendingInsert = false

        override suspend fun codeForUser(userId: Long): String? = codes[userId]
        override suspend fun lockCodeForUser(userId: Long): String? = codes[userId]

        override suspend fun createCode(userId: Long, code: String, now: Instant): Boolean {
            if (code in codes.values || userId in codes) return false
            codes[userId] = code
            return true
        }

        override suspend fun inviterUserId(code: String): Long? = codes.entries.firstOrNull { it.value == code }?.key
        override suspend fun lockUsers(userIds: List<Long>): Int = userIds.distinct().size
        override suspend fun referralForReferredUser(userId: Long): ReferralRecord? =
            referralRows.firstOrNull { it.referredUserId == userId }
        override suspend fun lockReferralForReferredUser(userId: Long): ReferralRecord? =
            referralForReferredUser(userId)

        override suspend fun createReferral(
            inviterUserId: Long,
            referredUserId: Long,
            code: String,
            now: Instant,
        ): Long? {
            if (referralForReferredUser(referredUserId) != null) return null
            val row = ReferralRecord((referralRows.size + 1).toLong(), inviterUserId, referredUserId, code)
            referralRows += row
            return row.id
        }

        override suspend fun pendingAttribution(userId: Long): PendingReferralAttribution? = pendingRows[userId]
        override suspend fun lockPendingAttribution(userId: Long): PendingReferralAttribution? {
            lockedPendingReads += 1
            return pendingRows[userId]
        }

        override suspend fun createPendingAttribution(
            inviterUserId: Long,
            referredUserId: Long,
            code: String,
            now: Instant,
        ): Boolean {
            if (simulateConcurrentPendingInsert) {
                simulateConcurrentPendingInsert = false
                pendingRows[referredUserId] = PendingReferralAttribution(
                    id = (pendingRows.size + 1).toLong(),
                    inviterUserId = inviterUserId,
                    referredUserId = referredUserId,
                    referralCode = code,
                    status = PendingReferralAttributionStatus.PENDING,
                )
                return false
            }
            if (referredUserId in pendingRows) return false
            pendingRows[referredUserId] = PendingReferralAttribution(
                id = (pendingRows.size + 1).toLong(),
                inviterUserId = inviterUserId,
                referredUserId = referredUserId,
                referralCode = code,
                status = PendingReferralAttributionStatus.PENDING,
            )
            return true
        }

        override suspend fun createRewardedAttribution(
            inviterUserId: Long?,
            referredUserId: Long,
            code: String,
            now: Instant,
        ): Boolean {
            if (referredUserId in pendingRows) return false
            pendingRows[referredUserId] = PendingReferralAttribution(
                id = (pendingRows.size + 1).toLong(),
                inviterUserId = inviterUserId,
                referredUserId = referredUserId,
                referralCode = code,
                status = PendingReferralAttributionStatus.REWARDED,
            )
            return true
        }

        override suspend fun completePendingAttribution(
            attributionId: Long,
            expectedStatus: PendingReferralAttributionStatus,
            status: PendingReferralAttributionStatus,
            now: Instant,
        ): Boolean {
            val entry = pendingRows.entries.firstOrNull { it.value.id == attributionId } ?: return false
            if (entry.value.status != expectedStatus) return false
            entry.setValue(entry.value.copy(status = status))
            return true
        }

        override suspend fun rewardBase(userId: Long, now: Instant): Instant = rewardBases[userId] ?: now
        override suspend fun hasUntrackedReferralMembership(userId: Long): Boolean = userId in untrackedMembershipUsers

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
                successfulReferralCount = referralRows.count { it.inviterUserId == userId },
                rewardMonthsEarned = userGrants.size,
                rewardStartsAt = userGrants.minOfOrNull { it.startsAt },
                rewardEndsAt = userGrants.maxOfOrNull { it.endsAt },
                hasRedeemedReferral = referralRows.any { it.referredUserId == userId } ||
                    pendingRows[userId]?.status == PendingReferralAttributionStatus.REWARDED ||
                    userId in untrackedMembershipUsers,
            )
        }
    }

    data class Grant(val referralId: Long, val userId: Long, val startsAt: Instant, val endsAt: Instant)

    private companion object {
        val GENERATED_CODES = listOf("BS-ABCDEFGH", "BS-JKLMNPQR", "BS-STUVWXYZ")
    }
}
