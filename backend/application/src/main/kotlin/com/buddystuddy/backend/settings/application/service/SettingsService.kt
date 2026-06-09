package com.buddystuddy.backend.settings.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.backend.settings.application.model.ScheduleResponse
import com.buddystuddy.backend.settings.application.model.StudySettingsResponse
import com.buddystuddy.backend.settings.application.model.toSettings
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.study.domain.StudyRoomSettings
import com.buddystuddy.study.domain.StudyRoomSettingsCommand
import com.buddystuddy.study.domain.StudyRoomSettingsState
import com.buddystuddy.study.domain.StudyRoomSettingsUpdate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SettingsService(
    private val studies: StudyPort,
    private val users: UserPort,
    private val cipher: KeyCipher,
) : SettingsUseCase {
    @Transactional
    override fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse {
        val now = Instant.now()
        val encryptedKey = cipher.encrypt(command.openaiApiKey)
        val items = command.schedules?.takeIf { it.isNotEmpty() } ?: listOf(
            ScheduleItemCommand(command.topic.ifBlank { "SwiftUI" }, command.difficultyLevel, command.customPrompt, command.openaiModel)
        )
        users.findById(principal.userId).orElse(null)?.let { user ->
            if (encryptedKey != null) {
                user.openaiApiKeyCipher = encryptedKey
            }
            user.appLanguage = command.appLanguage.ifBlank { user.appLanguage }
            user.updatedAt = now
            users.save(user)
        }
        var next: Instant? = null
        items.forEach { item ->
            val study = studies.findByUserIdAndTopic(principal.userId, item.topic)
                ?: StudyEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
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
                    questionPublic = command.isQuestionPublic,
                ),
                encryptedOpenAIKey = null,
                anonymous = principal.anonymous,
                now = now,
            ))
            next = studies.save(study).nextDueAt
        }
        return ScheduleResponse(principal.deviceId, command.enabled, next)
    }

    @Transactional(readOnly = true)
    override fun settings(principal: Principal): StudySettingsResponse {
        val user = users.findById(principal.userId).orElse(null)
        return studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId).toSettings(user)
    }

    @Transactional(readOnly = true)
    override fun studySettings(principal: Principal, studyId: Long): StudySettingsResponse {
        val user = users.findById(principal.userId).orElse(null)
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")
        return study.toSettings(user)
    }

    @Transactional
    override fun upsertStudySettings(principal: Principal, studyId: Long, command: ScheduleCommand): ScheduleResponse {
        val now = Instant.now()
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")
        val encryptedKey = cipher.encrypt(command.openaiApiKey)
        users.findById(principal.userId).orElse(null)?.let { user ->
            if (encryptedKey != null) {
                user.openaiApiKeyCipher = encryptedKey
            }
            user.appLanguage = command.appLanguage.ifBlank { user.appLanguage }
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
                questionPublic = command.isQuestionPublic,
            ),
            encryptedOpenAIKey = null,
            anonymous = principal.anonymous,
            now = now,
        ))
        study.topic = command.topic.ifBlank { study.topic }
        study.deviceId = principal.deviceId
        val saved = studies.save(study)
        return ScheduleResponse(principal.deviceId, saved.enabled, saved.nextDueAt)
    }

    private fun StudyEntity.toStudyRoomSettingsState() = StudyRoomSettingsState(
        openaiApiKeyCipher = null,
        nextDueAt = nextDueAt,
    )

    private fun StudyEntity.apply(update: StudyRoomSettingsUpdate) {
        difficultyLevel = update.difficultyLevel
        intervalMinutes = update.intervalMinutes
        enabled = update.enabled
        notificationSound = update.notificationSound
        customPrompt = update.customPrompt
        openaiModel = update.openaiModel
        maxHistoryCount = update.maxHistoryCount
        questionPublic = update.questionPublic
        nextDueAt = update.nextDueAt
        updatedAt = update.updatedAt
    }
}
