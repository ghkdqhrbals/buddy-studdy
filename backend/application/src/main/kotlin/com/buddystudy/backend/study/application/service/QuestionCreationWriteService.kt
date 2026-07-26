package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.inbound.QuestionCreationWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxAppendPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class QuestionCreationWriteService(
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val notificationOutbox: RedisEventOutboxAppendPort,
    private val pushOutbox: QuestionPushOutboxAppendPort,
) : QuestionCreationWriteUseCase {
    @Transactional
    override suspend fun saveQuestionWithOutboxes(
        question: QuestionEntity,
        embedding: List<Float>,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        notification: (QuestionEntity) -> NotificationRequestCommand,
        push: (QuestionEntity) -> QuestionPushRequest,
        now: Instant,
    ): QuestionWriteResult {
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
        val notificationOutboxId = notificationOutbox.appendNotification(notification(savedQuestion), now)
        val pushOutboxId = pushOutbox.enqueue(push(savedQuestion), now)
        return QuestionWriteResult(
            question = savedQuestion,
            outboxes = listOf(
                OutboxReference(OutboxType.DOMAIN_EVENT, notificationOutboxId),
                OutboxReference(OutboxType.QUESTION_PUSH, pushOutboxId),
            ),
        )
    }
}
