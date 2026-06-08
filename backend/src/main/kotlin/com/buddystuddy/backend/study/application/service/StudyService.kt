package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.backend.study.domain.StudyQuestionAggregate
import com.buddystuddy.backend.study.domain.StudyRoomAggregate
import com.buddystuddy.backend.study.domain.StudyRoomPendingLimitExceeded
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
    private val context: StudyContextService,
) : StudyUseCase, BrowseRecordsUseCase {
    @Transactional
    override fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse {
        val schedule = context.scheduleFor(principal, topic)
        val room = StudyRoomAggregate.of(
            schedule,
            questions.countPendingForStudy(principal.deviceId, principal.userId, schedule.topic),
        )
        try {
            room.assertCanCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (error: StudyRoomPendingLimitExceeded) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val generated = openAI.generateQuestion(
            context.apiKeyFor(room.schedule),
            room.openaiModel,
            room.topic,
            room.difficultyLevel,
            room.appLanguage,
            room.customPrompt,
            context.recentQuestions(principal),
        )
        val now = Instant.now()
        val question = questions.save(room.createQuestion(generated.question, generated.hint, source = "manual", now = now))
        questionStats.save(QuestionStatsEntity(questionId = question.id))
        return StudyQuestionAggregate.of(question).snapshot().toRecordResponse()
    }

    @Transactional
    override fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val aggregate = StudyQuestionAggregate.of(q, questionStats.findById(q.id).orElse(null))
        aggregate.answer(answer)
        if (grade && q.score == null) {
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, q.topic)
                ?: schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
            val graded = openAI.grade(
                context.apiKeyFor(schedule),
                schedule?.openaiModel ?: properties.openai.model,
                q.question,
                answer,
                q.topic,
                q.difficultyLevel,
                schedule?.appLanguage ?: "ko",
            )
            aggregate.grade(graded.score, graded.isCorrect, graded.feedback, graded.explanation)
        }
        return aggregate.snapshot().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findVisibleByUser(principal.userId, includePending = false, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { StudyQuestionAggregate.of(it, questionStats.findById(it.id).orElse(null)).snapshot().toRecordResponse() }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { StudyQuestionAggregate.of(it, questionStats.findById(it.id).orElse(null)).snapshot().toRecordResponse() }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun record(principal: Principal, id: Long): StudyRecordResponse =
        (questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."))
            .let { StudyQuestionAggregate.of(it, questionStats.findById(id).orElse(null)).snapshot().toRecordResponse() }

    @Transactional
    override fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val aggregate = StudyQuestionAggregate.of(q, questionStats.findById(id).orElse(null))
        aggregate.skip()
        return aggregate.snapshot().toRecordResponse()
    }

    @Transactional
    override fun delete(principal: Principal, id: Long) {
        questions.softDelete(id, principal.userId, Instant.now())
    }

    @Transactional
    override fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val aggregate = StudyQuestionAggregate.of(q, questionStats.findById(id).orElse(null))
        aggregate.restrictPublicity(isPublic)
        return aggregate.snapshot().toRecordResponse()
    }

}
