package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.localization.application.port.ProcessContentTranslationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.entity.QuestionEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ContentTranslationProcessor(
    private val questions: QuestionPort,
    private val comments: QuestionCommentPort,
    private val localizations: ContentLocalizationPort,
    private val translator: ContentTranslationPort,
    private val inbox: StreamInboxPort,
) : ProcessContentTranslationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: ContentTranslationRequestedEvent) {
        val claim = inbox.claim(
            event.eventId,
            CONSUMER_GROUP,
            inboxCorrelationId(event.eventId),
            Duration.ofMinutes(3),
            Instant.now(),
        ) ?: return
        try {
            when (event.contentType) {
                // Drain pre-separation work without translating bundled content.
                LocalizableContentType.RECORD -> Unit
                LocalizableContentType.QUESTION -> processQuestion(event)
                LocalizableContentType.ANSWER -> processAnswer(event)
                LocalizableContentType.AI_RESPONSE -> processAiResponse(event)
                LocalizableContentType.COMMENT -> processComment(event)
            }
            check(inbox.markSucceeded(claim, Instant.now()))
        } catch (error: Exception) {
            val errorType = error.javaClass.name
            val errorMessage = error.message ?: error.javaClass.simpleName
            val now = Instant.now()
            if (claim.attempt < MAX_ATTEMPTS) {
                check(inbox.releaseForRetry(claim, errorType, errorMessage, now))
                log.warn(
                    "content_translation_retry_scheduled eventId={} contentType={} contentId={} targetLanguage={} attempt={} maxAttempts={} errorType={} error={}",
                    event.eventId,
                    event.contentType,
                    event.contentId,
                    event.targetLanguage,
                    claim.attempt,
                    MAX_ATTEMPTS,
                    errorType,
                    errorMessage,
                )
                throw error
            }
            localizations.markFailed(event, errorMessage, now)
            check(inbox.markFailed(claim, errorType, errorMessage, now))
            log.error(
                "content_translation_terminal_failure eventId={} contentType={} contentId={} targetLanguage={} attempt={} maxAttempts={} errorType={} error={}",
                event.eventId,
                event.contentType,
                event.contentId,
                event.targetLanguage,
                claim.attempt,
                MAX_ATTEMPTS,
                errorType,
                errorMessage,
            )
        }
    }

    private suspend fun processQuestion(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        processQuestion(event, question)
    }

    private suspend fun processQuestion(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val sourceHash = ContentLocalizationService.recordHashes(question).question
        if (sourceHash != event.sourceHash) return
        val fields = linkedMapOf<String, String?>(
            "topic" to question.topic,
            "question" to question.question,
            "hint" to question.hint,
        )
        val sources = fields.keys.associateWith { question.sourceLanguage }
        val result = translator.translate(
            fields.filterValues { !it.isNullOrBlank() },
            sources,
            event.targetLanguage,
        )
        localizations.saveQuestionReady(question, event.targetLanguage, sourceHash, result, Instant.now())
    }

    private suspend fun processAnswer(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        processAnswer(event, question)
    }

    private suspend fun processAnswer(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val answer = question.answer?.takeIf(String::isNotBlank) ?: return
        val sourceHash = ContentLocalizationService.recordHashes(question).answer
        if (sourceHash == null || sourceHash != event.sourceHash) return
        val sourceLanguage = question.answerSourceLanguage ?: question.sourceLanguage
        val result = translator.translate(
            fields = mapOf("answer" to answer),
            sourceLanguages = mapOf("answer" to sourceLanguage),
            targetLanguage = event.targetLanguage,
        )
        localizations.saveAnswerReady(question, event.targetLanguage, sourceHash, result, Instant.now())
    }

    private suspend fun processAiResponse(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        processAiResponse(event, question)
    }

    private suspend fun processAiResponse(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val sourceHash = ContentLocalizationService.recordHashes(question).aiResponse
        if (sourceHash == null || sourceHash != event.sourceHash) return
        val fields = linkedMapOf(
            "feedback" to question.feedback,
            "explanation" to question.explanation,
        ).filterValues { !it.isNullOrBlank() }
        if (fields.isEmpty()) return
        val sourceLanguage = question.aiResponseSourceLanguage ?: question.sourceLanguage
        val translated = translator.translate(
            fields = fields,
            sourceLanguages = fields.keys.associateWith { sourceLanguage },
            targetLanguage = event.targetLanguage,
        )
        val result = translated.copy(
            fields = translated.fields + ("assessmentJson" to question.gradingAssessmentJson),
        )
        localizations.saveAiResponseReady(question, event.targetLanguage, sourceHash, result, Instant.now())
    }

    private suspend fun processComment(event: ContentTranslationRequestedEvent) {
        val comment = comments.findById(event.contentId) ?: return
        if (ContentLocalizationService.sha256(comment.body) != event.sourceHash) return
        val result = translator.translate(
            fields = mapOf("body" to comment.body),
            sourceLanguages = mapOf("body" to comment.sourceLanguage),
            targetLanguage = event.targetLanguage,
        )
        localizations.saveCommentReady(comment, event.targetLanguage, event.sourceHash, result, Instant.now())
    }

    companion object {
        const val CONSUMER_GROUP = "bs-backend-content-translation"
        const val RECOVERY_MIN_IDLE_TIME_MILLIS = 210_000L
        private const val MAX_ATTEMPTS = 3
        private const val INBOX_CORRELATION_ID_LENGTH = 36

        internal fun inboxCorrelationId(eventId: String): String =
            ContentLocalizationService.sha256(eventId).take(INBOX_CORRELATION_ID_LENGTH)
    }
}
