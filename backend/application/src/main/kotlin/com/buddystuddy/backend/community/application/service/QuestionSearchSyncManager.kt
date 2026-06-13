package com.buddystuddy.backend.community.application.service

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Component
class QuestionSearchSyncManager(
    private val questions: QuestionPort,
    private val users: UserPort,
    private val search: QuestionSearchPort,
    private val publisher: QuestionSearchPublishPort,
) {
    fun publishCreated(questionId: Long) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publisher.publishCreated(questionId)
                    }
                }
            )
            return
        }
        publisher.publishCreated(questionId)
    }

    fun syncQuestion(question: QuestionEntity) {
        val user = question.userId?.let { users.findById(it).orElse(null) }
        if (user == null) {
            search.deleteByQuestionId(question.id)
            return
        }
        search.save(question.toSearchEntity(user))
    }

    fun syncQuestion(questionId: Long) {
        val question = questions.findQuestionById(questionId).orElse(null)
        if (question == null) {
            search.deleteByQuestionId(questionId)
            return
        }
        syncQuestion(question)
    }

    fun deleteQuestion(questionId: Long) {
        search.deleteByQuestionId(questionId)
    }

    private fun QuestionEntity.toSearchEntity(user: UserEntity): QuestionSearchEntity =
        QuestionSearchEntity(
            questionId = id,
            userId = user.id,
            topic = topic,
            question = question,
            answer = answer,
            feedback = feedback,
            explanation = explanation,
            authorDisplayName = user.displayName,
            publicQuestion = publicQuestion,
            score = score,
            answeredAt = answeredAt,
            deletedAt = deletedAt,
            createdAt = createdAt,
            updatedAt = Instant.now(),
        )
}
