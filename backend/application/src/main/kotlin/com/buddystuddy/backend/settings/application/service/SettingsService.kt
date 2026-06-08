package com.buddystuddy.backend.settings.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.domain.ScheduleEntity
import com.buddystuddy.backend.settings.application.model.BackendSettingsResponse
import com.buddystuddy.backend.settings.application.model.ScheduleResponse
import com.buddystuddy.backend.settings.application.model.toSettings
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import com.buddystuddy.study.domain.StudyRoomSettings
import com.buddystuddy.study.domain.StudyRoomSettingsCommand
import com.buddystuddy.study.domain.StudyRoomSettingsState
import com.buddystuddy.study.domain.StudyRoomSettingsUpdate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SettingsService(
    private val schedules: SchedulePort,
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
            user.updatedAt = now
            users.save(user)
        }
        var next: Instant? = null
        items.forEach { item ->
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, item.topic)
                ?: ScheduleEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
            schedule.apply(StudyRoomSettings.of(schedule.toStudyRoomSettingsState()).configure(
                StudyRoomSettingsCommand(
                    difficultyLevel = item.difficultyLevel,
                    intervalMinutes = command.intervalMinutes,
                    enabled = command.enabled,
                    notificationSound = command.notificationSound,
                    customPrompt = item.customPrompt,
                    appLanguage = command.appLanguage,
                    openaiModel = item.openaiModel.ifBlank { command.openaiModel },
                    maxHistoryCount = command.maxHistoryCount,
                    questionPublic = command.isQuestionPublic,
                ),
                encryptedOpenAIKey = null,
                anonymous = principal.anonymous,
                now = now,
            ))
            next = schedules.save(schedule).nextDueAt
        }
        return ScheduleResponse(principal.deviceId, command.enabled, next)
    }

    @Transactional(readOnly = true)
    override fun settings(principal: Principal): BackendSettingsResponse {
        val user = users.findById(principal.userId).orElse(null)
        return schedules.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId).toSettings(user)
    }

    private fun ScheduleEntity.toStudyRoomSettingsState() = StudyRoomSettingsState(
        openaiApiKeyCipher = openaiApiKeyCipher,
        nextDueAt = nextDueAt,
    )

    private fun ScheduleEntity.apply(update: StudyRoomSettingsUpdate) {
        difficultyLevel = update.difficultyLevel
        intervalMinutes = update.intervalMinutes
        enabled = update.enabled
        openaiApiKeyCipher = update.openaiApiKeyCipher
        notificationSound = update.notificationSound
        customPrompt = update.customPrompt
        appLanguage = update.appLanguage
        openaiModel = update.openaiModel
        maxHistoryCount = update.maxHistoryCount
        questionPublic = update.questionPublic
        nextDueAt = update.nextDueAt
        updatedAt = update.updatedAt
    }
}
