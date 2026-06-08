package com.buddystuddy.study.domain

import com.buddystuddy.domain.ScheduleEntity
import java.time.Instant

class StudyRoomSettings private constructor(
    val schedule: ScheduleEntity,
) {
    fun configure(command: StudyRoomSettingsCommand, encryptedOpenAIKey: String?, anonymous: Boolean, now: Instant = Instant.now()) {
        schedule.difficultyLevel = command.difficultyLevel
        schedule.intervalMinutes = command.intervalMinutes
        schedule.enabled = command.enabled
        if (encryptedOpenAIKey != null) schedule.openaiApiKeyCipher = encryptedOpenAIKey
        schedule.notificationSound = command.notificationSound
        schedule.customPrompt = command.customPrompt
        schedule.appLanguage = command.appLanguage
        schedule.openaiModel = command.openaiModel
        schedule.maxHistoryCount = command.maxHistoryCount
        schedule.questionPublic = command.questionPublic && !anonymous
        schedule.nextDueAt = schedule.nextDueAt ?: now.plusSeconds(command.intervalMinutes.toLong() * 60)
        schedule.updatedAt = now
    }

    companion object {
        fun of(schedule: ScheduleEntity) = StudyRoomSettings(schedule)
    }
}

data class StudyRoomSettingsCommand(
    val difficultyLevel: Int,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val notificationSound: String?,
    val customPrompt: String,
    val appLanguage: String,
    val openaiModel: String,
    val maxHistoryCount: Int,
    val questionPublic: Boolean,
)
