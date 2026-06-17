package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxCommand
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxDispatchPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
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
    private val pushOutbox: QuestionPushOutboxPort,
    private val pushOutboxDispatch: QuestionPushOutboxDispatchPort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
) {
    @Transactional
    fun saveQuestionWithOutbox(
        question: QuestionEntity,
        push: QuestionPushOutboxCommand,
        now: Instant,
    ): QuestionEntity {
        val savedQuestion = questions.save(question)
        questionStats.save(QuestionStatsEntity(questionId = savedQuestion.id, updatedAt = now))
        val outboxId = pushOutbox.enqueue(push.toRequest(savedQuestion.id), now)
        afterCommit {
            questionCreatedPublisher.publishQuestionCreated(savedQuestion.id, savedQuestion.language, now)
            pushOutboxDispatch.dispatchOutbox(outboxId)
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
