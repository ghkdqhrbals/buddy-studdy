package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystudy.backend.study.application.port.inbound.ScheduledQuestionWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ScheduledQuestionService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val requestWriter: QuestionGenerationRequestWriteUseCase,
    private val scheduleWriter: ScheduledQuestionWriteUseCase,
    private val publisher: PublishOutboxUseCase,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) : RunQuestionScheduleUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun runDueQuestions() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val batchSize = properties.scheduler.batchSize.coerceAtLeast(1)
        var processed = 0
        while (true) {
            val dueStudies = studies.claimDue(now, batchSize)
            if (dueStudies.isEmpty()) break
            val contexts = dueStudies.map { root ->
                val allStudies = studies.findAllByUserId(root.userId)
                ScheduledStudyContext(root, allStudies, StudyTreeSelector.activeTopics(root, allStudies))
            }
            val latestStatuses = latestStatuses(contexts)
            contexts.forEach { context ->
                enqueueOne(context, latestStatuses, now)
            }
            processed += dueStudies.size
        }
        if (processed > 0) {
            log.info("scheduled_question_saga_drain_completed processed={} batchSize={}", processed, batchSize)
        }
    }

    private suspend fun enqueueOne(
        context: ScheduledStudyContext,
        latestStatuses: Map<Long, QuestionStatus>,
        now: Instant,
    ) {
        val root = context.root
        if (context.activeTopics.isEmpty()) {
            scheduleWriter.deferUntilNextInterval(root, now)
            log.info("scheduled_question_skipped_no_active_topic userId={} rootStudyId={}", root.userId, root.id)
            return
        }
        val blockedTopicIds = context.activeTopics
            .filter { latestStatuses[it.id]?.allowsNextQuestion == false }
            .mapTo(mutableSetOf(), StudyEntity::id)
        val topic = StudyTreeSelector.nextActiveTopic(root, context.allStudies, blockedTopicIds)
        if (topic == null) {
            scheduleWriter.fail(
                study = root,
                questionKey = null,
                error = "Pending question limit reached for all active topics.",
                retryAt = backoffPolicy.pendingLimitNextDueAt(now),
                now = now,
            )
            log.info(
                "scheduled_question_skipped_all_topics_pending userId={} rootStudyId={} activeTopics={} blockedTopics={}",
                root.userId,
                root.id,
                context.activeTopics.size,
                blockedTopicIds.size,
            )
            return
        }
        try {
            val occurrence = root.nextDueAt ?: now
            val queued = requestWriter.enqueueScheduled(
                scheduleStudy = root,
                topicStudy = topic,
                idempotencyKey = "scheduled:${root.id}:${topic.id}:${occurrence.toEpochMilli()}",
                now = now,
            )
            runCatching { publisher.publishNow(queued.outboxes) }
                .onFailure {
                    log.warn(
                        "scheduled_question_immediate_publish_failed correlationId={} userId={} rootStudyId={} topicStudyId={} error={}",
                        queued.accepted.correlationId,
                        root.userId,
                        root.id,
                        topic.id,
                        it.message,
                    )
                }
            log.info(
                "scheduled_question_saga_queued correlationId={} userId={} rootStudyId={} topicStudyId={}",
                queued.accepted.correlationId,
                root.userId,
                root.id,
                topic.id,
            )
        } catch (error: Exception) {
            scheduleWriter.fail(
                study = root,
                questionKey = null,
                error = error.message ?: error.javaClass.simpleName,
                retryAt = backoffPolicy.failureNextDueAt(now),
                now = now,
            )
            log.warn(
                "scheduled_question_saga_queue_failed userId={} rootStudyId={} topicStudyId={} error={}",
                root.userId,
                root.id,
                topic.id,
                error.message,
            )
        }
    }

    private suspend fun latestStatuses(
        contexts: List<ScheduledStudyContext>,
    ): Map<Long, QuestionStatus> {
        val studyIds = contexts.flatMap { it.activeTopics.map(StudyEntity::id) }.distinct()
        return buildMap {
            studyIds.chunked(PENDING_COUNT_BATCH_SIZE).forEach { chunk ->
                putAll(questions.findLatestStatusesByStudyIds(chunk))
            }
        }
    }
}

private data class ScheduledStudyContext(
    val root: StudyEntity,
    val allStudies: List<StudyEntity>,
    val activeTopics: List<StudyEntity>,
)

class ScheduleBackoffPolicy(
    private val pendingLimitRetrySeconds: Long = 5 * 60,
    private val failureRetrySeconds: Long = 10 * 60,
) {
    fun pendingLimitNextDueAt(now: Instant): Instant = now.plusSeconds(pendingLimitRetrySeconds)
    fun failureNextDueAt(now: Instant): Instant = now.plusSeconds(failureRetrySeconds)
}

private const val PENDING_COUNT_BATCH_SIZE = 500
