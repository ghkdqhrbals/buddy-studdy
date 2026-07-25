package com.buddystudy.backend.study.application.model

import java.time.Instant

data class QuestionQuotaResponse(
    val usedCount: Int,
    val monthlyLimit: Int,
    val remainingCount: Int,
    val resetAt: Instant,
)
