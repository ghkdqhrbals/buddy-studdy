package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Component
class QuestionCreationWriteManager(
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
) {
    @Transactional
    fun saveQuestionWithNotification(
        question: QuestionEntity,
        notification: (QuestionEntity) -> NotificationRequestCommand,
        now: Instant,
    ): QuestionEntity {
        val savedQuestion = questions.save(question)
        questionStats.save(QuestionStatsEntity(questionId = savedQuestion.id, updatedAt = now))
        afterCommit {
            questionCreatedPublisher.publishQuestionCreated(savedQuestion.id, savedQuestion.language, now)
            notifications.publish(notification(savedQuestion))
        }
        return savedQuestion
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            }
        )
    }
}
