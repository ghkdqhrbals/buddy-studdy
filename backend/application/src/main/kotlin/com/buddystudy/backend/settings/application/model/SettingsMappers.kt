package com.buddystudy.backend.settings.application.model

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.study.domain.entity.StudyEntity

fun StudyEntity?.toSettings(user: UserEntity?) = this?.let {
    StudySettingsResponse(
        id = it.id,
        topic = it.topic,
        difficultyLevel = it.difficultyLevel,
        intervalMinutes = it.intervalMinutes,
        enabled = it.enabled,
        notificationSound = it.notificationSound,
        customPrompt = it.customPrompt,
        appLanguage = user?.appLanguage?.databaseValue ?: "ko",
        openaiModel = it.openaiModel,
        maxHistoryCount = it.maxHistoryCount,
        openaiKeyConfigured = !user?.openaiApiKeyCipher.isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: StudySettingsResponse(
    appLanguage = user?.appLanguage?.databaseValue ?: "ko",
    openaiKeyConfigured = !user?.openaiApiKeyCipher.isNullOrBlank(),
)
