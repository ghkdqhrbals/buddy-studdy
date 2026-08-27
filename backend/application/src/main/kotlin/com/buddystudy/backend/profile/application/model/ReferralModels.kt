package com.buddystudy.backend.profile.application.model

import java.time.Instant

data class ReferralSummaryResponse(
    val code: String,
    val successfulReferralCount: Int,
    val rewardMonthsEarned: Int,
    val rewardStartsAt: Instant?,
    val rewardEndsAt: Instant?,
    val hasRedeemedReferral: Boolean,
)

data class ReferralAccountSummary(
    val successfulReferralCount: Int,
    val rewardMonthsEarned: Int,
    val rewardStartsAt: Instant?,
    val rewardEndsAt: Instant?,
    val hasRedeemedReferral: Boolean,
)
