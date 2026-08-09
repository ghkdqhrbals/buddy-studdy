package com.buddystudy.backend.study.application.port.inbound

import java.time.Instant

data class QuestionQuotaRolloverResult(
    val rolledOver: Int,
)

interface QuestionQuotaRolloverUseCase {
    suspend fun rolloverDue(at: Instant = Instant.now()): QuestionQuotaRolloverResult

    /** Fallback used by quota reads and reservations when the scheduled job has not run yet. */
    suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean
}
