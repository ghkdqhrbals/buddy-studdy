package com.buddystudy.backend.profile.application.model

import java.time.Instant

data class ReferralSummaryResponse(
    val code: String,
    val referralUrl: String,
    val successfulReferralCount: Int,
    val rewardMonthsEarned: Int,
    val rewardStartsAt: Instant?,
    val rewardEndsAt: Instant?,
    val hasRedeemedReferral: Boolean,
)

data class ReferralLandingResponse(
    val code: String,
    val referralUrl: String,
    val appDeepLink: String,
    val appStoreUrl: String,
    val appStoreAppId: Long,
)

data class ReferralRecord(
    val id: Long,
    val inviterUserId: Long?,
    val referredUserId: Long,
    val referralCode: String,
)

data class PendingReferralAttribution(
    val id: Long,
    val inviterUserId: Long?,
    val referredUserId: Long,
    val referralCode: String,
    val status: PendingReferralAttributionStatus,
)

enum class PendingReferralAttributionStatus {
    PENDING,
    REWARDED,
    REJECTED,
}

data class ReferralAccountSummary(
    val successfulReferralCount: Int,
    val rewardMonthsEarned: Int,
    val rewardStartsAt: Instant?,
    val rewardEndsAt: Instant?,
    val hasRedeemedReferral: Boolean,
)
