package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.ClaimedQuestionPushOutbox
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class OutboxPublicationService(
    private val properties: BuddyStudyProperties,
    private val domainOutbox: RedisEventOutboxPort,
    private val pushOutbox: QuestionPushOutboxPort,
    private val domainPublisher: DomainEventPublishPort,
    private val pushPublisher: QuestionPushPublishPort,
) : PublishOutboxUseCase, RecoverOutboxUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
        if (!properties.streams.enabled || references.isEmpty()) return EMPTY_SUMMARY
        val now = Instant.now()
        val outcomes = references.distinct().map { reference ->
            runCatching {
                publishReference(reference, now)
            }.getOrElse { error ->
                log.warn(
                    "outbox_immediate_publish_failed type={} outboxId={} error={}",
                    reference.type,
                    reference.id,
                    error.message,
                )
                PublishOutcome.NOT_CLAIMED
            }
        }
        return outcomes.toSummary()
    }

    private suspend fun publishReference(reference: OutboxReference, now: Instant): PublishOutcome =
        when (reference.type) {
            OutboxType.DOMAIN_EVENT ->
                domainOutbox.claim(reference.id, now, now.minus(CLAIM_LEASE))
                    ?.let { publishDomain(it) }
                    ?: PublishOutcome.NOT_CLAIMED

            OutboxType.QUESTION_PUSH ->
                pushOutbox.claim(reference.id, now, now.minus(CLAIM_LEASE))
                    ?.let { publishPush(it) }
                    ?: PublishOutcome.NOT_CLAIMED
        }

    override suspend fun recoverPending(): OutboxPublishSummary {
        if (!properties.streams.enabled) return EMPTY_SUMMARY
        val now = Instant.now()
        val staleBefore = now.minus(CLAIM_LEASE)
        val domainEvents = runCatching { domainOutbox.claimBatch(now, staleBefore, BATCH_SIZE) }
            .onFailure { log.warn("redis_outbox_recovery_claim_failed error={}", it.message) }
            .getOrDefault(emptyList())
        val pushes = runCatching { pushOutbox.claimBatch(now, staleBefore, BATCH_SIZE) }
            .onFailure { log.warn("question_push_outbox_recovery_claim_failed error={}", it.message) }
            .getOrDefault(emptyList())
        val outcomes = domainEvents.map { event ->
            runCatching { publishDomain(event) }
                .onFailure { log.warn("redis_outbox_recovery_publish_failed outboxId={} error={}", event.id, it.message) }
                .getOrDefault(PublishOutcome.NOT_CLAIMED)
        } + pushes.map { item ->
            runCatching { publishPush(item) }
                .onFailure {
                    log.warn("question_push_outbox_recovery_publish_failed outboxId={} error={}", item.id, it.message)
                }
                .getOrDefault(PublishOutcome.NOT_CLAIMED)
        }
        return outcomes.toSummary()
    }

    private suspend fun publishDomain(event: ClaimedRedisOutboxEvent): PublishOutcome {
        val publishedAt = Instant.now()
        return runCatching {
            require(event.payloadVersion == SUPPORTED_PAYLOAD_VERSION) {
                "Unsupported outbox payload version: ${event.payloadVersion}"
            }
            domainPublisher.publish(event)
        }.fold(
            onSuccess = { recordId ->
                if (domainOutbox.markPublished(event.id, event.claimToken, publishedAt)) {
                    log.info(
                        "redis_outbox_published outboxId={} eventId={} eventType={} redisRecordId={} attempts={} ageMs={}",
                        event.id,
                        event.eventId,
                        event.eventType,
                        recordId,
                        event.attempts,
                        Duration.between(event.createdAt, publishedAt).toMillis(),
                    )
                    PublishOutcome.PUBLISHED
                } else {
                    log.warn("redis_outbox_publish_fence_lost outboxId={} eventId={}", event.id, event.eventId)
                    PublishOutcome.NOT_CLAIMED
                }
            },
            onFailure = { error -> retryDomain(event, error, publishedAt) },
        )
    }

    private suspend fun publishPush(item: ClaimedQuestionPushOutbox): PublishOutcome {
        val publishedAt = Instant.now()
        return runCatching { check(pushPublisher.publishPush(item.request)) { "Push stream publish failed." } }
            .fold(
                onSuccess = {
                    if (pushOutbox.markPublished(item.id, item.claimToken, publishedAt)) {
                        log.info(
                            "question_push_outbox_published outboxId={} eventId={} attempts={} ageMs={}",
                            item.id,
                            item.request.eventId,
                            item.attempts,
                            Duration.between(item.createdAt, publishedAt).toMillis(),
                        )
                        PublishOutcome.PUBLISHED
                    } else {
                        log.warn(
                            "question_push_outbox_publish_fence_lost outboxId={} eventId={}",
                            item.id,
                            item.request.eventId,
                        )
                        PublishOutcome.NOT_CLAIMED
                    }
                },
                onFailure = { error -> retryPush(item, error, publishedAt) },
            )
    }

    private suspend fun retryDomain(
        event: ClaimedRedisOutboxEvent,
        error: Throwable,
        failedAt: Instant,
    ): PublishOutcome {
        val attempts = event.attempts + 1
        val nextAttemptAt = failedAt.plusSeconds(retryDelaySeconds(attempts))
        val updated = domainOutbox.markRetry(
            id = event.id,
            claimToken = event.claimToken,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            error = error.message ?: error.javaClass.simpleName,
            updatedAt = failedAt,
        )
        log.warn(
            "redis_outbox_retry_scheduled outboxId={} eventId={} attempts={} nextAttemptAt={} fenced={} error={}",
            event.id,
            event.eventId,
            attempts,
            nextAttemptAt,
            updated,
            error.message,
        )
        return if (updated) PublishOutcome.RETRY_SCHEDULED else PublishOutcome.NOT_CLAIMED
    }

    private suspend fun retryPush(
        item: ClaimedQuestionPushOutbox,
        error: Throwable,
        failedAt: Instant,
    ): PublishOutcome {
        val attempts = item.attempts + 1
        val nextAttemptAt = failedAt.plusSeconds(retryDelaySeconds(attempts))
        val updated = pushOutbox.markRetry(
            id = item.id,
            claimToken = item.claimToken,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            error = error.message ?: error.javaClass.simpleName,
            updatedAt = failedAt,
        )
        log.warn(
            "question_push_outbox_retry_scheduled outboxId={} eventId={} attempts={} nextAttemptAt={} fenced={} error={}",
            item.id,
            item.request.eventId,
            attempts,
            nextAttemptAt,
            updated,
            error.message,
        )
        return if (updated) PublishOutcome.RETRY_SCHEDULED else PublishOutcome.NOT_CLAIMED
    }

    private fun retryDelaySeconds(attempts: Int): Long =
        (1L shl attempts.coerceIn(0, MAX_BACKOFF_EXPONENT)).coerceAtMost(MAX_RETRY_DELAY_SECONDS)

    private fun List<PublishOutcome>.toSummary(): OutboxPublishSummary =
        OutboxPublishSummary(
            attempted = count { it != PublishOutcome.NOT_CLAIMED },
            published = count { it == PublishOutcome.PUBLISHED },
            retryScheduled = count { it == PublishOutcome.RETRY_SCHEDULED },
        )

    private enum class PublishOutcome {
        PUBLISHED,
        RETRY_SCHEDULED,
        NOT_CLAIMED,
    }

    private companion object {
        val EMPTY_SUMMARY = OutboxPublishSummary(0, 0, 0)
        val CLAIM_LEASE: Duration = Duration.ofMinutes(2)
        const val BATCH_SIZE = 100
        const val SUPPORTED_PAYLOAD_VERSION = 1
        const val MAX_BACKOFF_EXPONENT = 8
        const val MAX_RETRY_DELAY_SECONDS = 300L
    }
}
