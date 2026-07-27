package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.localization.application.port.ProcessContentTranslationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.QuestionLanguage
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
    override suspend fun process(event: ContentTranslationRequestedEvent) {
        val claim = inbox.claim(
            event.eventId,
            CONSUMER_GROUP,
            event.eventId,
            Duration.ofMinutes(3),
            Instant.now(),
        ) ?: return
        try {
            when (event.contentType) {
                LocalizableContentType.RECORD -> processRecord(event)
                LocalizableContentType.COMMENT -> processComment(event)
            }
            check(inbox.markSucceeded(claim, Instant.now()))
        } catch (error: Exception) {
            if (claim.attempt < MAX_ATTEMPTS) {
                check(inbox.releaseForRetry(claim, error.message ?: error.javaClass.simpleName, Instant.now()))
                throw error
            }
            localizations.markFailed(event, error.message ?: error.javaClass.simpleName, Instant.now())
            check(inbox.markSucceeded(claim, Instant.now()))
        }
    }

    private suspend fun processRecord(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        val hashes = ContentLocalizationService.recordHashes(question)
        if (hashes.record != event.sourceHash) return
        val fields = linkedMapOf<String, String?>(
            "topic" to question.topic,
            "question" to question.question,
            "hint" to question.hint,
            "answer" to question.answer,
            "feedback" to question.feedback,
            "explanation" to question.explanation,
            "assessmentJson" to question.gradingAssessmentJson,
        )
        val sources = fields.keys.associateWith { field ->
            when (field) {
                "answer" -> question.answerSourceLanguage ?: question.sourceLanguage
                "feedback", "explanation", "assessmentJson" ->
                    question.aiResponseSourceLanguage ?: question.sourceLanguage
                else -> question.sourceLanguage
            }
        }
        val translatable = fields.filter { (name, value) ->
            name != "assessmentJson" &&
                !value.isNullOrBlank() &&
                QuestionLanguage.normalize(sources.getValue(name)) != event.targetLanguage
        }
        val translated = translator.translate(translatable, sources, event.targetLanguage)
        val result = translated.copy(
            fields = translated.fields + ("assessmentJson" to question.gradingAssessmentJson),
        )
        localizations.saveRecordReady(question, event.targetLanguage, hashes, result, Instant.now())
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
    }
}
