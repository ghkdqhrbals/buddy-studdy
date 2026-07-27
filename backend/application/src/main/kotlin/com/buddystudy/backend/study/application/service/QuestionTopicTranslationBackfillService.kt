package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.study.application.port.inbound.BackfillQuestionTopicsUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionTopicTranslationBackfillResult
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.study.domain.QuestionLanguage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QuestionTopicTranslationBackfillService(
    private val questions: QuestionPort,
    private val translations: QuestionTranslationPort,
) : BackfillQuestionTopicsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun backfill(limit: Int): QuestionTopicTranslationBackfillResult {
        val candidates = questions.findEnglishTopicBackfillCandidates(limit.coerceIn(1, 100))
        var translatedCount = 0
        var failedCount = 0

        candidates.forEach { question ->
            try {
                val translated = translations.translateToEnglish(
                    topic = question.topic,
                    question = question.question,
                    hint = question.hint,
                    sourceLanguage = QuestionLanguage.normalize(question.language),
                )
                if (questions.saveEnglishTopicTranslation(question.id, translated.topic, Instant.now())) {
                    translatedCount++
                }
            } catch (error: Exception) {
                failedCount++
                log.warn(
                    "question_topic_translation_backfill_failed questionId={} errorType={} error={}",
                    question.id,
                    error.javaClass.simpleName,
                    error.message,
                )
            }
        }

        return QuestionTopicTranslationBackfillResult(
            candidates = candidates.size,
            translated = translatedCount,
            failed = failedCount,
        )
    }
}
