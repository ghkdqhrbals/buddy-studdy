package com.buddystuddy.backend.settings.application.model

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.study.domain.entity.StudyEntity

fun StudyEntity?.toSettings(user: UserEntity?) = this?.let {
    StudySettingsResponse(
        id = it.id,
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
        openaiKeyConfigured = !user?.openaiApiKeyCipher.isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: StudySettingsResponse(openaiKeyConfigured = !user?.openaiApiKeyCipher.isNullOrBlank())
