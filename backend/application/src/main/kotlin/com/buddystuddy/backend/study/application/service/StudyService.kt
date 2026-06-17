package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.study.domain.StudyRoom
import com.buddystuddy.study.domain.StudyRoomPendingLimitExceeded
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxCommand
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
    private val questionWriter: QuestionCreationWriteManager,
    private val questionSearch: QuestionSearchSyncManager,
) : StudyUseCase, BrowseRecordsUseCase {
    override fun createQuestion(principal: Principal, studyId: Long): StudyRecordResponse = runBlocking {
        createQuestionAsync(principal, studyId)
    }

    private suspend fun createQuestionAsync(principal: Principal, studyId: Long): StudyRecordResponse = coroutineScope {
        val studyDeferred = async(Dispatchers.IO) { studies.findByIdAndUserId(studyId, principal.userId) }
        val userDeferred = async(Dispatchers.IO) { users.findById(principal.userId).orElse(null) }
        val recentQuestionsDeferred = async(Dispatchers.IO) { recentQuestions(principal) }

        val study = studyDeferred.await()
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val user = userDeferred.await()
        val appLanguage = user?.appLanguage ?: "ko"

        val pendingCountDeferred = async(Dispatchers.IO) { questions.countPendingForStudy(study.id) }
        val room = StudyRoom.of(study.toStudyRoomSchedule(appLanguage), pendingCountDeferred.await())
        try {
            room.canCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (error: StudyRoomPendingLimitExceeded) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }

        val generatedQuestionDeferred = async(Dispatchers.IO) {
            openAI.generateQuestion(
                apiKeyFor(user),
                study.openaiModel.ifBlank { properties.openai.model },
                room.topic,
                room.difficultyLevel,
                room.appLanguage,
                room.customPrompt,
                recentQuestionsDeferred.await(),
            )
        }

        val generated = generatedQuestionDeferred.await()
        val now = Instant.now()

        val questionDeferred = async(Dispatchers.IO) {
            questionWriter.saveQuestionWithOutbox(
                room.createQuestion(generated.question, generated.hint, source = "manual", now = now).toQuestionEntity(),
                QuestionPushOutboxCommand(
                    createdAt = now,
                    studyId = study.id,
                    deviceId = study.deviceId,
                    userId = principal.userId,
                    question = generated.question,
                    expectedAnswerHint = generated.hint,
                    topic = study.topic,
                    difficultyLevel = study.difficultyLevel,
                    language = appLanguage,
                    sound = study.notificationSound,
                    intervalMinutes = study.intervalMinutes,
                ),
                now,
            )
        }

        val question = questionDeferred.await()
        question.toStudyRecord().toProjection().toRecordResponse()
    }

    @Transactional
    override fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val record = q.toStudyRecord()
        q.apply(record.answer(answer))
        val user = users.findById(principal.userId).orElse(null)
        if (grade && q.score == null) {
            val study = q.studyId?.let { studies.findByIdAndUserId(it, principal.userId) }
                ?: studies.findByUserIdAndTopic(principal.userId, q.topic)
                ?: studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            val graded = openAI.grade(
                apiKeyFor(user),
                openAIModelFor(study),
                q.question,
                answer,
                q.topic,
                q.difficultyLevel,
                user?.appLanguage ?: "ko",
            )
            q.apply(record.grade(graded.score, graded.isCorrect, graded.feedback, graded.explanation))
        }
        questionSearch.refreshIndexedQuestion(q, user)
        return q.toStudyRecord(questionStats.findById(q.id).orElse(null)).toProjection().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override fun records(principal: Principal, limit: Int, offset: Int, query: String?, language: String): RecordsPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            questions.findVisibleByUser(principal.userId, includePending = false, pageable)
        } else {
            questions.findVisibleByUserAndQuery(principal.userId, includePending = false, search, pageable)
        }
        return RecordsPageResponse(page.content.toRecordResponses(language), page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.toRecordResponses(), page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override fun record(principal: Principal, id: Long, language: String): StudyRecordResponse {
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        if (question.skippedAt != null) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        }
        return question.toStudyRecord(questionStats.findById(id).orElse(null))
            .toProjection()
            .toRecordResponse()
            .withTranslatedText(questionSearch.findIndexedQuestion(question.id, language))
    }

    @Transactional
    override fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.apply(q.toStudyRecord().skip())
        questionSearch.refreshIndexedQuestion(q)
        return q.toStudyRecord(questionStats.findById(id).orElse(null)).toProjection().toRecordResponse()
    }

    @Transactional
    override fun delete(principal: Principal, id: Long) {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val now = Instant.now()
        questions.softDelete(id, principal.userId, now)
        questionSearch.removeIndexedQuestion(id)
    }

    @Transactional
    override fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.apply(q.toStudyRecord().restrictPublicity(isPublic))
        questionSearch.refreshIndexedQuestion(q)
        return q.toStudyRecord(questionStats.findById(id).orElse(null)).toProjection().toRecordResponse()
    }

    private fun apiKeyFor(user: com.buddystuddy.account.domain.entity.UserEntity?): String {
        return cipher.decrypt(user?.openaiApiKeyCipher)
            ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")
    }

    private fun openAIModelFor(study: StudyEntity?): String = study?.openaiModel?.takeIf { it.isNotBlank() } ?: properties.openai.model

    private fun recentQuestions(principal: Principal): List<String> =
        questions.findVisibleByUser(principal.userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }

    private fun List<QuestionEntity>.toRecordResponses(language: String = "ko"): List<StudyRecordResponse> {
        if (isEmpty()) return emptyList()
        val statsByQuestionId = questionStats.findAllByIds(map { it.id }).associateBy { it.questionId }
        val translatedByQuestionId = associate { question ->
            question.id to questionSearch.findIndexedQuestion(question.id, language)
        }
        return map { question ->
            question.toStudyRecord(statsByQuestionId[question.id])
                .toProjection()
                .toRecordResponse()
                .withTranslatedText(translatedByQuestionId[question.id])
        }
    }
}

private fun StudyRecordResponse.withTranslatedText(translated: QuestionSearchEntity?): StudyRecordResponse {
    if (translated == null) return this
    return copy(
        question = question.copy(question = translated.question),
        answer = translated.answer ?: answer,
        gradingResult = gradingResult?.copy(
            feedback = translated.feedback ?: gradingResult.feedback,
            explanation = translated.explanation ?: gradingResult.explanation,
        ),
    )
}
