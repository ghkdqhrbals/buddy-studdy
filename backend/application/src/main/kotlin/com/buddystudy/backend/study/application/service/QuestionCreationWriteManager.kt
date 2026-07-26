package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
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
    private val notifications: PublishNotificationUseCase,
    private val pushOutbox: QuestionPushOutboxPort,
) {
    @Transactional
    suspend fun saveQuestionWithNotification(
        question: QuestionEntity,
        embedding: List<Float>,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        notification: (QuestionEntity) -> NotificationRequestCommand,
        push: (QuestionEntity) -> QuestionPushRequest,
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
        notifications.publish(notification(savedQuestion))
        pushOutbox.enqueue(push(savedQuestion), now)
        return savedQuestion
    }
}
