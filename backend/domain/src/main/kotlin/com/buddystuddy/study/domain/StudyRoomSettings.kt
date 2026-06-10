package com.buddystuddy.study.domain

import java.time.Instant

class StudyRoomSettings private constructor(
    private val schedule: StudyRoomSettingsState,
) {
    fun configure(command: StudyRoomSettingsCommand, encryptedOpenAIKey: String?, anonymous: Boolean, now: Instant = Instant.now()) =
        StudyRoomSettingsUpdate(
            difficultyLevel = command.difficultyLevel,
            intervalMinutes = command.intervalMinutes,
            enabled = command.enabled,
            openaiApiKeyCipher = encryptedOpenAIKey ?: schedule.openaiApiKeyCipher,
            notificationSound = command.notificationSound,
            customPrompt = command.customPrompt,
            openaiModel = command.openaiModel,
            maxHistoryCount = command.maxHistoryCount,
            nextDueAt = schedule.nextDueAt ?: now.plusSeconds(command.intervalMinutes.toLong() * 60),
            updatedAt = now,
        )

    companion object {
        fun of(schedule: StudyRoomSettingsState) = StudyRoomSettings(schedule)
    }
}

data class StudyRoomSettingsState(
    val openaiApiKeyCipher: String?,
    val nextDueAt: Instant?,
)

data class StudyRoomSettingsUpdate(
    val difficultyLevel: Int,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val openaiApiKeyCipher: String?,
    val notificationSound: String?,
    val customPrompt: String,
    val openaiModel: String,
    val maxHistoryCount: Int,
    val nextDueAt: Instant,
    val updatedAt: Instant,
)

data class StudyRoomSettingsCommand(
    val difficultyLevel: Int,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val notificationSound: String?,
    val customPrompt: String,
    val openaiModel: String,
    val maxHistoryCount: Int,
)
