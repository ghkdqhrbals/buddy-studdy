package com.buddystudy.backend.study.application.port.outbound

import java.time.Instant

interface QuestionQuotaRolloverPort {
    /**
     * Locks and advances at most [batchSize] expired current-quota rows.
     * The current projection and its append-only history must commit atomically.
     */
    suspend fun rolloverDue(at: Instant, batchSize: Int): Int

    suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean
}
