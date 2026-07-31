package com.buddystudy.backend.settings.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.backend.settings.application.model.ScheduleResponse
import com.buddystudy.backend.settings.application.model.StudySettingsResponse
import com.buddystudy.backend.settings.application.model.toSettings
import com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystudy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystudy.backend.settings.application.port.inbound.SettingsUseCase
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.StudyTreeSelector
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.study.domain.StudyRoomSettings
import com.buddystudy.study.domain.StudyRoomSettingsCommand
import com.buddystudy.study.domain.StudyRoomSettingsState
import com.buddystudy.study.domain.StudyRoomSettingsUpdate
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.common.domain.SupportedLanguage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SettingsService(
    private val studies: StudyPort,
    private val users: UserPort,
    private val cipher: KeyCipher,
    private val properties: BuddyStudyProperties,
) : SettingsUseCase {
    @Transactional
    override suspend fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse {
        val now = Instant.now()
        val encryptedKey = cipher.encrypt(command.openaiApiKey)
        val items = command.schedules
            ?.takeIf { it.isNotEmpty() }
            ?: command.topic.trim().takeIf { it.isNotEmpty() }?.let { topic ->
                listOf(ScheduleItemCommand(topic, command.difficultyLevel, command.customPrompt, command.openaiModel))
            }.orEmpty()
        users.findById(principal.userId)?.let { user ->
            if (encryptedKey != null) {
                user.openaiApiKeyCipher = encryptedKey
            }
            user.appLanguage = SupportedLanguage.fromLocale(
                QuestionLanguage.normalize(command.appLanguage.ifBlank { user.appLanguage.databaseValue }),
            )
            user.updatedAt = now
            users.save(user)
        }
        var next: Instant? = null
        val allUserStudies = studies.findAllByUserId(principal.userId).toMutableList()
        val studiesByTopic = studies.findByUserIdAndTopics(principal.userId, items.map { it.topic }.distinct())
            .associateBy { it.topic }
            .toMutableMap()
        items.forEach { item ->
            val study = studiesByTopic.getOrPut(item.topic) {
                StudyEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
            }
            val isNewStudy = study.id == 0L
            val previousEnabled = study.enabled
            val previousIntervalMinutes = study.intervalMinutes
            val previousNextDueAt = study.nextDueAt
            study.deviceId = principal.deviceId
            study.apply(StudyRoomSettings.of(study.toStudyRoomSettingsState()).configure(
                StudyRoomSettingsCommand(
                    difficultyLevel = item.difficultyLevel,
                    intervalMinutes = command.intervalMinutes,
                    enabled = command.enabled,
                    notificationSound = command.notificationSound,
                    customPrompt = item.customPrompt,
                    openaiModel = item.openaiModel.ifBlank { command.openaiModel },
                    maxHistoryCount = command.maxHistoryCount,
                ),
                encryptedOpenAIKey = null,
                anonymous = principal.anonymous,
                now = now,
            ))
            if (study.shouldReschedule(isNewStudy, previousEnabled, previousIntervalMinutes, previousNextDueAt)) {
                study.reschedule(now)
            }
            if (command.enabled && StudyTreeSelector.nextActiveTopic(study, allUserStudies + study) == null) {
                study.activeForQuestions = true
                study.lastError = null
                study.updatedAt = now
            }
            val saved = studies.save(study)
            studiesByTopic[item.topic] = saved
            if (allUserStudies.none { it.id == saved.id }) {
                allUserStudies += saved
            }
            next = saved.nextDueAt
        }
        return ScheduleResponse(principal.deviceId, command.enabled, next)
    }

    @Transactional(readOnly = true)
    override suspend fun settings(principal: Principal): StudySettingsResponse {
        val user = users.findById(principal.userId)
        return studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            .toSettings(user)
            .copy(openaiKeyConfigured = properties.openai.userContentApiKey.isNotBlank())
    }

    @Transactional(readOnly = true)
    override suspend fun studySettings(principal: Principal, studyId: Long): StudySettingsResponse {
        val user = users.findById(principal.userId)
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")
        return study.toSettings(user)
            .copy(openaiKeyConfigured = properties.openai.userContentApiKey.isNotBlank())
    }

    @Transactional
    override suspend fun upsertStudySettings(principal: Principal, studyId: Long, command: ScheduleCommand): ScheduleResponse {
        val now = Instant.now()
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")
        val previousEnabled = study.enabled
        val previousIntervalMinutes = study.intervalMinutes
        val previousNextDueAt = study.nextDueAt
        val encryptedKey = cipher.encrypt(command.openaiApiKey)
        users.findById(principal.userId)?.let { user ->
            if (encryptedKey != null) {
                user.openaiApiKeyCipher = encryptedKey
            }
            user.appLanguage = SupportedLanguage.fromLocale(
                QuestionLanguage.normalize(command.appLanguage.ifBlank { user.appLanguage.databaseValue }),
            )
            user.updatedAt = now
            users.save(user)
        }

        study.apply(StudyRoomSettings.of(study.toStudyRoomSettingsState()).configure(
            StudyRoomSettingsCommand(
                difficultyLevel = command.difficultyLevel,
                intervalMinutes = command.intervalMinutes,
                enabled = command.enabled,
                notificationSound = command.notificationSound,
                customPrompt = command.customPrompt,
                openaiModel = command.openaiModel.ifBlank { study.openaiModel },
                maxHistoryCount = command.maxHistoryCount,
            ),
            encryptedOpenAIKey = null,
            anonymous = principal.anonymous,
            now = now,
        ))
        study.topic = command.topic.ifBlank { study.topic }
        study.deviceId = principal.deviceId
        if (study.shouldReschedule(false, previousEnabled, previousIntervalMinutes, previousNextDueAt)) {
            study.reschedule(now)
        }
        if (study.parentStudyId == null && command.enabled) {
            val allUserStudies = studies.findAllByUserId(principal.userId)
            if (StudyTreeSelector.nextActiveTopic(study, allUserStudies) == null) {
                study.activeForQuestions = true
                study.lastError = null
                study.updatedAt = now
            }
        }
        val saved = studies.save(study)
        return ScheduleResponse(principal.deviceId, saved.enabled, saved.nextDueAt)
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
}
