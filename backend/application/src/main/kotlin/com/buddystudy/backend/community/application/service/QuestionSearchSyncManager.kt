package com.buddystudy.backend.community.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class QuestionSearchSyncManager(
    private val properties: BuddyStudyProperties,
    private val questions: QuestionPort,
    private val users: UserPort,
    private val search: QuestionSearchPort,
    private val translator: QuestionSearchTranslationPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun indexCreatedQuestion(questionId: Long) {
        val question = questions.findQuestionById(questionId).orElse(null)
        if (question == null) {
            search.deleteByQuestionId(questionId)
            return
        }
        refreshIndexedQuestion(question)
    }

    fun refreshIndexedQuestion(question: QuestionEntity) {
        val user = question.userId?.let { users.findById(it).orElse(null) }
        refreshIndexedQuestion(question, user)
    }

    fun refreshIndexedQuestion(question: QuestionEntity, user: UserEntity?) {
        if (user == null) {
            search.deleteByQuestionId(question.id)
            return
        }
        val sourceLanguage = question.language.normalizedSearchLanguage()
        val targetLanguages = properties.translation.supportedLanguages
            .map { it.normalizedSearchLanguage() }
            .toSet()
            .plus(sourceLanguage)
        targetLanguages.forEach { language ->
            val translated = if (language == sourceLanguage) {
                question.toSearchText()
            } else {
                translateOrFallback(question, sourceLanguage, language)
            }
            search.save(question.toSearchEntity(user, language, translated))
        }
    }

    fun removeIndexedQuestion(questionId: Long) {
        search.deleteByQuestionId(questionId)
    }

    fun removeIndexedStudy(studyId: Long, userId: Long) {
        search.deleteByStudyId(studyId, userId)
    }

    fun removeIndexedStudyTopic(userId: Long, topic: String) {
        search.deleteByUserIdAndTopic(userId, topic)
    }

    fun findIndexedQuestion(questionId: Long, language: String): QuestionSearchEntity? =
        search.findByQuestionIdAndLanguage(questionId, language)

    private fun QuestionEntity.toSearchEntity(
        user: UserEntity,
        language: String,
        text: TranslatedQuestionSearchText,
    ): QuestionSearchEntity =
        QuestionSearchEntity(
            questionId = id,
            language = language,
            userId = user.id,
            topic = topic,
            question = text.question,
            answer = text.answer,
            feedback = text.feedback,
            explanation = text.explanation,
            authorDisplayName = user.displayName,
            publicQuestion = publicQuestion,
            score = score,
            answeredAt = answeredAt,
            deletedAt = deletedAt,
            createdAt = createdAt,
            updatedAt = Instant.now(),
        )

    private fun translateOrFallback(
        question: QuestionEntity,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslatedQuestionSearchText {
        return runCatching {
            translator.translateSearchText(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                topic = question.topic,
                question = question.question,
                answer = question.answer,
                feedback = question.feedback,
                explanation = question.explanation,
            )
        }.onFailure { error ->
            logger.warn(
                "question_search_translation_failed questionId={} targetLanguage={} error={}",
                question.id,
                targetLanguage,
                error.message,
            )
        }.getOrElse { question.toSearchText() }
    }

    private fun QuestionEntity.toSearchText(): TranslatedQuestionSearchText =
        TranslatedQuestionSearchText(
            topic = topic,
            question = question,
            answer = answer,
            feedback = feedback,
            explanation = explanation,
        )

    private fun String.normalizedSearchLanguage(): String =
        if (lowercase().startsWith("en")) "en" else "ko"
}
