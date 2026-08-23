package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class PublicQuestionReactionOutboxPublisher(
    private val outbox: RedisEventOutboxAppendPort,
    private val afterCommit: AfterCommitPort,
    private val publisher: PublishOutboxUseCase,
) : PublicQuestionReactionPublishPort {
    override suspend fun publishViewed(
        questionId: Long,
        userId: Long?,
        localization: PublicQuestionViewLocalization?,
    ): Boolean = publish(
        RedisOutboxEventType.CONTENT_VIEWED,
        event(
            eventId = "question-viewed-${UUID.randomUUID()}",
            questionId = questionId,
            userId = userId,
            localization = localization,
        ),
    )

    override suspend fun publishLiked(questionId: Long, userId: Long): Boolean =
        publish(
            RedisOutboxEventType.QUESTION_LIKED,
            event("question-liked-${UUID.randomUUID()}", questionId, userId),
        )

    override suspend fun publishUnliked(questionId: Long, userId: Long): Boolean =
        publish(
            RedisOutboxEventType.QUESTION_UNLIKED,
            event("question-unliked-${UUID.randomUUID()}", questionId, userId),
        )

    override suspend fun publishCommented(questionId: Long, commentId: Long, userId: Long): Boolean =
        publish(
            RedisOutboxEventType.QUESTION_COMMENTED,
            event("question-commented-$commentId", questionId, userId, commentId),
        )

    override suspend fun publishCommentDeleted(questionId: Long, commentId: Long, userId: Long): Boolean =
        publish(
            RedisOutboxEventType.QUESTION_COMMENT_DELETED,
            event("question-comment-deleted-$commentId", questionId, userId, commentId),
        )

    private suspend fun publish(
        eventType: RedisOutboxEventType,
        event: CommunityQuestionEvent,
    ): Boolean {
        val outboxId = outbox.appendCommunityQuestionEvent(eventType, event, event.occurredAt)
        afterCommit.execute {
            publisher.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)))
        }
        return true
    }

    private fun event(
        eventId: String,
        questionId: Long,
        userId: Long?,
        commentId: Long? = null,
        localization: PublicQuestionViewLocalization? = null,
    ): CommunityQuestionEvent = CommunityQuestionEvent(
        eventId = eventId,
        questionId = questionId,
        userId = userId,
        commentId = commentId,
        translationState = localization?.translationState,
        translationLanguage = localization?.translationLanguage,
        translationReason = localization?.translationReason,
        requestId = localization?.requestId,
        questionSourceLanguage = localization?.questionSourceLanguage,
        questionDisplayLanguage = localization?.questionDisplayLanguage,
        answerSourceLanguage = localization?.answerSourceLanguage,
        answerDisplayLanguage = localization?.answerDisplayLanguage,
        aiResponseSourceLanguage = localization?.aiResponseSourceLanguage,
        aiResponseDisplayLanguage = localization?.aiResponseDisplayLanguage,
        occurredAt = Instant.now(),
    )
}
