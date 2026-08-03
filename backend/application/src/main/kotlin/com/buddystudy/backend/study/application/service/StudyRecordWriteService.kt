package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.CompletedAnswerGrading
import com.buddystudy.backend.study.application.port.inbound.QueuedAnswerGrading
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class StudyRecordWriteService(
    private val questions: QuestionPort,
    private val questionCoverage: QuestionCoveragePort,
    private val gradingProgress: AnswerGradingProgressPort,
    private val redisOutbox: RedisEventOutboxAppendPort,
    private val languageDetector: ContentLanguageDetectionPort,
    private val translationRequests: ContentTranslationRequestAppendPort,
) : StudyRecordWriteUseCase, AnswerGradingWriteUseCase {
    @Transactional
    override suspend fun answer(
        userId: Long,
        recordId: Long,
        answer: String,
        sourceLanguage: String,
        grade: GradedAnswer?,
        now: Instant,
    ): QuestionWriteResult {
        val question = lockRecord(recordId, userId)
        if (question.gradingRequestId != null ||
            question.gradingStatus != null ||
            question.score != null ||
            question.status == QuestionStatus.GRADING ||
            question.status == QuestionStatus.GRADED
        ) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.ANSWER_ALREADY_SUBMITTED,
                "An answer has already been submitted for this question.",
                metadata = mapOf(
                    "recordId" to recordId,
                    "gradingRequestId" to question.gradingRequestId,
                    "gradingStatus" to question.gradingStatus?.name,
                ),
            )
        }
        val record = question.toStudyRecord()
        question.apply(record.answer(answer, sourceLanguage))
        if (grade != null && question.score == null) {
            question.applyGradingMetadata(grade)
            val aiResponseLanguage = languageDetector.detect(
                "${grade.feedback}\n${grade.explanation}",
                sourceLanguage,
            )
            question.apply(
                record.grade(
                    grade.score,
                    grade.isCorrect,
                    grade.feedback,
                    grade.explanation,
                    aiResponseLanguage,
                    now,
                ),
            )
            if (question.conceptId != null && question.angleKey != null) {
                questionCoverage.markAnswered(
                    question.conceptId!!,
                    question.angleKey!!,
                    grade.score,
                    grade.isCorrect,
                    now,
                )
            }
        }
        val saved = questions.save(question)
        return QuestionWriteResult(
            question = saved,
            outboxes = translationRequests.appendRecordForSupportedLanguages(saved, now),
        )
    }

    @Transactional
    override suspend fun skip(userId: Long, recordId: Long): QuestionEntity {
        val question = lockRecord(recordId, userId)
        question.apply(question.toStudyRecord().skip())
        return questions.save(question)
    }

    @Transactional
    override suspend fun delete(userId: Long, recordId: Long, now: Instant) {
        lockRecord(recordId, userId)
        val deleted = questions.softDelete(recordId, userId, now)
        check(deleted == 1) { "Expected one record to be deleted, but updated $deleted." }
    }

    @Transactional
    override suspend fun clear(userId: Long, now: Instant) {
        questions.softDeleteByUserId(userId, now)
    }

    @Transactional
    override suspend fun updatePublicity(userId: Long, recordId: Long, isPublic: Boolean): QuestionEntity {
        val question = lockRecord(recordId, userId)
        question.apply(question.toStudyRecord().restrictPublicity(isPublic))
        return questions.save(question)
    }

    @Transactional
    override suspend fun queue(
        userId: Long,
        recordId: Long,
        answer: String,
        sourceLanguage: String,
        aiResponseLanguage: String,
        now: Instant,
    ): QueuedAnswerGrading {
        val question = lockRecord(recordId, userId)
        val normalizedAnswer = answer.trim()
        val persistedAnswer = question.answer?.trim()?.takeIf { it.isNotEmpty() }
        if ((persistedAnswer != null && persistedAnswer != normalizedAnswer) ||
            question.gradingRequestId != null ||
            question.gradingStatus != null ||
            question.score != null ||
            question.status == QuestionStatus.GRADING ||
            question.status == QuestionStatus.GRADED
        ) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.ANSWER_ALREADY_SUBMITTED,
                "An answer has already been submitted for this question.",
                metadata = mapOf(
                    "recordId" to recordId,
                    "gradingRequestId" to question.gradingRequestId,
                    "gradingStatus" to question.gradingStatus?.name,
                ),
            )
        }

        val requestId = UUID.randomUUID().toString()
        val event = AnswerGradingRequestedEvent(
            eventId = "answer-grading-$recordId-$requestId",
            requestId = requestId,
            recordId = recordId,
            userId = userId,
            requestedAt = now,
            responseLanguage = aiResponseLanguage,
        )
        if (persistedAnswer == null) {
            question.apply(question.toStudyRecord().answer(normalizedAnswer, sourceLanguage, now))
        }
        question.gradingRequestId = requestId
        question.gradingStatus = AnswerGradingStatus.QUEUED
        question.status = QuestionStatus.GRADING
        question.gradingError = null
        question.gradingRequestedAt = now
        question.gradingStartedAt = null
        val progress = gradingProgress.append(
            recordId,
            userId,
            requestId,
            AnswerGradingStatus.QUEUED,
            QuestionStatus.GRADING,
            null,
            now,
        )
        question.gradingLastEventId = progress.id
        val saved = questions.save(question)
        val outboxId = redisOutbox.appendAnswerGrading(event, now)
        val translationOutboxes = translationRequests.appendRecordForSupportedLanguages(saved, now)
        return QueuedAnswerGrading(
            saved,
            listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)) + translationOutboxes,
        )
    }

    @Transactional
    override suspend fun transition(
        event: AnswerGradingRequestedEvent,
        status: AnswerGradingStatus,
        now: Instant,
    ): Boolean {
        require(!status.terminal) { "Terminal grading status must use complete or fail." }
        val question = lockRecord(event.recordId, event.userId)
        if (question.gradingRequestId != event.requestId || question.score != null) return false
        if (question.gradingStatus == AnswerGradingStatus.FAILED ||
            question.gradingStatus == AnswerGradingStatus.COMPLETED
        ) {
            return false
        }
        if (question.gradingStatus == status) return true
        question.gradingStatus = status
        question.gradingStartedAt = question.gradingStartedAt ?: now
        question.updatedAt = now
        val progress = gradingProgress.append(
            event.recordId,
            event.userId,
            event.requestId,
            status,
            QuestionStatus.GRADING,
            null,
            now,
        )
        question.gradingLastEventId = progress.id
        questions.save(question)
        return true
    }

    @Transactional
    override suspend fun complete(
        event: AnswerGradingRequestedEvent,
        grade: GradedAnswer,
        now: Instant,
    ): CompletedAnswerGrading {
        val question = lockRecord(event.recordId, event.userId)
        if (question.gradingRequestId != event.requestId) return CompletedAnswerGrading(false)
        if (question.score != null && question.gradingStatus == AnswerGradingStatus.COMPLETED) {
            return CompletedAnswerGrading(true)
        }
        if (question.gradingStatus == AnswerGradingStatus.FAILED) return CompletedAnswerGrading(false)
        val record = question.toStudyRecord()
        question.applyGradingMetadata(grade)
        val aiResponseLanguage = languageDetector.detect(
            "${grade.feedback}\n${grade.explanation}",
            event.responseLanguage,
        )
        question.apply(
            record.grade(
                grade.score,
                grade.isCorrect,
                grade.feedback,
                grade.explanation,
                aiResponseLanguage,
                now,
            ),
        )
        question.gradingStatus = AnswerGradingStatus.COMPLETED
        question.gradingError = null
        if (question.conceptId != null && question.angleKey != null) {
            questionCoverage.markAnswered(
                question.conceptId!!,
                question.angleKey!!,
                grade.score,
                grade.isCorrect,
                now,
            )
        }
        val progress = gradingProgress.append(
            event.recordId,
            event.userId,
            event.requestId,
            AnswerGradingStatus.COMPLETED,
            QuestionStatus.GRADED,
            null,
            now,
        )
        question.gradingLastEventId = progress.id
        val saved = questions.save(question)
        return CompletedAnswerGrading(
            completed = true,
            outboxes = translationRequests.appendRecordForSupportedLanguages(saved, now),
        )
    }

    @Transactional
    override suspend fun fail(
        event: AnswerGradingRequestedEvent,
        errorMessage: String,
        now: Instant,
    ) {
        val question = lockRecord(event.recordId, event.userId)
        if (question.gradingRequestId != event.requestId || question.score != null) return
        if (question.gradingStatus == AnswerGradingStatus.FAILED ||
            question.gradingStatus == AnswerGradingStatus.COMPLETED
        ) {
            return
        }
        question.gradingStatus = AnswerGradingStatus.FAILED
        question.status = QuestionStatus.FAILED
        question.gradingError = errorMessage.take(255)
        question.updatedAt = now
        val progress = gradingProgress.append(
            event.recordId,
            event.userId,
            event.requestId,
            AnswerGradingStatus.FAILED,
            QuestionStatus.FAILED,
            question.gradingError,
            now,
        )
        question.gradingLastEventId = progress.id
        questions.save(question)
    }

    private suspend fun lockRecord(recordId: Long, userId: Long): QuestionEntity =
        questions.lockByIdAndUserIdAndDeletedAtIsNull(recordId, userId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RECORD_NOT_FOUND,
                "Record not found.",
            )

}
