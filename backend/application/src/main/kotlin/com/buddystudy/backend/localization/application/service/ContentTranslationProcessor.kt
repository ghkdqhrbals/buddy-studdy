package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.backend.common.application.stream.StreamRetryScheduledException
import com.buddystudy.backend.common.application.privacy.PrivateLearningContentLogScope
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.localization.application.port.ProcessContentTranslationUseCase
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.localization.application.port.UnavailableVoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyRecordType
import kotlinx.coroutines.CancellationException
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
    private val voiceLocalizations: VoiceStudyLearningLocalizationPort = UnavailableVoiceStudyLearningLocalizationPort,
) : ProcessContentTranslationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: ContentTranslationRequestedEvent, streamKey: String) {
        val claim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = CONSUMER_GROUP,
            correlationId = inboxCorrelationId(event.eventId),
            leaseDuration = Duration.ofMinutes(3),
            now = Instant.now(),
            streamKey = streamKey,
        ) ?: return
        try {
            when (event.contentType) {
                // Drain pre-separation work without translating bundled content.
                LocalizableContentType.RECORD -> Unit
                LocalizableContentType.QUESTION -> processQuestion(event)
                LocalizableContentType.ANSWER -> processAnswer(event)
                LocalizableContentType.AI_RESPONSE -> processAiResponse(event)
                LocalizableContentType.COMMENT -> processComment(event)
                LocalizableContentType.VOICE_STUDY_RECORD -> processVoiceStudyRecord(event)
            }
            check(inbox.markSucceeded(claim, Instant.now()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val errorType = error.javaClass.name
            val errorMessage = if (event.contentType == LocalizableContentType.VOICE_STUDY_RECORD) {
                "Voice learning record translation failed."
            } else error.message ?: error.javaClass.simpleName
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
                // The shared stream dispatcher logs the cause message; never carry a private provider body to it.
                val retryCause = if (event.contentType == LocalizableContentType.VOICE_STUDY_RECORD) {
                    IllegalStateException("Voice learning record translation failed ($errorType).")
                } else error
                throw StreamRetryScheduledException(errorMessage, retryCause)
            }
            if (event.contentType == LocalizableContentType.VOICE_STUDY_RECORD) {
                voiceLocalizations.markFailed(event, errorMessage, now)
            } else {
                localizations.markFailed(event, errorMessage, now)
            }
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

    private suspend fun processVoiceStudyRecord(event: ContentTranslationRequestedEvent) {
        val record = voiceLocalizations.content(event.contentId) ?: return
        if (record.sourceHash != event.sourceHash) return
        val result = PrivateLearningContentLogScope.protecting {
            translator.translate(record.translatableFields(), record.sourceLanguages, event.targetLanguage)
        }
        voiceLocalizations.saveReady(record, event, result, Instant.now())
    }

    private suspend fun processQuestion(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        if (question.recordType != StudyRecordType.QUESTION) return
        processQuestion(event, question)
    }

    private suspend fun processQuestion(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val sourceHash = ContentSourceHashPolicy.recordHashes(question).question
        if (sourceHash != event.sourceHash) return
        val fields = linkedMapOf<String, String?>(
            "topic" to question.topic,
            "question" to question.question,
            "hint" to question.hint,
        )
        val sources = fields.keys.associateWith { question.sourceLanguage.databaseValue }
        val result = translator.translate(
            fields.filterValues { !it.isNullOrBlank() },
            sources,
            event.targetLanguage,
        )
        localizations.saveQuestionReady(question, event.targetLanguage, sourceHash, result, Instant.now())
    }

    private suspend fun processAnswer(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        if (question.recordType != StudyRecordType.QUESTION) return
        processAnswer(event, question)
    }

    private suspend fun processAnswer(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val answer = question.answer?.takeIf(String::isNotBlank) ?: return
        val sourceHash = ContentSourceHashPolicy.recordHashes(question).answer
        if (sourceHash == null || sourceHash != event.sourceHash) return
        val sourceLanguage = (question.answerSourceLanguage ?: question.sourceLanguage).databaseValue
        val result = translator.translate(
            fields = mapOf("answer" to answer),
            sourceLanguages = mapOf("answer" to sourceLanguage),
            targetLanguage = event.targetLanguage,
        )
        localizations.saveAnswerReady(question, event.targetLanguage, sourceHash, result, Instant.now())
    }

    private suspend fun processAiResponse(event: ContentTranslationRequestedEvent) {
        val question = questions.findQuestionById(event.contentId) ?: return
        if (question.recordType != StudyRecordType.QUESTION) return
        processAiResponse(event, question)
    }

    private suspend fun processAiResponse(
        event: ContentTranslationRequestedEvent,
        question: QuestionEntity,
    ) {
        val sourceHash = ContentSourceHashPolicy.recordHashes(question).aiResponse
        if (sourceHash == null || sourceHash != event.sourceHash) return
        val fields = linkedMapOf(
            "feedback" to question.feedback,
            "explanation" to question.explanation,
        ).filterValues { !it.isNullOrBlank() }
        if (fields.isEmpty()) return
        val sourceLanguage = (question.aiResponseSourceLanguage ?: question.sourceLanguage).databaseValue
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
        if (ContentSourceHashPolicy.sha256(comment.body) != event.sourceHash) return
        val result = translator.translate(
            fields = mapOf("body" to comment.body),
            sourceLanguages = mapOf("body" to comment.sourceLanguage.databaseValue),
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
            ContentSourceHashPolicy.sha256(eventId).take(INBOX_CORRELATION_ID_LENGTH)
    }
}
