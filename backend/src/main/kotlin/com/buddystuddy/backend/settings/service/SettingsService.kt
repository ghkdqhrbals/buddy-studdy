package com.buddystuddy.backend.settings.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.domain.ScheduleEntity
import com.buddystuddy.backend.dto.BackendSettingsResponse
import com.buddystuddy.backend.dto.ScheduleItemRequest
import com.buddystuddy.backend.dto.ScheduleRequest
import com.buddystuddy.backend.dto.ScheduleResponse
import com.buddystuddy.backend.dto.toSettings
import com.buddystuddy.backend.study.repository.ScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SettingsService(
    private val schedules: ScheduleRepository,
    private val cipher: KeyCipher,
) {
    @Transactional
    fun upsertSchedule(principal: Principal, payload: ScheduleRequest): ScheduleResponse {
        val now = Instant.now()
        val encryptedKey = cipher.encrypt(payload.openaiApiKey)
        val items = payload.schedules?.takeIf { it.isNotEmpty() } ?: listOf(
            ScheduleItemRequest(payload.topic.ifBlank { "SwiftUI" }, payload.difficultyLevel, payload.customPrompt, payload.openaiModel)
        )
        var next: Instant? = null
        items.forEach { item ->
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, item.topic)
                ?: ScheduleEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
            schedule.difficultyLevel = item.difficultyLevel
            schedule.intervalMinutes = payload.intervalMinutes
            schedule.enabled = payload.enabled
            if (encryptedKey != null) schedule.openaiApiKeyCipher = encryptedKey
            schedule.notificationSound = payload.notificationSound
            schedule.customPrompt = item.customPrompt
            schedule.appLanguage = payload.appLanguage
            schedule.openaiModel = item.openaiModel.ifBlank { payload.openaiModel }
            schedule.maxHistoryCount = payload.maxHistoryCount
            schedule.questionPublic = payload.isQuestionPublic && !principal.anonymous
            schedule.nextDueAt = schedule.nextDueAt ?: now.plusSeconds(payload.intervalMinutes.toLong() * 60)
            schedule.updatedAt = now
            next = schedules.save(schedule).nextDueAt
        }
        return ScheduleResponse(principal.deviceId, payload.enabled, next)
    }

    @Transactional(readOnly = true)
    fun settings(principal: Principal): BackendSettingsResponse =
        schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId).toSettings()
}
