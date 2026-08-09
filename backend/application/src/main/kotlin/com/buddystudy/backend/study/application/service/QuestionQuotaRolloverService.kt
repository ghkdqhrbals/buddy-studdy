package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverResult
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaRolloverPort
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QuestionQuotaRolloverService(
    private val properties: BuddyStudyProperties,
    private val quotas: QuestionQuotaRolloverPort,
) : QuestionQuotaRolloverUseCase {
    override suspend fun rolloverDue(at: Instant): QuestionQuotaRolloverResult {
        val batchSize = properties.quota.rollover.batchSize.coerceIn(1, MAX_BATCH_SIZE)
        val maxBatches = properties.quota.rollover.maxBatchesPerRun.coerceIn(1, MAX_BATCHES_PER_RUN)
        var rolledOver = 0
        repeat(maxBatches) {
            val currentBatch = quotas.rolloverDue(at, batchSize)
            rolledOver += currentBatch
            if (currentBatch < batchSize) return QuestionQuotaRolloverResult(rolledOver)
        }
        return QuestionQuotaRolloverResult(rolledOver)
    }

    override suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean =
        quotas.rolloverUserIfDue(userId, at)

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
        const val MAX_BATCHES_PER_RUN = 100
    }
}
