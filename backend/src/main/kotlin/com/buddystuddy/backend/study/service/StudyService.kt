package com.buddystuddy.backend.study.service

import com.buddystuddy.backend.admin.service.AdminService
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.api.ApiErrorCode
import com.buddystuddy.backend.common.api.ApiException
import com.buddystuddy.backend.common.service.BackendSupportService
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.dto.BackendSnapshotResponse
import com.buddystuddy.backend.dto.RecordsPageResponse
import com.buddystuddy.backend.dto.StudyRecordResponse
import com.buddystuddy.backend.dto.toRecord
import com.buddystuddy.backend.openai.OpenAIClient
import com.buddystuddy.backend.settings.service.SettingsService
import com.buddystuddy.backend.stats.StatsService
import com.buddystuddy.backend.study.repository.QuestionRepository
import com.buddystuddy.backend.study.repository.QuestionStatsRepository
import com.buddystuddy.backend.study.repository.ScheduleRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudyService(
    private val properties: BuddyStuddyProperties,
    private val schedules: ScheduleRepository,
    private val questions: QuestionRepository,
    private val questionStats: QuestionStatsRepository,
    private val openAI: OpenAIClient,
    private val statsService: StatsService,
    private val settingsService: SettingsService,
    private val adminService: AdminService,
    private val support: BackendSupportService,
) {
    @Transactional
    fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse {
        val schedule = support.scheduleFor(principal, topic)
        if (questions.countPendingForStudy(principal.deviceId, principal.userId, schedule.topic) >= properties.scheduler.maxPendingPerStudy) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val generated = openAI.generateQuestion(
            support.apiKeyFor(schedule),
            schedule.openaiModel,
            schedule.topic,
            schedule.difficultyLevel,
            schedule.appLanguage,
            schedule.customPrompt,
            support.recentQuestions(principal),
        )
        val now = Instant.now()
        val question = questions.save(
            QuestionEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                question = generated.question,
                hint = generated.hint,
                topic = schedule.topic,
                difficultyLevel = schedule.difficultyLevel,
                scheduledFor = now,
                sentAt = now,
                status = "ungraded",
                source = "manual",
                publicQuestion = schedule.questionPublic,
                createdAt = now,
                updatedAt = now,
            )
        )
        questionStats.save(QuestionStatsEntity(questionId = question.id))
        return question.toRecord()
    }

    @Transactional
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.answer = answer
        q.answeredAt = Instant.now()
        if (grade && q.score == null) {
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, q.topic)
                ?: schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
            val graded = openAI.grade(
                support.apiKeyFor(schedule),
                schedule?.openaiModel ?: properties.openai.model,
                q.question,
                answer,
                q.topic,
                q.difficultyLevel,
                schedule?.appLanguage ?: "ko",
            )
            q.score = graded.score
            q.correct = graded.isCorrect
            q.feedback = graded.feedback
            q.explanation = graded.explanation
            q.gradedAt = Instant.now()
            q.status = "graded"
        }
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(q.id).orElse(null))
    }

    @Transactional(readOnly = true)
    fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findVisibleByUser(principal.userId, includePending = false, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toRecord(questionStats.findById(it.id).orElse(null)) }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toRecord(questionStats.findById(it.id).orElse(null)) }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    fun record(principal: Principal, id: Long): StudyRecordResponse =
        (questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."))
            .toRecord(questionStats.findById(id).orElse(null))

    @Transactional
    fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.skippedAt = Instant.now()
        q.status = "skipped"
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(id).orElse(null))
    }

    @Transactional
    fun delete(principal: Principal, id: Long) {
        questions.softDelete(id, principal.userId, Instant.now())
    }

    @Transactional
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.publicQuestion = isPublic && q.score != null
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(id).orElse(null))
    }

    @Transactional(readOnly = true)
    fun stats(principal: Principal, limit: Int, offset: Int) = statsService.stats(principal.userId, limit, offset)

    @Transactional(readOnly = true)
    fun snapshot(principal: Principal, limit: Int, offset: Int): BackendSnapshotResponse {
        val records = records(principal, limit, offset)
        return BackendSnapshotResponse(
            settingsService.settings(principal),
            adminService.apiStatus(principal),
            records.records,
            stats(principal, 8, 0),
            records.totalCount,
            Instant.now(),
        )
    }
}
