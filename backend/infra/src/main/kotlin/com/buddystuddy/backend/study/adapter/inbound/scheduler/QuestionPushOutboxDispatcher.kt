package com.buddystuddy.backend.study.adapter.inbound.scheduler

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionPushOutboxJpaRepository
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.study.domain.entity.QuestionPushOutboxEntity
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class QuestionPushOutboxDispatcher(
    private val properties: BuddyStuddyProperties,
    private val outbox: QuestionPushOutboxJpaRepository,
    private val streams: QuestionPushPublishPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${buddystuddy.scheduler.poll-ms:30000}")
    fun dispatchPendingPushes() {
        if (!properties.scheduler.enabled || !properties.streams.enabled) return

        val now = Instant.now()
        outbox.findPending(now, PageRequest.of(0, 50)).forEach { item ->
            dispatchItem(item, now)
        }
    }

    fun dispatchItem(item: QuestionPushOutboxEntity, now: Instant = Instant.now()) {
        val published = runCatching { streams.publishPush(item.toRequest()) }
            .onFailure { error -> log.warn("question_push_outbox_publish_failed outboxId={} recordId={} error={}", item.id, item.recordId, error.message) }
            .getOrElse { error ->
                markRetry(item, now, error.message ?: error.javaClass.simpleName)
                return
            }
        if (published) {
            markPublished(item, now)
        } else {
            markRetry(item, now, "Push stream publish failed.")
        }
    }

    private fun markPublished(item: QuestionPushOutboxEntity, now: Instant) {
        item.status = "PUBLISHED"
        item.publishedAt = now
        item.lastError = null
        item.updatedAt = now
        log.info(
            "question_push_outbox_published outboxId={} recordId={} deviceId={} userId={}",
            item.id,
            item.recordId,
            item.deviceId,
            item.userId,
        )
        outbox.save(item)
    }

    private fun markRetry(item: QuestionPushOutboxEntity, now: Instant, error: String) {
        item.attempts += 1
        item.lastError = error
        item.nextAttemptAt = now.plusSeconds(retryDelaySeconds(item.attempts))
        item.updatedAt = now
        log.warn(
            "question_push_outbox_retry_scheduled outboxId={} recordId={} attempts={} nextAttemptAt={}",
            item.id,
            item.recordId,
            item.attempts,
            item.nextAttemptAt,
        )
        outbox.save(item)
    }

    private fun retryDelaySeconds(attempts: Int): Long =
        (attempts.coerceAtLeast(1) * 30L).coerceAtMost(300L)

    private fun QuestionPushOutboxEntity.toRequest(): QuestionPushRequest =
        QuestionPushRequest(
            recordId = recordId,
            studyId = studyId,
            createdAt = createdAt,
            deviceId = deviceId,
            userId = userId,
            question = question,
            expectedAnswerHint = expectedAnswerHint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            language = language,
            sound = sound,
            intervalMinutes = intervalMinutes,
        )
}
