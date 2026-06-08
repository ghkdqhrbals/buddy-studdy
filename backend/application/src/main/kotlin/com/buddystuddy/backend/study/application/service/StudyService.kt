package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.domain.QuestionEntity
import com.buddystuddy.domain.QuestionStatsEntity
import com.buddystuddy.domain.ScheduleEntity
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.study.domain.StudyRecord
import com.buddystuddy.study.domain.StudyRecordAnswerUpdate
import com.buddystuddy.study.domain.StudyRecordGradeUpdate
import com.buddystuddy.study.domain.StudyRecordPublicityUpdate
import com.buddystuddy.study.domain.StudyRecordSkipUpdate
import com.buddystuddy.study.domain.StudyRecordState
import com.buddystuddy.study.domain.StudyRecordStats
import com.buddystuddy.study.domain.StudyRoom
import com.buddystuddy.study.domain.StudyRoomQuestionDraft
import com.buddystuddy.study.domain.StudyRoomPendingLimitExceeded
import com.buddystuddy.study.domain.StudyRoomSchedule
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudyService(
    private val properties: BuddyStuddyProperties,
    private val schedules: SchedulePort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val openAI: OpenAIPort,
    private val context: StudyContextProvider,
) : StudyUseCase, BrowseRecordsUseCase {
    @Transactional
    override fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse {
        val schedule = context.scheduleFor(principal, topic)
        val room = StudyRoom.of(
            schedule.toStudyRoomSchedule(),
            questions.countPendingForStudy(principal.deviceId, principal.userId, schedule.topic),
        )
        try {
            room.assertCanCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (error: StudyRoomPendingLimitExceeded) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val generated = openAI.generateQuestion(
            context.apiKeyFor(principal, schedule),
            context.openAIModelFor(principal, schedule),
            room.topic,
            room.difficultyLevel,
            room.appLanguage,
            room.customPrompt,
            context.recentQuestions(principal),
        )
        val now = Instant.now()
        val question = questions.save(room.createQuestion(generated.question, generated.hint, source = "manual", now = now).toQuestionEntity())
        questionStats.save(QuestionStatsEntity(questionId = question.id))
        return question.toStudyRecord().toProjection().toRecordResponse()
    }

    @Transactional
    override fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val record = q.toStudyRecord(questionStats.findById(q.id).orElse(null))
        q.apply(record.answer(answer))
        if (grade && q.score == null) {
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, q.topic)
                ?: schedules.findByUserIdAndTopic(principal.userId, q.topic)
                ?: schedules.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            val graded = openAI.grade(
                context.apiKeyFor(principal, schedule),
                context.openAIModelFor(principal, schedule),
                q.question,
                answer,
                q.topic,
                q.difficultyLevel,
                schedule?.appLanguage ?: "ko",
            )
            q.apply(record.grade(graded.score, graded.isCorrect, graded.feedback, graded.explanation))
        }
        return q.toStudyRecord(questionStats.findById(q.id).orElse(null)).toProjection().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findVisibleByUser(principal.userId, includePending = false, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toStudyRecord(questionStats.findById(it.id).orElse(null)).toProjection().toRecordResponse() }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toStudyRecord(questionStats.findById(it.id).orElse(null)).toProjection().toRecordResponse() }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun record(principal: Principal, id: Long): StudyRecordResponse =
        (questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."))
            .let { it.toStudyRecord(questionStats.findById(id).orElse(null)).toProjection().toRecordResponse() }

    @Transactional
    override fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val record = q.toStudyRecord(questionStats.findById(id).orElse(null))
        q.apply(record.skip())
        return q.toStudyRecord(questionStats.findById(id).orElse(null)).toProjection().toRecordResponse()
    }

    @Transactional
    override fun delete(principal: Principal, id: Long) {
        questions.softDelete(id, principal.userId, Instant.now())
    }

    @Transactional
    override fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val record = q.toStudyRecord(questionStats.findById(id).orElse(null))
        q.apply(record.restrictPublicity(isPublic))
        return q.toStudyRecord(questionStats.findById(id).orElse(null)).toProjection().toRecordResponse()
    }

    private fun ScheduleEntity.toStudyRoomSchedule() = StudyRoomSchedule(
        deviceId = deviceId,
        userId = userId,
        topic = topic,
        difficultyLevel = difficultyLevel,
        openaiModel = openaiModel,
        appLanguage = appLanguage,
        customPrompt = customPrompt,
        questionPublic = questionPublic,
    )

    private fun StudyRoomQuestionDraft.toQuestionEntity() = QuestionEntity(
        deviceId = deviceId,
        userId = userId,
        question = question,
        hint = hint,
        topic = topic,
        difficultyLevel = difficultyLevel,
        scheduledFor = scheduledFor,
        sentAt = sentAt,
        status = status,
        source = source,
        publicQuestion = publicQuestion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun QuestionEntity.toStudyRecord(stats: QuestionStatsEntity? = null) = StudyRecord.of(
        StudyRecordState(
            id = id,
            question = question,
            hint = hint,
            createdAt = createdAt,
            answer = answer,
            score = score,
            correct = correct,
            feedback = feedback,
            explanation = explanation,
            topic = topic,
            difficultyLevel = difficultyLevel,
            answeredAt = answeredAt,
            publicQuestion = publicQuestion,
        ),
        stats?.let { StudyRecordStats(it.likeCount, it.commentCount, it.viewCount) },
    )

    private fun QuestionEntity.apply(update: StudyRecordAnswerUpdate) {
        answer = update.answer
        answeredAt = update.answeredAt
        updatedAt = update.updatedAt
    }

    private fun QuestionEntity.apply(update: StudyRecordGradeUpdate) {
        score = update.score
        correct = update.correct
        feedback = update.feedback
        explanation = update.explanation
        gradedAt = update.gradedAt
        status = update.status
        updatedAt = update.updatedAt
    }

    private fun QuestionEntity.apply(update: StudyRecordSkipUpdate) {
        skippedAt = update.skippedAt
        status = update.status
        updatedAt = update.updatedAt
    }

    private fun QuestionEntity.apply(update: StudyRecordPublicityUpdate) {
        publicQuestion = update.publicQuestion
        updatedAt = update.updatedAt
    }
}
