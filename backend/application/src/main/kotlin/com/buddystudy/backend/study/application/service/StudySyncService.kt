package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.service.applyReadyQuestionLocalization
import com.buddystudy.study.domain.StudyRoomSettings
import com.buddystudy.study.domain.StudyRoomSettingsCommand
import com.buddystudy.study.domain.StudyRoomSettingsState
import com.buddystudy.study.domain.StudyRoomSettingsUpdate
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.study.domain.StudyRecord
import com.buddystudy.study.domain.StudyRecordState
import com.buddystudy.study.domain.StudyRecordStats
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudySyncService(
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val contentLocalizations: ContentLocalizationPort,
) : StudySyncUseCase {
    @Transactional(readOnly = true)
    override suspend fun study(principal: Principal, limit: Int, offset: Int, query: String?): StudyPageResponse =
        study(principal, limit, offset, query, QuestionLanguage.KOREAN)

    @Transactional(readOnly = true)
    override suspend fun study(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        language: String,
    ): StudyPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            studies.findByUserId(principal.userId, pageable)
        } else {
            studies.findByUserIdAndQuery(principal.userId, search, pageable)
        }
        return StudyPageResponse(
            studies = page.content.toStudyRoomResponses(QuestionLanguage.normalize(language)),
            totalCount = page.totalElements,
            limit = limit,
            offset = offset,
            serverTime = Instant.now(),
        )
    }

    @Transactional(readOnly = true)
    override suspend fun study(principal: Principal, studyId: Long, language: String): StudyRoomResponse {
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val pendingQuestion = questions
            .findLatestPendingByStudyIdsAndLanguage(listOf(studyId), normalizedLanguage)
            .singleOrNull()
            ?.localizedForDisplay(normalizedLanguage)
        val latestQuestion = questions
            .findLatestCompletedByStudyIdAndUserId(studyId, principal.userId)
            ?.localizedForDisplay(normalizedLanguage)
        val questionIds = listOfNotNull(pendingQuestion?.id, latestQuestion?.id).distinct()
        val statsByQuestionId = questionIds
            .takeIf { it.isNotEmpty() }
            ?.let { questionStats.findAllByIds(it).associateBy { stats -> stats.questionId } }
            .orEmpty()
        return study.toStudyRoomResponse(
            pendingQuestion = pendingQuestion,
            latestQuestion = latestQuestion,
            statsByQuestionId = statsByQuestionId,
        )
    }

    @Transactional
    @RequirePermission(Permissions.STUDY_CREATE)
    override suspend fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse {
        return saveStudy(
            principal = principal,
            command = command,
            parentStudy = null,
            sortOrder = 0,
            activeForQuestions = true,
            scheduleEnabled = command.enabled,
        )
    }

    @Transactional
    @RequirePermission(Permissions.STUDY_CREATE)
    override suspend fun createStudyTopic(
        principal: Principal,
        parentStudyId: Long,
        command: CreateStudyTopicCommand,
    ): StudyRoomResponse {
        val parentStudy = studies.findByIdAndUserId(parentStudyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Parent study not found.")
        val allStudies = studies.findAllByUserId(principal.userId)
        val rootStudy = StudyTreeSelector.rootFor(parentStudy, allStudies)

        return saveStudy(
            principal = principal,
            command = CreateStudyCommand(
                topic = command.topic,
                difficultyLevel = command.difficultyLevel,
                intervalMinutes = rootStudy.intervalMinutes,
                enabled = false,
                notificationSound = rootStudy.notificationSound,
                customPrompt = rootStudy.customPrompt,
                openaiModel = rootStudy.openaiModel,
                maxHistoryCount = rootStudy.maxHistoryCount,
            ),
            parentStudy = parentStudy,
            sortOrder = command.sortOrder,
            activeForQuestions = command.activeForQuestions,
            scheduleEnabled = false,
        )
    }

    private suspend fun saveStudy(
        principal: Principal,
        command: CreateStudyCommand,
        parentStudy: StudyEntity?,
        sortOrder: Int,
        activeForQuestions: Boolean,
        scheduleEnabled: Boolean,
    ): StudyRoomResponse {
        val topic = command.topic.trim()
        if (topic.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Study topic is required.")
        }

        val now = Instant.now()
        val duplicate = studies.findAllByUserId(principal.userId)
            .firstOrNull { it.topic.normalizedStudyTopicKey() == topic.normalizedStudyTopicKey() }
        if (duplicate != null && (parentStudy != null || duplicate.parentStudyId != null)) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A study topic with the same name already exists.")
        }
        val study = duplicate ?: StudyEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                parentStudyId = parentStudy?.id,
                topic = topic,
                createdAt = now,
            )
        val isNewStudy = study.id == 0L
        val previousEnabled = study.enabled
        val previousIntervalMinutes = study.intervalMinutes
        val previousNextDueAt = study.nextDueAt

        study.topic = topic
        study.deviceId = principal.deviceId
        study.parentStudyId = parentStudy?.id
        study.sortOrder = sortOrder.coerceAtLeast(0)
        study.activeForQuestions = activeForQuestions
        study.apply(
            StudyRoomSettings.of(study.toStudyRoomSettingsState()).configure(
                StudyRoomSettingsCommand(
                    difficultyLevel = command.difficultyLevel.coerceIn(1, 10),
                    intervalMinutes = command.intervalMinutes.coerceIn(1, 1440),
                    enabled = scheduleEnabled,
                    notificationSound = command.notificationSound,
                    customPrompt = command.customPrompt,
                    openaiModel = command.openaiModel.ifBlank { study.openaiModel.ifBlank { "gpt-5.4" } },
                    maxHistoryCount = command.maxHistoryCount.coerceIn(10, 10_000),
                ),
                encryptedOpenAIKey = null,
                anonymous = principal.anonymous,
                now = now,
            )
        )
        if (parentStudy != null) {
            study.nextDueAt = null
            study.scheduleClaimedUntil = null
        }

        var saved = studies.save(study)
        if (parentStudy == null && saved.shouldReschedule(
                isNewStudy,
                previousEnabled,
                previousIntervalMinutes,
                previousNextDueAt,
            )
        ) {
            saved.reschedule(now)
            saved = studies.save(saved)
        }
        return saved.toStudyRoomResponse()
    }

    @Transactional
    override suspend fun deleteStudy(principal: Principal, studyId: Long) {
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val now = Instant.now()
        questions.softDeleteByStudySubtree(study.id, principal.userId, now)
        val deleted = studies.deleteByIdAndUserId(studyId, principal.userId)
        if (deleted == 0L) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        }
    }

    private suspend fun List<StudyEntity>.toStudyRoomResponses(language: String): List<StudyRoomResponse> {
        if (isEmpty()) return emptyList()
        val pendingByStudyId = questions
            .findLatestPendingByStudyIdsAndLanguage(map { it.id }, language)
            .associateBy { it.studyId }
        val statsByQuestionId = pendingByStudyId.values
            .map { it.id }
            .takeIf { it.isNotEmpty() }
            ?.let { questionStats.findAllByIds(it).associateBy { stats -> stats.questionId } }
            .orEmpty()
        return map { study ->
            val pendingQuestion = pendingByStudyId[study.id]?.localizedForDisplay(language)
            study.toStudyRoomResponse(
                pendingQuestion = pendingQuestion,
                statsByQuestionId = statsByQuestionId,
            )
        }
    }

    private suspend fun QuestionEntity.localizedForDisplay(language: String): QuestionEntity =
        applyReadyQuestionLocalization(
            contentLocalizations.record(id, language),
            language,
        )

}

internal suspend fun StudyEntity.toStudyRoomResponse(
    pendingQuestion: QuestionEntity? = null,
    latestQuestion: QuestionEntity? = null,
    statsByQuestionId: Map<Long, QuestionStatsEntity> = emptyMap(),
): StudyRoomResponse {
    val pending = pendingQuestion?.let { question ->
        question.toStudyRecord(statsByQuestionId[question.id]).toProjection().toRecordResponse()
    }
    val latest = latestQuestion?.let { question ->
        question.toStudyRecord(statsByQuestionId[question.id]).toProjection().toRecordResponse()
    }

    return StudyRoomResponse(
        id = id,
        parentStudyId = parentStudyId,
        sortOrder = sortOrder,
        topic = topic,
        difficultyLevel = difficultyLevel,
        intervalMinutes = intervalMinutes,
        enabled = enabled,
        activeForQuestions = activeForQuestions,
        notificationSound = notificationSound,
        customPrompt = customPrompt,
        openaiModel = openaiModel,
        maxHistoryCount = maxHistoryCount,
        nextDueAt = nextDueAt,
        lastSentAt = lastSentAt,
        lastError = lastError,
        pendingQuestion = pending,
        latestQuestion = latest,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private suspend fun StudyEntity.toStudyRoomSettingsState() = StudyRoomSettingsState(
    openaiApiKeyCipher = null,
    nextDueAt = nextDueAt,
)

private suspend fun StudyEntity.apply(update: StudyRoomSettingsUpdate) {
    difficultyLevel = update.difficultyLevel
    intervalMinutes = update.intervalMinutes
    enabled = update.enabled
    notificationSound = update.notificationSound
    customPrompt = update.customPrompt
    openaiModel = update.openaiModel
    maxHistoryCount = update.maxHistoryCount
    updatedAt = update.updatedAt
}

private suspend fun StudyEntity.reschedule(now: Instant) {
    nextDueAt = if (enabled && parentStudyId == null) now.plusSeconds(intervalMinutes.toLong() * 60) else null
    updatedAt = now
}

private suspend fun StudyEntity.shouldReschedule(
    isNewStudy: Boolean,
    previousEnabled: Boolean,
    previousIntervalMinutes: Int,
    previousNextDueAt: Instant?,
): Boolean =
    isNewStudy ||
        previousEnabled != enabled ||
        previousIntervalMinutes != intervalMinutes ||
        (enabled && previousNextDueAt == null)

internal fun String.normalizedStudyTopicKey(): String =
    trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
