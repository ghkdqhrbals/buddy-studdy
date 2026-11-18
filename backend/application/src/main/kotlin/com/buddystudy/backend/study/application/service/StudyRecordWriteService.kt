package com.buddystudy.backend.study.application.service
import com.buddystudy.study.domain.entity.QuestionFormat
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.port.outbound.QuestionChoiceResponsePort

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.content.QuestionNotificationContentPolicy
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
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
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
    private val choiceResponses: QuestionChoiceResponsePort,
    private val choiceCodec: QuestionChoiceCodec,

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
        val question = lockGeneratedQuestion(recordId, userId)
        if (question.questionFormat == QuestionFormat.MULTIPLE_CHOICE) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Select an option to answer this question.")
        }
        if (question.source == QuestionSource.CUSTOM_QUESTION ||
            question.status != QuestionStatus.UNGRADED || question.skippedAt != null ||
            question.gradingRequestId != null ||
            question.gradingStatus != null ||
            question.score != null
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
        val question = lockGeneratedQuestion(recordId, userId)
        if (question.source == QuestionSource.CUSTOM_QUESTION) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A saved custom question cannot be skipped.")
        }
        // Repeated tools/requests observe the same result without changing its history.
        if (question.status == QuestionStatus.SKIPPED) return question
        if (question.status != QuestionStatus.UNGRADED || !question.answer.isNullOrBlank() ||
            question.answeredAt != null || question.score != null || question.gradedAt != null ||
            question.gradingRequestId != null || question.gradingStatus != null ||
            question.gradingRequestedAt != null || question.gradingStartedAt != null ||
            question.gradingLastEventId != null || question.skippedAt != null
        ) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Only an unanswered, ungraded question can be skipped.",
            )
        }
        question.apply(question.toStudyRecord().skip())
        return questions.save(question)
    }

    @Transactional
    override suspend fun delete(userId: Long, recordId: Long, now: Instant) {
        lockRecord(recordId, userId)
        val deleted = questions.softDelete(recordId, userId, now)
        check(deleted == 1) { "Expected one record to be deleted, but updated $deleted." }
        choiceResponses.deleteByQuestionId(recordId)
    }

    @Transactional
    override suspend fun clear(userId: Long, now: Instant) {
        questions.softDeleteByUserId(userId, now)
        choiceResponses.deleteByOwnerUserId(userId)
    }

    @Transactional
    override suspend fun updatePublicity(userId: Long, recordId: Long, isPublic: Boolean): QuestionEntity {
        val question = lockRecord(recordId, userId)
        if (isPublic && question.source in setOf(QuestionSource.CUSTOM_QUESTION, QuestionSource.FOLLOW_UP)) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "Supplementary study records are private.")
        }
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
    ): QueuedAnswerGrading = enqueueAnswer(userId, recordId, answer, sourceLanguage, aiResponseLanguage, now, null)

    @Transactional
    override suspend fun queueChoice(
        userId: Long, recordId: Long, selectedOptionId: String, aiResponseLanguage: String, now: Instant,
    ): QueuedAnswerGrading = enqueueAnswer(userId, recordId, "", aiResponseLanguage, aiResponseLanguage, now, selectedOptionId)

    private suspend fun enqueueAnswer(
        userId: Long, recordId: Long, answer: String, sourceLanguage: String, aiResponseLanguage: String,
        now: Instant, selectedOptionId: String?,
    ): QueuedAnswerGrading {
        val question = lockGeneratedQuestion(recordId, userId)
        val choice = if (question.questionFormat == QuestionFormat.MULTIPLE_CHOICE) {
            val option = choiceCodec.decode(question.choiceOptionsJson).firstOrNull { it.id == selectedOptionId }
                ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Select a valid option.")
            if (question.selectedOptionId != null) {
                if (question.selectedOptionId == option.id) return QueuedAnswerGrading(question, emptyList())
                throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.ANSWER_ALREADY_SUBMITTED, "The selected option cannot be changed.")
            }
            if (question.status != QuestionStatus.UNGRADED) {
                throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.ANSWER_ALREADY_SUBMITTED, "This question cannot be answered.")
            }
            option
        } else {
            if (selectedOptionId != null) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "This question requires a written answer.")
            null
        }
        val normalizedAnswer = choice?.text ?: answer.trim()
        val persistedAnswer = question.answer?.trim()?.takeIf { it.isNotEmpty() }
        if (question.source == QuestionSource.CUSTOM_QUESTION ||
            question.status != QuestionStatus.UNGRADED || question.skippedAt != null ||
            (persistedAnswer != null && persistedAnswer != normalizedAnswer) ||
            question.gradingRequestId != null ||
            question.gradingStatus != null ||
            question.score != null
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
        if (choice != null) {
            val persistedChoice = choiceResponses.insertOrRead(recordId, userId, choice.id, now)
            if (persistedChoice != choice.id) throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.ANSWER_ALREADY_SUBMITTED, "The selected option cannot be changed.")
            question.selectedOptionId = choice.id
        }
        if (persistedAnswer == null) {
            question.apply(question.toStudyRecord().answer(normalizedAnswer, if (choice != null) question.sourceLanguage.databaseValue else sourceLanguage, now))
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
        if (question.recordType != StudyRecordType.QUESTION) return false
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
        if (question.recordType != StudyRecordType.QUESTION) return CompletedAnswerGrading(false)
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
        val notificationId = redisOutbox.appendNotification(
            NotificationRequestCommand(
                eventId = "answer-graded-${saved.id}-${event.requestId}",
                userId = event.userId,
                deviceId = saved.deviceId,
                type = "STUDY_QUESTION",
                title = QuestionNotificationContentPolicy.gradingCompletedTitle(event.responseLanguage),
                body = QuestionNotificationContentPolicy.gradingCompletedBody(event.responseLanguage),
                threadType = "study_question",
                threadId = saved.id.toString(),
                deepLink = "buddystudy://records/${saved.id}",
                shouldPush = true,
            ),
            now,
        )
        return CompletedAnswerGrading(
            completed = true,
            outboxes = listOf(OutboxReference(OutboxType.DOMAIN_EVENT, notificationId)) +
                translationRequests.appendRecordForSupportedLanguages(saved, now),
        )
    }

    @Transactional
    override suspend fun fail(
        event: AnswerGradingRequestedEvent,
        errorMessage: String,
        now: Instant,
    ) {
        val question = lockRecord(event.recordId, event.userId)
        if (question.recordType != StudyRecordType.QUESTION) return
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

    private suspend fun lockGeneratedQuestion(recordId: Long, userId: Long): QuestionEntity {
        val record = lockRecord(recordId, userId)
        if (record.recordType != StudyRecordType.QUESTION) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Completed voice learning records cannot be answered, skipped, or regraded.",
            )
        }
        return record
    }

    private suspend fun lockRecord(recordId: Long, userId: Long): QuestionEntity =
        questions.lockByIdAndUserIdAndDeletedAtIsNull(recordId, userId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RECORD_NOT_FOUND,
                "Record not found.",
            )

}
