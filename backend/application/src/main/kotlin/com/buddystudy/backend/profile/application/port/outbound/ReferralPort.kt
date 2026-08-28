package com.buddystudy.backend.profile.application.port.outbound

import com.buddystudy.backend.profile.application.model.ReferralAccountSummary
import com.buddystudy.backend.profile.application.model.PendingReferralAttribution
import com.buddystudy.backend.profile.application.model.PendingReferralAttributionStatus
import com.buddystudy.backend.profile.application.model.ReferralRecord
import java.time.Instant

interface ReferralPort {
    suspend fun codeForUser(userId: Long): String?
    suspend fun lockCodeForUser(userId: Long): String?
    suspend fun createCode(userId: Long, code: String, now: Instant): Boolean
    suspend fun inviterUserId(code: String): Long?
    suspend fun lockUsers(userIds: List<Long>): Int
    suspend fun referralForReferredUser(userId: Long): ReferralRecord?
    suspend fun lockReferralForReferredUser(userId: Long): ReferralRecord?
    suspend fun createReferral(inviterUserId: Long, referredUserId: Long, code: String, now: Instant): Long?
    suspend fun pendingAttribution(userId: Long): PendingReferralAttribution?
    suspend fun lockPendingAttribution(userId: Long): PendingReferralAttribution?
    suspend fun createPendingAttribution(
        inviterUserId: Long,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): Boolean
    suspend fun createRewardedAttribution(
        inviterUserId: Long?,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): Boolean
    suspend fun completePendingAttribution(
        attributionId: Long,
        expectedStatus: PendingReferralAttributionStatus,
        status: PendingReferralAttributionStatus,
        now: Instant,
    ): Boolean
    suspend fun rewardBase(userId: Long, now: Instant): Instant
    suspend fun hasUntrackedReferralMembership(userId: Long): Boolean
    suspend fun grantTier2Month(
        referralId: Long,
        beneficiaryUserId: Long,
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): Boolean
    suspend fun accountSummary(userId: Long): ReferralAccountSummary
}
