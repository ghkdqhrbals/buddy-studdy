package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionTranslationUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionDeliveryWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.localizedFor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QuestionTranslationService(
    private val questions: QuestionPort,
    private val translations: QuestionTranslationPort,
    private val users: UserPort,
    private val studies: StudyPort,
    private val deliveryWriter: QuestionDeliveryWriteUseCase,
    private val outboxPublisher: PublishOutboxUseCase,
) : ProcessQuestionTranslationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: QuestionGeneratedEvent) {
        val question = questions.findQuestionById(event.questionId) ?: run {
            log.info("question_translation_skipped reason=question_missing questionId={}", event.questionId)
            return
        }
        if (question.translationStatus != READY || question.questionEn.isNullOrBlank()) {
            translate(question.id, question.question, question.hint, event.sourceLanguage)
        } else {
            log.debug("question_translation_skipped reason=already_ready questionId={}", question.id)
        }

        val translatedQuestion = checkNotNull(questions.findQuestionById(question.id)) {
            "Question disappeared after translation: ${question.id}"
        }
        val user = checkNotNull(users.findById(event.userId)) {
            "Question owner was not found: ${event.userId}"
        }
        val userStudies = studies.findAllByUserId(event.userId)
        val topicStudy = userStudies.firstOrNull { it.id == translatedQuestion.studyId }
            ?: error("Question study was not found: ${translatedQuestion.studyId}")
        val rootStudy = StudyTreeSelector.rootFor(topicStudy, userStudies)
        val appLanguage = QuestionLanguage.normalize(user.appLanguage)
        val localizedQuestion = translatedQuestion.localizedFor(appLanguage)
        val delivery = deliveryWriter.enqueue(
            question = localizedQuestion,
            rootStudy = rootStudy,
            appLanguage = appLanguage,
            now = Instant.now(),
        )
        outboxPublisher.publishNow(delivery.outboxes)
        log.info(
            "question_delivery_enqueued questionId={} language={} outboxes={}",
            question.id,
            appLanguage,
            delivery.outboxes.size,
        )
    }

    private suspend fun translate(
        questionId: Long,
        question: String,
        hint: String?,
        sourceLanguage: String,
    ) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val translated = runCatching {
                if (QuestionLanguage.normalize(sourceLanguage) == QuestionLanguage.ENGLISH) {
                    TranslatedQuestionContent(question = question, hint = hint)
                } else {
                    translations.translateToEnglish(
                        question = question,
                        hint = hint,
                        sourceLanguage = QuestionLanguage.normalize(sourceLanguage),
                    )
                }
            }.getOrElse { error ->
                lastError = error
                return@repeat
            }
            if (!QuestionLanguage.matches(translated.question, QuestionLanguage.ENGLISH)) {
                lastError = IllegalStateException("Question translation did not produce English content.")
                return@repeat
            }
            check(
                questions.saveEnglishTranslation(
                    questionId = questionId,
                    question = translated.question,
                    hint = translated.hint,
                    now = Instant.now(),
                ),
            ) { "Question disappeared while saving its translation." }
            log.info(
                "question_translation_completed questionId={} sourceLanguage={} targetLanguage=en attempt={}",
                questionId,
                sourceLanguage,
                attempt + 1,
            )
            return
        }

        val failure = lastError ?: IllegalStateException("Question translation failed.")
        questions.markEnglishTranslationFailed(questionId, failure.message ?: failure.javaClass.simpleName, Instant.now())
        throw failure
    }

    private companion object {
        const val READY = "READY"
        const val MAX_ATTEMPTS = 3
    }
}
