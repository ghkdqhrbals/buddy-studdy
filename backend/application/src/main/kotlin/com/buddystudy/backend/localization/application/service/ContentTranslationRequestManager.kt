package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Creates durable translation work inside the caller's transaction.
 * Publishing is deliberately owned by the outer use case after commit.
 */
@Component
class ContentTranslationRequestManager(
    private val localizations: ContentLocalizationPort,
    private val events: ContentTranslationEventPort,
) : ContentTranslationRequestAppendPort {
    override suspend fun appendRecordForSupportedLanguages(
        question: QuestionEntity,
        requestedAt: Instant,
    ): List<OutboxReference> = QuestionLanguage.supported
        .sorted()
        .flatMap { targetLanguage -> appendRecord(question, targetLanguage, requestedAt) }

    override suspend fun appendCommentForSupportedLanguages(
        comment: QuestionCommentEntity,
        requestedAt: Instant,
    ): List<OutboxReference> = QuestionLanguage.supported
        .sorted()
        .mapNotNull { targetLanguage -> appendComment(comment, targetLanguage, requestedAt) }

    override suspend fun appendRecord(
        question: QuestionEntity,
        targetLanguage: String,
        requestedAt: Instant,
    ): List<OutboxReference> {
        val target = QuestionLanguage.normalize(targetLanguage)
        val pendingRequests = localizations.ensureRecordPending(
            question = question,
            targetLanguage = target,
            sourceHashes = ContentSourceHashPolicy.recordHashes(question),
            now = requestedAt,
            retryPendingBefore = requestedAt.minus(REQUEST_RETRY_DELAY),
        )
        return pendingRequests.map { request ->
            append(
                ContentTranslationRequestedEvent(
                    eventId = "content-translation-${request.requestToken}",
                    contentType = request.contentType,
                    contentId = question.id,
                    targetLanguage = target,
                    sourceHash = request.sourceHash,
                    requestedAt = requestedAt,
                ),
                requestedAt,
            )
        }
    }

    override suspend fun appendComment(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        requestedAt: Instant,
    ): OutboxReference? {
        val target = QuestionLanguage.normalize(targetLanguage)
        val sourceHash = ContentSourceHashPolicy.sha256(comment.body)
        val request = localizations.ensureCommentPending(
            comment = comment,
            targetLanguage = target,
            sourceHash = sourceHash,
            now = requestedAt,
            retryPendingBefore = requestedAt.minus(REQUEST_RETRY_DELAY),
        ) ?: return null
        return append(
            ContentTranslationRequestedEvent(
                eventId = "content-translation-${request.requestToken}",
                contentType = LocalizableContentType.COMMENT,
                contentId = comment.id,
                targetLanguage = target,
                sourceHash = sourceHash,
                requestedAt = requestedAt,
            ),
            requestedAt,
        )
    }

    private suspend fun append(
        event: ContentTranslationRequestedEvent,
        requestedAt: Instant,
    ): OutboxReference = OutboxReference(
        OutboxType.DOMAIN_EVENT,
        events.append(event, requestedAt),
    )

    private companion object {
        val REQUEST_RETRY_DELAY: Duration = Duration.ofMinutes(5)
    }
}
