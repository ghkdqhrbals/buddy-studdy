package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.inbound.ScheduledQuestionWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.AiGradingRubric
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ScheduledQuestionWriteService(
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val notificationOutbox: RedisEventOutboxAppendPort,
) : ScheduledQuestionWriteUseCase {
    @Transactional
    override suspend fun complete(
        scheduleStudy: StudyEntity,
        topicStudy: StudyEntity,
        generated: GeneratedQuestionWithEmbedding,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult {
        val saved = questions.save(
            scheduleStudy.toScheduledQuestion(
                topicStudy = topicStudy,
                question = generated.generated.question,
                hint = generated.generated.hint,
                rubric = generated.generated.rubric,
                appLanguage = appLanguage,
                now = now,
            )
                .applyCoverage(coverage),
        )
        questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
        coverage?.let { questionCoverage.markAsked(it, now) }
        questionEmbeddings.save(
            questionId = saved.id,
            userId = scheduleStudy.userId,
            studyId = topicStudy.id,
            topic = topicStudy.topic,
            question = saved.question,
            embedding = generated.embedding,
        )
        questionKeys.markQuestionCreated(questionKey, now)
        scheduleStudy.markScheduleCompleted(now)
        if (scheduleStudy.id == topicStudy.id) {
            studies.save(scheduleStudy)
        } else {
            topicStudy.markTopicSelected(now)
            studies.save(topicStudy)
            studies.save(scheduleStudy)
        }
        val generatedOutboxId = notificationOutbox.appendQuestionGenerated(
            QuestionGeneratedEvent(
                eventId = "question-generated-${saved.id}",
                questionId = saved.id,
                userId = scheduleStudy.userId,
                sourceLanguage = saved.language,
                generatedAt = now,
            ),
            now,
        )
        return QuestionWriteResult(
            question = saved,
            outboxes = listOf(OutboxReference(OutboxType.DOMAIN_EVENT, generatedOutboxId)),
        )
    }

    @Transactional
    override suspend fun deferUntilNextInterval(study: StudyEntity, now: Instant) {
        study.markScheduleCompleted(now)
        studies.save(study)
    }

    @Transactional
    override suspend fun fail(
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
    topicStudy: StudyEntity,
    question: String,
    hint: String?,
    rubric: AiGradingRubric?,
    appLanguage: String,
    now: Instant,
): QuestionEntity =
    QuestionEntity(
        deviceId = deviceId,
        userId = userId,
        studyId = topicStudy.id,
        question = question,
        hint = hint,
        topic = topicStudy.topic,
        language = appLanguage,
        difficultyLevel = topicStudy.difficultyLevel,
        scheduledFor = nextDueAt ?: now,
        sentAt = now,
        status = "ungraded",
        source = "scheduled",
        publicQuestion = true,
        createdAt = now,
        updatedAt = now,
    ).applyRubric(rubric)

internal fun StudyEntity.markScheduleFailed(error: String, retryAt: Instant, now: Instant) {
    nextDueAt = retryAt
    scheduleClaimedUntil = null
    lastError = error
    updatedAt = now
}

private fun StudyEntity.markTopicSelected(now: Instant) {
    lastSentAt = now
    lastError = null
    updatedAt = now
}

internal fun StudyEntity.markScheduleCompleted(now: Instant) {
    lastSentAt = now
    nextDueAt = now.plusSeconds(intervalMinutes.toLong() * 60)
    scheduleClaimedUntil = null
    lastError = null
    updatedAt = now
}
