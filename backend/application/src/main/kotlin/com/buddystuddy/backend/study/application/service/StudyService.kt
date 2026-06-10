package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.study.domain.StudyRoom
import com.buddystuddy.study.domain.StudyRoomPendingLimitExceeded
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudyService(
    private val properties: BuddyStuddyProperties,
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val openAI: OpenAIPort,
    private val users: UserPort,
    private val cipher: KeyCipher,
    private val pushPublisher: QuestionPushPublishPort,
) : StudyUseCase, BrowseRecordsUseCase {
    @Transactional
    override fun createQuestion(principal: Principal, studyId: Long): StudyRecordResponse {
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val appLanguage = appLanguageFor(principal)
        val room = StudyRoom.of(
            study.toStudyRoomSchedule(appLanguage),
            questions.countPendingForStudy(study.id),
        )
        try {
            room.canCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (error: StudyRoomPendingLimitExceeded) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val generated = openAI.generateQuestion(
            apiKeyFor(principal),
            study.openaiModel.ifBlank { properties.openai.model },
            room.topic,
            room.difficultyLevel,
            room.appLanguage,
            room.customPrompt,
            recentQuestions(principal),
        )
        val now = Instant.now()
        val question = questions.save(room.createQuestion(generated.question, generated.hint, source = "manual", now = now).toQuestionEntity())
        questionStats.save(QuestionStatsEntity(questionId = question.id))
        pushPublisher.publishPush(
            QuestionPushRequest(
                recordId = question.id,
                createdAt = now,
                deviceId = study.deviceId,
                userId = principal.userId,
                question = generated.question,
                expectedAnswerHint = generated.hint,
                topic = study.topic,
                difficultyLevel = study.difficultyLevel,
                language = appLanguage,
                sound = study.notificationSound,
                intervalMinutes = study.intervalMinutes,
            )
        )
        return question.toStudyRecord().toProjection().toRecordResponse()
    }

    @Transactional
    override fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val record = q.toStudyRecord(questionStats.findById(q.id).orElse(null))
        q.apply(record.answer(answer))
        if (grade && q.score == null) {
            val study = q.studyId?.let { studies.findByIdAndUserId(it, principal.userId) }
                ?: studies.findByUserIdAndTopic(principal.userId, q.topic)
                ?: studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            val graded = openAI.grade(
                apiKeyFor(principal),
                openAIModelFor(study),
                q.question,
                answer,
                q.topic,
                q.difficultyLevel,
                appLanguageFor(principal),
            )
            q.apply(record.grade(graded.score, graded.isCorrect, graded.feedback, graded.explanation))
        }
        return q.toStudyRecord(questionStats.findById(q.id).orElse(null)).toProjection().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override fun records(principal: Principal, limit: Int, offset: Int, query: String?): RecordsPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            questions.findVisibleByUser(principal.userId, includePending = false, pageable)
        } else {
            questions.findVisibleByUserAndQuery(principal.userId, includePending = false, search, pageable)
        }
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

    private fun apiKeyFor(principal: Principal): String {
        val user = users.findById(principal.userId).orElse(null)
        return cipher.decrypt(user?.openaiApiKeyCipher)
            ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")
    }

    private fun openAIModelFor(study: StudyEntity?): String = study?.openaiModel?.takeIf { it.isNotBlank() } ?: properties.openai.model

    private fun appLanguageFor(principal: Principal): String =
        users.findById(principal.userId).orElse(null)?.appLanguage ?: "ko"

    private fun recentQuestions(principal: Principal): List<String> =
        questions.findVisibleByUser(principal.userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
}
