package com.buddystudy.backend.study.application.model

import java.time.Instant

data class QuestionQuotaResponse(
    val usedCount: Int,
    val monthlyLimit: Int,
    val remainingCount: Int,
    val resetAt: Instant,
    val tierCode: String = "TIER1",
    val periodStartedAt: Instant? = null,
    val reservedCount: Int = 0,
    val baseLimit: Int = monthlyLimit,
    val bonusLimit: Int = 0,
    val anchorType: String = "ACCOUNT_CREATED",
    val policyVersion: Int = 2,
)
