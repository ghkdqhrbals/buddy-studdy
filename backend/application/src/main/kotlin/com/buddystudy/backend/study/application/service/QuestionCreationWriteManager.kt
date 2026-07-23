package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.transaction.afterReactiveCommit
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class QuestionCreationWriteManager(
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
    suspend fun saveQuestionWithNotification(
        question: QuestionEntity,
        embedding: List<Float>,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        notification: (QuestionEntity) -> NotificationRequestCommand,
        now: Instant,
    ): QuestionEntity {
        val savedQuestion = questions.save(question)
        questionStats.save(QuestionStatsEntity(questionId = savedQuestion.id, updatedAt = now))
        coverage?.let { questionCoverage.markAsked(it, now) }
        questionEmbeddings.save(
            questionId = savedQuestion.id,
            userId = checkNotNull(savedQuestion.userId) { "Created question must have a user." },
            studyId = checkNotNull(savedQuestion.studyId) { "Created question must have a study." },
            topic = savedQuestion.topic,
            question = savedQuestion.question,
            embedding = embedding,
        )
        questionKeys.markQuestionCreated(questionKey, now)
        afterReactiveCommit {
            runCatching { questionCreatedPublisher.publishQuestionCreated(savedQuestion.id, savedQuestion.language, now) }
                .onFailure { error ->
                    log.warn(
                        "question_creation_after_commit_event_failed questionId={} error={}",
                        savedQuestion.id,
                        error.message,
                    )
                }
            runCatching { notifications.publish(notification(savedQuestion)) }
                .onFailure { error ->
                    log.warn(
                        "question_creation_after_commit_notification_failed questionId={} error={}",
                        savedQuestion.id,
                        error.message,
                    )
                }
        }
        return savedQuestion
    }
}
