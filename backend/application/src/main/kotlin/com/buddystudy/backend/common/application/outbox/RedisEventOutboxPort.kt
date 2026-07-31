package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import java.time.Instant

enum class OutboxType {
    DOMAIN_EVENT,
}

data class OutboxReference(
    val type: OutboxType,
    val id: Long,
)

data class OutboxPublishSummary(
    val attempted: Int,
    val published: Int,
    val retryScheduled: Int,
)

interface PublishOutboxUseCase {
    suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary
}

interface RecoverOutboxUseCase {
    suspend fun recoverPending(): OutboxPublishSummary
}

data class PublishedStreamRecord(
    val streamKey: String,
    val recordId: String,
)

interface DomainEventPublishPort {
    suspend fun publish(event: ClaimedRedisOutboxEvent): PublishedStreamRecord
}

interface AfterCommitPort {
    suspend fun execute(action: suspend () -> Unit)
}

enum class RedisOutboxEventType {
    NOTIFICATION_REQUESTED,
    ACCOUNT_WITHDRAWN,
    ANSWER_GRADING_REQUESTED,
    QUESTION_GENERATION_REQUESTED,
    QUESTION_GENERATED,
    CONTENT_TRANSLATION_REQUESTED,
    CONTENT_VIEWED,
    QUESTION_LIKED,
    QUESTION_UNLIKED,
    QUESTION_COMMENTED,
    QUESTION_COMMENT_DELETED,
}

data class ClaimedRedisOutboxEvent(
    val id: Long,
    val eventId: String,
    val eventType: RedisOutboxEventType,
    val payloadVersion: Int,
    val payloadJson: String,
    val attempts: Int,
    val createdAt: Instant,
    val claimToken: String,
)

interface RedisEventOutboxAppendPort {
    suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant = Instant.now()): Long
    suspend fun appendAnswerGrading(event: AnswerGradingRequestedEvent, createdAt: Instant = Instant.now()): Long =
        error("Answer grading outbox is not configured.")
    suspend fun appendQuestionGenerationRequested(
        event: QuestionGenerationRequestedEvent,
        createdAt: Instant = Instant.now(),
    ): Long = error("Question generation requested outbox is not configured.")
    suspend fun appendQuestionGenerated(event: QuestionGeneratedEvent, createdAt: Instant = Instant.now()): Long =
        error("Question generated outbox is not configured.")
    suspend fun appendContentTranslation(
        event: ContentTranslationRequestedEvent,
        createdAt: Instant = Instant.now(),
    ): Long = error("Content translation outbox is not configured.")
    suspend fun appendCommunityQuestionEvent(
        eventType: RedisOutboxEventType,
        event: CommunityQuestionEvent,
        createdAt: Instant = Instant.now(),
    ): Long = error("Community question event outbox is not configured.")
}

interface RedisEventOutboxPort : RedisEventOutboxAppendPort {
    suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedRedisOutboxEvent?
    suspend fun claimBatch(now: Instant, staleBefore: Instant, limit: Int): List<ClaimedRedisOutboxEvent>
    suspend fun markPublished(
        id: Long,
        claimToken: String,
        publication: PublishedStreamRecord,
        publishedAt: Instant,
    ): Boolean
    suspend fun markRetry(
        id: Long,
        claimToken: String,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean
}
