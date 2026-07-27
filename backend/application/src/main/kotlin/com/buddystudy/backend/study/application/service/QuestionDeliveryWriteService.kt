package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.study.application.port.inbound.QuestionDeliveryWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxAppendPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class QuestionDeliveryWriteService(
    private val notificationOutbox: RedisEventOutboxAppendPort,
    private val pushOutbox: QuestionPushOutboxAppendPort,
) : QuestionDeliveryWriteUseCase {
    @Transactional
    override suspend fun enqueue(
        question: QuestionEntity,
        rootStudy: StudyEntity,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult {
        val notificationOutboxId = notificationOutbox.appendNotification(
            question.toQuestionNotification(rootStudy, appLanguage),
            now,
        )
        val pushOutboxId = pushOutbox.enqueue(
            question.toQuestionPushRequest(rootStudy, appLanguage),
            now,
        )
        return QuestionWriteResult(
            question = question,
            outboxes = listOf(
                OutboxReference(OutboxType.DOMAIN_EVENT, notificationOutboxId),
                OutboxReference(OutboxType.QUESTION_PUSH, pushOutboxId),
            ),
        )
    }
}
