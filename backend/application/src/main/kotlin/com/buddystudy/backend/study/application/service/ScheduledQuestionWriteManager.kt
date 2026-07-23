package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.transaction.afterReactiveCommit
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ScheduledQuestionWriteManager(
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun complete(
        study: StudyEntity,
        generated: GeneratedQuestionWithEmbedding,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        appLanguage: String,
        now: Instant,
    ): QuestionEntity {
        val saved = questions.save(
            study.toScheduledQuestion(generated.generated.question, generated.generated.hint, appLanguage, now)
                .applyCoverage(coverage),
        )
        questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
        coverage?.let { questionCoverage.markAsked(it, now) }
        questionEmbeddings.save(
            questionId = saved.id,
            userId = study.userId,
            studyId = study.id,
            topic = study.topic,
            question = saved.question,
            embedding = generated.embedding,
        )
        questionKeys.markQuestionCreated(questionKey, now)
        study.markScheduleCompleted(now)
        studies.save(study)
        afterReactiveCommit {
            runCatching { questionCreatedPublisher.publishQuestionCreated(saved.id, appLanguage, now) }
                .onFailure { error ->
                    log.warn(
                        "scheduled_question_after_commit_event_failed studyId={} questionId={} error={}",
                        study.id,
                        saved.id,
                        error.message,
                    )
                }
            runCatching { notifications.publish(saved.toQuestionNotification(study, appLanguage)) }
                .onFailure { error ->
                    log.warn(
                        "scheduled_question_after_commit_notification_failed studyId={} questionId={} error={}",
                        study.id,
                        saved.id,
                        error.message,
                    )
                }
        }
        return saved
    }

    @Transactional
    suspend fun fail(
        study: StudyEntity,
        questionKey: OpenAIQuestionKey?,
        error: String,
        retryAt: Instant,
        now: Instant,
    ) {
        questionKey?.let { questionKeys.releaseQuestionReservation(it, now) }
        study.markScheduleFailed(error, retryAt, now)
        studies.save(study)
    }
}

private fun StudyEntity.toScheduledQuestion(
    question: String,
    hint: String?,
    appLanguage: String,
    now: Instant,
): QuestionEntity =
    QuestionEntity(
        deviceId = deviceId,
        userId = userId,
        studyId = id,
        question = question,
        hint = hint,
        topic = topic,
        language = appLanguage,
        difficultyLevel = difficultyLevel,
        scheduledFor = nextDueAt ?: now,
        sentAt = now,
        status = "ungraded",
        source = "scheduled",
        publicQuestion = true,
        createdAt = now,
        updatedAt = now,
    )

internal fun StudyEntity.markScheduleFailed(error: String, retryAt: Instant, now: Instant) {
    nextDueAt = retryAt
    scheduleClaimedUntil = null
    lastError = error
    updatedAt = now
}

internal fun StudyEntity.markScheduleCompleted(now: Instant) {
    lastSentAt = now
    nextDueAt = now.plusSeconds(intervalMinutes.toLong() * 60)
    scheduleClaimedUntil = null
    lastError = null
    updatedAt = now
}
