package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Service
class ContentLocalizationService(
    private val localizations: ContentLocalizationPort,
    private val events: ContentTranslationEventPort,
    private val afterCommit: AfterCommitPort,
    private val publisher: PublishOutboxUseCase,
) : RequestContentLocalizationUseCase {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requestRecord(question: QuestionEntity, targetLanguage: String) {
        val target = QuestionLanguage.normalize(targetLanguage)
        val hashes = recordHashes(question)
        val requestedAt = Instant.now()
        val pendingRequests = localizations.ensureRecordPending(
            question,
            target,
            hashes,
            requestedAt,
            requestedAt.minus(REQUEST_RETRY_DELAY),
        )
        if (pendingRequests.isEmpty()) return
        val outboxIds = pendingRequests.map { request ->
            events.append(
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
        if (outboxIds.isNotEmpty()) {
            afterCommit.execute {
                publisher.publishNow(outboxIds.map { OutboxReference(OutboxType.DOMAIN_EVENT, it) })
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requestComment(comment: QuestionCommentEntity, targetLanguage: String) {
        val target = QuestionLanguage.normalize(targetLanguage)
        val hash = sha256(comment.body)
        val requestedAt = Instant.now()
        val request = localizations.ensureCommentPending(
            comment,
            target,
            hash,
            requestedAt,
            requestedAt.minus(REQUEST_RETRY_DELAY),
        ) ?: return
        val outboxId = events.append(
            ContentTranslationRequestedEvent(
                eventId = "content-translation-${request.requestToken}",
                contentType = LocalizableContentType.COMMENT,
                contentId = comment.id,
                targetLanguage = target,
                sourceHash = hash,
                requestedAt = requestedAt,
            ),
            requestedAt,
        )
        afterCommit.execute {
            publisher.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)))
        }
    }

    companion object {
        private val REQUEST_RETRY_DELAY: Duration = Duration.ofMinutes(5)

        fun recordHashes(question: QuestionEntity): RecordSourceHashes {
            val questionHash = sha256(
                listOf(
                    question.topic,
                    question.question,
                    question.hint.orEmpty(),
                ).joinToString("\u001f"),
            )
            val answerHash = question.answer
                ?.takeIf(String::isNotBlank)
                ?.let(::sha256)
            val aiResponseHash = if (
                !question.feedback.isNullOrBlank() ||
                !question.explanation.isNullOrBlank() ||
                !question.gradingAssessmentJson.isNullOrBlank()
            ) {
                sha256(
                    listOf(
                        question.feedback.orEmpty(),
                        question.explanation.orEmpty(),
                        question.gradingAssessmentJson.orEmpty(),
                    ).joinToString("\u001f"),
                )
            } else {
                null
            }
            return RecordSourceHashes(
                record = sha256(
                    listOf(questionHash, answerHash.orEmpty(), aiResponseHash.orEmpty())
                        .joinToString("\u001f"),
                ),
                question = questionHash,
                answer = answerHash,
                aiResponse = aiResponseHash,
            )
        }

        fun recordHash(question: QuestionEntity): String = recordHashes(question).record

        fun legacyRecordHash(question: QuestionEntity): String = sha256(
            listOf(
                question.topic,
                question.question,
                question.hint.orEmpty(),
                question.answer.orEmpty(),
                question.feedback.orEmpty(),
                question.explanation.orEmpty(),
                question.gradingAssessmentJson.orEmpty(),
            ).joinToString("\u001f"),
        )

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
