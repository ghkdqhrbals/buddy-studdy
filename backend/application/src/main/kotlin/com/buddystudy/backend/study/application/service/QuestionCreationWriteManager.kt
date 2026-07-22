package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.transaction.afterReactiveCommit
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
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
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
) {
    @Transactional
    suspend fun saveQuestionWithNotification(
        question: QuestionEntity,
        notification: (QuestionEntity) -> NotificationRequestCommand,
        now: Instant,
    ): QuestionEntity {
        val savedQuestion = questions.save(question)
        questionStats.save(QuestionStatsEntity(questionId = savedQuestion.id, updatedAt = now))
        afterReactiveCommit {
            questionCreatedPublisher.publishQuestionCreated(savedQuestion.id, savedQuestion.language, now)
            notifications.publish(notification(savedQuestion))
        }
        return savedQuestion
    }
}
