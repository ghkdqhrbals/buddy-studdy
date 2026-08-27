package com.buddystudy.backend.profile.application.port.outbound

import com.buddystudy.backend.profile.application.model.ReferralAccountSummary
import java.time.Instant

interface ReferralPort {
    suspend fun codeForUser(userId: Long): String?
    suspend fun createCode(userId: Long, code: String, now: Instant): Boolean
    suspend fun inviterUserId(code: String): Long?
    suspend fun lockUsers(userIds: List<Long>): Int
    suspend fun hasRedeemed(userId: Long): Boolean
    suspend fun createReferral(inviterUserId: Long, referredUserId: Long, code: String, now: Instant): Long?
    suspend fun rewardBase(userId: Long, now: Instant): Instant
    suspend fun grantTier2Month(
        referralId: Long,
        beneficiaryUserId: Long,
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): Boolean
    suspend fun accountSummary(userId: Long): ReferralAccountSummary
}
