package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PublicQuestionReactionOutboxPublisherTest {
    @Test
    fun `every community action appends and immediately publishes a dedicated outbox event`() = runBlocking<Unit> {
        val outbox = RecordingOutbox()
        val publisher = RecordingPublisher()
        val reactions = PublicQuestionReactionOutboxPublisher(outbox, ImmediateAfterCommit, publisher)

        reactions.publishViewed(
            questionId = 10,
            userId = 7,
            localization = PublicQuestionViewLocalization(
                translationState = "TRANSLATED",
                translationLanguage = "ko",
                translationReason = "EXPLICIT_TL",
                requestId = "request-1",
                questionSourceLanguage = "en",
                questionDisplayLanguage = "ko",
            ),
        )
        reactions.publishLiked(10, 7)
        reactions.publishUnliked(10, 7)
        reactions.publishCommented(10, 22, 7)
        reactions.publishCommentDeleted(10, 22, 7)

        assertThat(outbox.events.map { it.first }).containsExactly(
            RedisOutboxEventType.CONTENT_VIEWED,
            RedisOutboxEventType.QUESTION_LIKED,
            RedisOutboxEventType.QUESTION_UNLIKED,
            RedisOutboxEventType.QUESTION_COMMENTED,
            RedisOutboxEventType.QUESTION_COMMENT_DELETED,
        )
        val viewed = outbox.events.first().second
        assertThat(viewed.questionId).isEqualTo(10)
        assertThat(viewed.userId).isEqualTo(7)
        assertThat(viewed.translationState).isEqualTo("TRANSLATED")
        assertThat(viewed.questionSourceLanguage).isEqualTo("en")
        assertThat(viewed.questionDisplayLanguage).isEqualTo("ko")
        assertThat(outbox.events.last().second.commentId).isEqualTo(22)
        assertThat(publisher.references).containsExactly(
            OutboxReference(OutboxType.DOMAIN_EVENT, 1),
            OutboxReference(OutboxType.DOMAIN_EVENT, 2),
            OutboxReference(OutboxType.DOMAIN_EVENT, 3),
            OutboxReference(OutboxType.DOMAIN_EVENT, 4),
            OutboxReference(OutboxType.DOMAIN_EVENT, 5),
        )
    }

    private class RecordingOutbox : RedisEventOutboxAppendPort {
        val events = mutableListOf<Pair<RedisOutboxEventType, CommunityQuestionEvent>>()

        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long =
            error("Notification event is not expected.")

        override suspend fun appendCommunityQuestionEvent(
            eventType: RedisOutboxEventType,
            event: CommunityQuestionEvent,
            createdAt: Instant,
        ): Long {
            events += eventType to event
            return events.size.toLong()
        }
    }

    private object ImmediateAfterCommit : AfterCommitPort {
        override suspend fun execute(action: suspend () -> Unit) = action()
    }

    private class RecordingPublisher : PublishOutboxUseCase {
        val references = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            this.references += references
            return OutboxPublishSummary(references.size, references.size, 0)
        }
    }
}
