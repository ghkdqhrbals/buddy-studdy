package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import java.time.Duration
import java.time.Instant

interface QuestionGenerationSagaPort {
    suspend fun insert(saga: QuestionGenerationSaga): Boolean
    suspend fun findByCorrelationId(correlationId: String): QuestionGenerationSaga?
    suspend fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): QuestionGenerationSaga?
    suspend fun findActiveByUserIdAndTopicId(userId: Long, topicId: Long): QuestionGenerationSaga?
    suspend fun findActiveTopicIdsByUserId(userId: Long, topicIds: Collection<Long>): Set<Long> =
        topicIds.filterTo(mutableSetOf()) { topicId ->
            findActiveByUserIdAndTopicId(userId, topicId) != null
        }
    suspend fun markGenerating(correlationId: String, now: Instant): Boolean
    suspend fun markTranslating(correlationId: String, questionId: Long, now: Instant): Boolean
    suspend fun markCompleted(correlationId: String, now: Instant): Boolean
    suspend fun markFailed(
        correlationId: String,
        failedStep: QuestionGenerationStep,
        errorCode: String,
        errorMessage: String,
        refundedAt: Instant?,
        now: Instant,
    ): Boolean
    suspend fun markRollbackCompleted(correlationId: String, now: Instant): Boolean
}

interface StreamInboxPort {
    suspend fun claim(
        eventId: String,
        consumerGroup: String,
        correlationId: String,
        leaseDuration: Duration,
        now: Instant,
        streamKey: String = "test",
    ): StreamInboxClaim?

    suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean
    suspend fun releaseForRetry(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean

    suspend fun markFailed(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean
}
