package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.localization.application.policy.QuestionChoiceLocalizationPolicy
import com.buddystudy.backend.common.application.stream.StreamRetryScheduledException
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
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

    override suspend fun process(event: QuestionGeneratedEvent, streamKey: String) {
        val claimed = writer.claim(event, Instant.now(), streamKey) ?: return
        val result = try {
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
                targetLanguage = QuestionLanguage.normalize(user.appLanguage.databaseValue),
                choiceOptionsJson = question.choiceOptionsJson,
            )
            writer.complete(
                event = event,
                translation = translation,
                rootStudy = rootStudy,
                appLanguage = QuestionLanguage.normalize(user.appLanguage.databaseValue),
                now = Instant.now(),
            )
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (claimed.inbox.attempt < MAX_ATTEMPTS) {
                writer.retry(claimed.inbox, message, Instant.now())
                throw StreamRetryScheduledException(message, error)
            }
            val rollbackOutbox = writer.fail(event, message, Instant.now())
            writer.completeFailure(claimed.inbox, message, Instant.now())
            rollbackOutbox?.let { reference ->
                runCatching { publisher.publishNow(listOf(reference)) }
                    .onFailure {
                        log.warn(
                            "question_translation_rollback_immediate_publish_failed correlationId={} questionId={} error={}",
                            event.correlationId,
                            event.questionId,
                            it.message,
                        )
                    }
            }
            log.warn(
                "question_translation_failed correlationId={} questionId={} attempts={} errorType={} error={}",
                event.correlationId,
                event.questionId,
                claimed.inbox.attempt,
                error.javaClass.name,
                message,
            )
            return
        }
        writer.succeed(claimed.inbox, Instant.now())
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
    }

    private suspend fun translatedContent(
        topic: String,
        question: String,
        hint: String?,
        sourceLanguage: String,
        targetLanguage: String,
        choiceOptionsJson: String? = null,
    ): TranslatedQuestionContent? {
        val normalizedSource = QuestionLanguage.normalize(sourceLanguage)
        val normalizedTarget = QuestionLanguage.normalize(targetLanguage)
        if (normalizedSource == normalizedTarget) return null
        val translated = translations.translate(
            topic = topic,
            question = question,
            hint = hint,
            sourceLanguage = normalizedSource,
            targetLanguage = normalizedTarget,
        )
        check(
            QuestionLanguage.matchesTranslation(
                source = topic,
                translated = translated.topic,
                targetLanguage = normalizedTarget,
                shortLabel = true,
            ),
        ) {
            "Question translation did not produce the requested topic language."
        }
        check(
            QuestionLanguage.matchesTranslation(
                source = question,
                translated = translated.question,
                targetLanguage = normalizedTarget,
            ),
        ) {
            "Question translation did not produce the requested content language."
        }
        val choices = QuestionChoiceCodec().decode(choiceOptionsJson)
        if (choices.isEmpty()) return translated
        val translatedChoices = choices.map { option ->
            val label = translations.translate(
                topic = "CONTENT",
                question = option.text,
                hint = null,
                sourceLanguage = normalizedSource,
                targetLanguage = normalizedTarget,
                validationMode = TranslationValidationMode.SHORT_TEXT,
            ).question
            check(label.isNotBlank()) { "Question translation returned an empty choice." }
            option.copy(text = label)
        }
        QuestionChoiceLocalizationPolicy.validateTranslatedOptions(translatedChoices)
        return translated.copy(choiceOptionsJson = QuestionChoiceCodec().encode(translatedChoices))
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
