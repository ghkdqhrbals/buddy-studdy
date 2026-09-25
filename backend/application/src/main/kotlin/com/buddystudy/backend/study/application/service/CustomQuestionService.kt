package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.sha256
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.study.application.port.inbound.CreateCustomQuestionUseCase
import com.buddystudy.backend.study.application.port.outbound.CustomQuestionReceipt
import com.buddystudy.backend.study.application.port.outbound.CustomQuestionRequestPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import java.time.Instant

@Service
class CustomQuestionService(
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val requests: CustomQuestionRequestPort,
) : CreateCustomQuestionUseCase {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permissions.RECORD_UPDATE)
    override suspend fun create(
        principal: Principal,
        studyId: Long,
        idempotencyKey: String,
        question: String,
        answer: String,
        language: String,
    ): StudyRecordResponse {
        val questionText = question.trim()
        val answerText = answer.trim()
        if (!KEY.matches(idempotencyKey) || questionText.isBlank() || questionText.length > 4_000 ||
            answerText.isBlank() || answerText.length > 12_000 || language !in setOf("ko", "en", "ja")
        ) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Provide a question (1–4000 characters), answer (1–12000 characters), language (ko, en, ja), and valid Idempotency-Key.")
        }
        // Serialize receipts per account, including concurrent requests from different devices/topics.
        if (!requests.lockOwner(principal.userId)) missing()
        val hash = sha256(listOf(studyId.toString(), language, questionText, answerText).joinToString("") { "${it.length}:$it" })
        requests.find(principal.userId, idempotencyKey)?.let { receipt ->
            if (receipt.requestHash != hash) {
                throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "Idempotency-Key already belongs to another custom question.")
            }
            return response(questions.findByIdAndUserIdAndDeletedAtIsNull(receipt.questionId, principal.userId) ?: missing())
        }
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val now = Instant.now()
        val saved = questions.save(
            QuestionEntity(
                deviceId = principal.deviceId, userId = principal.userId, studyId = study.id,
                question = questionText, answer = answerText, topic = study.topic,
                sourceLanguage = SupportedLanguage.fromLocale(language),
                answerSourceLanguage = SupportedLanguage.fromLocale(language),
                // The existing terminal wire status keeps older clients able to decode record pages.
                // source + absent grading fields identify authored content; no grading was performed.
                difficultyLevel = study.difficultyLevel, status = QuestionStatus.GRADED,
                source = QuestionSource.CUSTOM_QUESTION, publicQuestion = false,
                scheduledFor = now, answeredAt = now, createdAt = now, updatedAt = now,
            ),
        )
        requests.save(principal.userId, idempotencyKey, CustomQuestionReceipt(saved.id, hash), now)
        return response(saved)
    }

    private fun response(question: QuestionEntity) = question.toStudyRecord().toProjection().toRecordResponse(
        viewMode = TranslationViewMode.ORIGINAL, questionTranslationPending = false,
        answerTranslationPending = false, aiResponseTranslationPending = false, answerAuthorOriginal = true,
    )

    private fun missing(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")

    private companion object { val KEY = Regex("[A-Za-z0-9_-]{8,120}") }
}
