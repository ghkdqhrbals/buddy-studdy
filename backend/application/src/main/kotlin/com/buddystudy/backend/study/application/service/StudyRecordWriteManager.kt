package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.study.domain.entity.QuestionEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class StudyRecordWriteManager(
    private val questions: QuestionPort,
    private val questionCoverage: QuestionCoveragePort,
) {
    @Transactional
    suspend fun answer(
        userId: Long,
        recordId: Long,
        answer: String,
        grade: GradedAnswer?,
        now: Instant,
    ): QuestionEntity {
        val question = lockRecord(recordId, userId)
        val record = question.toStudyRecord()
        question.apply(record.answer(answer))
        if (grade != null && question.score == null) {
            question.apply(
                record.grade(
                    grade.score,
                    grade.isCorrect,
                    grade.feedback,
                    grade.explanation,
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
        return questions.save(question)
    }

    @Transactional
    suspend fun skip(userId: Long, recordId: Long): QuestionEntity {
        val question = lockRecord(recordId, userId)
        question.apply(question.toStudyRecord().skip())
        return questions.save(question)
    }

    @Transactional
    suspend fun delete(userId: Long, recordId: Long, now: Instant) {
        lockRecord(recordId, userId)
        val deleted = questions.softDelete(recordId, userId, now)
        check(deleted == 1) { "Expected one record to be deleted, but updated $deleted." }
    }

    @Transactional
    suspend fun updatePublicity(userId: Long, recordId: Long, isPublic: Boolean): QuestionEntity {
        val question = lockRecord(recordId, userId)
        question.apply(question.toStudyRecord().restrictPublicity(isPublic))
        return questions.save(question)
    }

    private suspend fun lockRecord(recordId: Long, userId: Long): QuestionEntity =
        questions.lockByIdAndUserIdAndDeletedAtIsNull(recordId, userId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RECORD_NOT_FOUND,
                "Record not found.",
            )

}
