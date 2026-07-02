package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionPushOutboxJpaRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxDispatchPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class QuestionPushOutboxDispatcher(
    private val properties: BuddyStudyProperties,
    private val outbox: QuestionPushOutboxJpaRepository,
    private val streams: QuestionPushPublishPort,
) : QuestionPushOutboxDispatchPort {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatchPendingPushes(): Int {
        if (!properties.scheduler.enabled || !properties.streams.enabled) return 0

        val now = Instant.now()
        var processed = 0
        outbox.findPending(now, PageRequest.of(0, 50)).forEach { item ->
            dispatchItem(item, now)
            processed += 1
        }
        return processed
    }

    fun dispatchItem(item: QuestionPushOutboxEntity, now: Instant = Instant.now()) {
        if (item.status != "PENDING") return
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

    override fun dispatchOutbox(outboxId: Long) {
        if (!properties.scheduler.enabled || !properties.streams.enabled) return
        val item = outbox.findById(outboxId).orElse(null) ?: run {
            log.warn("question_push_outbox_missing outboxId={}", outboxId)
            return
        }
        dispatchItem(item, Instant.now())
    }

    private fun markPublished(item: QuestionPushOutboxEntity, now: Instant) {
        item.status = "PUBLISHED"
        item.publishedAt = now
        item.lastError = null
        item.updatedAt = now
        val outboxAgeMs = Duration.between(item.createdAt, now).toMillis()
        log.info(
            "question_push_outbox_published outboxId={} recordId={} deviceId={} userId={} pushCreatedAt={} outboxPublishedAt={} outboxAgeMs={}",
            item.id,
            item.recordId,
            item.deviceId,
            item.userId,
            item.createdAt,
            now,
            outboxAgeMs,
        )
        outbox.save(item)
    }

    private fun markRetry(item: QuestionPushOutboxEntity, now: Instant, error: String) {
        item.attempts += 1
        item.lastError = error
        item.nextAttemptAt = now.plusSeconds(retryDelaySeconds(item.attempts))
        item.updatedAt = now
        val outboxAgeMs = Duration.between(item.createdAt, now).toMillis()
        log.warn(
            "question_push_outbox_retry_scheduled outboxId={} recordId={} attempts={} pushCreatedAt={} retryScheduledAt={} nextAttemptAt={} outboxAgeMs={}",
            item.id,
            item.recordId,
            item.attempts,
            item.createdAt,
            now,
            item.nextAttemptAt,
            outboxAgeMs,
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

@Component
@ConditionalOnProperty(prefix = "buddystudy.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionPushOutboxScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val questionPushOutboxDispatchJob: QuestionPushOutboxDispatchJob,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.scheduler.poll-ms:30000}")
    fun dispatchPendingPushes() {
        jobs.execute(questionPushOutboxDispatchJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class QuestionPushOutboxDispatchJob(
    private val dispatcher: QuestionPushOutboxDispatcher,
) : ManagedJob {
    override val name: String = "question-push-outbox-dispatch"

    override fun run(): String =
        "processed=${dispatcher.dispatchPendingPushes()}"
}
