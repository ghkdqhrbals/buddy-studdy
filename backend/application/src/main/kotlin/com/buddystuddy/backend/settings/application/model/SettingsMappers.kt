package com.buddystuddy.backend.settings.application.model

import com.buddystuddy.domain.ScheduleEntity

fun ScheduleEntity?.toSettings() = this?.let {
    BackendSettingsResponse(
        topic = it.topic,
        difficultyLevel = it.difficultyLevel,
        intervalMinutes = it.intervalMinutes,
        enabled = it.enabled,
        notificationSound = it.notificationSound,
        customPrompt = it.customPrompt,
        appLanguage = it.appLanguage,
        openaiModel = it.openaiModel,
        maxHistoryCount = it.maxHistoryCount,
        isQuestionPublic = it.questionPublic,
        openaiKeyConfigured = !it.openaiApiKeyCipher.isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: BackendSettingsResponse()
