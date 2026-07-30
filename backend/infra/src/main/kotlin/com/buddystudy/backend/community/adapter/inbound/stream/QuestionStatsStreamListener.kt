package com.buddystudy.backend.community.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionStatsStreamListener(
    private val handler: QuestionStatsStreamEventHandler,
) {
    @StreamListener(
        topic = RedisStreamTopic.COMMUNITY_QUESTION_VIEWED,
        group = VIEW_GROUP,
        consumer = "buddystudy-question-view",
        eventType = VIEW_EVENT_TYPE,
        payloadType = CommunityQuestionEvent::class,
        batchSize = 100,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(
        payload: CommunityQuestionEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.COMMUNITY_QUESTION_VIEWED,
        group = VIEW_GROUP,
        consumer = "buddystudy-question-view-recovery",
        eventType = VIEW_EVENT_TYPE,
        payloadType = CommunityQuestionEvent::class,
        batchSize = 100,
        minIdleTimeMs = 60_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(
        payload: CommunityQuestionEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    internal suspend fun deliver(
        payload: CommunityQuestionEvent,
        context: StreamMessageContext,
    ) {
        handler.processViewEvent(payload, context.streamKey)
    }
}

@Component
class QuestionStatsStreamEventHandler(
    private val stats: QuestionStatsPort,
    private val inbox: StreamInboxPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun processViewEvent(
        event: CommunityQuestionEvent,
        streamKey: String,
    ) {
        val now = Instant.now()
        val claim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = VIEW_GROUP,
            correlationId = event.questionId.toString(),
            leaseDuration = VIEW_INBOX_LEASE,
            now = now,
            streamKey = streamKey,
        ) ?: return
        increment(event.questionId) { stats.incrementView(event.questionId, 1, now) }
        check(inbox.markSucceeded(claim, now)) {
            "Question view Inbox claim was lost before completion."
        }
        logger.debug(
            "question_stats_event_applied eventId={} eventType={} questionId={} deltaField={}",
            event.eventId,
            VIEW_EVENT_TYPE,
            event.questionId,
            "viewCount",
        )
    }

    private suspend fun increment(questionId: Long, update: suspend () -> Int) {
        if (update() == 0) {
            stats.save(QuestionStatsEntity(questionId = questionId, updatedAt = Instant.now()))
            update()
        }
    }
}

private const val VIEW_GROUP = "bs-backend-view"
private const val VIEW_EVENT_TYPE = "CONTENT_VIEWED"
private val VIEW_INBOX_LEASE: Duration = Duration.ofMinutes(1)
