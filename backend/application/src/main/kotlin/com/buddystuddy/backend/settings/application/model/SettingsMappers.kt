package com.buddystuddy.backend.settings.application.model

import com.buddystuddy.domain.ScheduleEntity
import com.buddystuddy.domain.UserEntity

fun ScheduleEntity?.toSettings(user: UserEntity?) = this?.let {
    BackendSettingsResponse(
        topic = it.topic,
        difficultyLevel = it.difficultyLevel,
        intervalMinutes = it.intervalMinutes,
        enabled = it.enabled,
        notificationSound = it.notificationSound,
        customPrompt = it.customPrompt,
        appLanguage = it.appLanguage,
        openaiModel = user?.openaiModel ?: it.openaiModel,
        maxHistoryCount = it.maxHistoryCount,
        isQuestionPublic = it.questionPublic,
        openaiKeyConfigured = !(user?.openaiApiKeyCipher ?: it.openaiApiKeyCipher).isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: BackendSettingsResponse(
    openaiModel = user?.openaiModel ?: BackendSettingsResponse().openaiModel,
    openaiKeyConfigured = !user?.openaiApiKeyCipher.isNullOrBlank(),
)
