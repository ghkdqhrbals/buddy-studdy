package com.buddystuddy.backend.settings.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.domain.ScheduleEntity
import com.buddystuddy.backend.settings.application.model.BackendSettingsResponse
import com.buddystuddy.backend.settings.application.model.ScheduleResponse
import com.buddystuddy.backend.settings.application.model.toSettings
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import com.buddystuddy.backend.study.domain.StudyRoomSettings
import com.buddystuddy.backend.study.domain.StudyRoomSettingsCommand
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SettingsService(
    private val schedules: SchedulePort,
    private val cipher: KeyCipher,
) : SettingsUseCase {
    @Transactional
    override fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse {
        val now = Instant.now()
        val encryptedKey = cipher.encrypt(command.openaiApiKey)
        val items = command.schedules?.takeIf { it.isNotEmpty() } ?: listOf(
            ScheduleItemCommand(command.topic.ifBlank { "SwiftUI" }, command.difficultyLevel, command.customPrompt, command.openaiModel)
        )
        var next: Instant? = null
        items.forEach { item ->
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, item.topic)
                ?: ScheduleEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
            StudyRoomSettings.of(schedule).configure(
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
                encryptedOpenAIKey = encryptedKey,
                anonymous = principal.anonymous,
                now = now,
            )
            next = schedules.save(schedule).nextDueAt
        }
        return ScheduleResponse(principal.deviceId, command.enabled, next)
    }

    @Transactional(readOnly = true)
    override fun settings(principal: Principal): BackendSettingsResponse =
        schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId).toSettings()
}
