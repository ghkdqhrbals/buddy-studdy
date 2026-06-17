package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxCommand
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class QuestionCreationWriteManager(
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val pushOutbox: QuestionPushOutboxPort,
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
        pushOutbox.enqueue(push.toRequest(savedQuestion.id), now)
        questionCreatedPublisher.publishQuestionCreated(savedQuestion.id, savedQuestion.language, now)
        return savedQuestion
    }
}
