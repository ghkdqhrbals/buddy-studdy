package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionTranslationUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionTranslationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.QuestionLanguage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QuestionTranslationService(
    private val questions: QuestionPort,
    private val translations: QuestionTranslationPort,
    private val users: UserPort,
    private val studies: StudyPort,
    private val writer: QuestionTranslationExecutionWriteUseCase,
    private val publisher: PublishOutboxUseCase,
) : ProcessQuestionTranslationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: QuestionGeneratedEvent) {
        val claimed = writer.claim(event, Instant.now()) ?: return
        try {
            val question = checkNotNull(questions.findQuestionById(event.questionId)) {
                "Question was not found for translation."
            }
            val user = checkNotNull(users.findById(event.userId)) {
                "Question owner was not found."
            }
            val userStudies = studies.findAllByUserId(event.userId)
            val topicStudy = userStudies.firstOrNull { it.id == event.topicId }
                ?: error("Question topic study was not found.")
            val rootStudy = StudyTreeSelector.rootFor(topicStudy, userStudies)
            check(rootStudy.id == event.studyId) {
                "Question topic does not belong to the event root study."
            }
            val translation = translatedContent(
                topic = question.topic,
                question = question.question,
                hint = question.hint,
                sourceLanguage = event.sourceLanguage,
                alreadyReady = question.translationStatus == READY &&
                    !question.topicEn.isNullOrBlank() &&
                    !question.questionEn.isNullOrBlank(),
            )
            val result = writer.complete(
                event = event,
                claim = claimed.inbox,
                translation = translation,
                rootStudy = rootStudy,
                appLanguage = QuestionLanguage.normalize(user.appLanguage),
                now = Instant.now(),
            )
            runCatching { publisher.publishNow(result.outboxes) }
                .onFailure {
                    log.warn(
                        "question_delivery_immediate_publish_failed correlationId={} questionId={} error={}",
                        event.correlationId,
                        event.questionId,
                        it.message,
                    )
                }
            log.info(
                "question_generation_saga_completed correlationId={} questionId={} source={}",
                event.correlationId,
                event.questionId,
                event.source,
            )
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (claimed.inbox.attempt < MAX_ATTEMPTS) {
                writer.retry(claimed.inbox, message, Instant.now())
                throw error
            }
            writer.fail(event, claimed.inbox, message, Instant.now())
            log.warn(
                "question_translation_failed correlationId={} questionId={} attempts={} errorType={} error={}",
                event.correlationId,
                event.questionId,
                claimed.inbox.attempt,
                error.javaClass.name,
                message,
            )
        }
    }

    private suspend fun translatedContent(
        topic: String,
        question: String,
        hint: String?,
        sourceLanguage: String,
        alreadyReady: Boolean,
    ): TranslatedQuestionContent? {
        if (alreadyReady) return null
        val normalizedSource = QuestionLanguage.normalize(sourceLanguage)
        val translated = if (normalizedSource == QuestionLanguage.ENGLISH) {
            TranslatedQuestionContent(topic, question, hint)
        } else {
            translations.translateToEnglish(topic, question, hint, normalizedSource)
        }
        check(QuestionLanguage.matchesShortLabel(translated.topic, QuestionLanguage.ENGLISH)) {
            "Question translation did not produce an English topic."
        }
        check(QuestionLanguage.matches(translated.question, QuestionLanguage.ENGLISH)) {
            "Question translation did not produce English content."
        }
        return translated
    }

    private companion object {
        const val READY = "READY"
        const val MAX_ATTEMPTS = 3
    }
}
