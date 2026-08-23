package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ContentLocalizationService(
    private val requests: ContentTranslationRequestAppendPort,
    private val afterCommit: AfterCommitPort,
    private val publisher: PublishOutboxUseCase,
) : RequestContentLocalizationUseCase {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requestRecord(question: QuestionEntity, targetLanguage: String) {
        val requestedAt = Instant.now()
        val outboxes = requests.appendRecord(question, targetLanguage, requestedAt)
        if (outboxes.isNotEmpty()) {
            afterCommit.execute {
                publisher.publishNow(outboxes)
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requestComment(comment: QuestionCommentEntity, targetLanguage: String) {
        val requestedAt = Instant.now()
        val outbox = requests.appendComment(comment, targetLanguage, requestedAt) ?: return
        afterCommit.execute {
            publisher.publishNow(listOf(outbox))
        }
    }

}
