package com.buddystudy.backend.settings.application.model

import java.time.Instant

data class ScheduleResponse(val deviceId: String, val enabled: Boolean, val nextDueAt: Instant?)

data class StudySettingsResponse(
    val id: Long? = null,
    val topic: String = "",
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = false,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val appLanguage: String = "ko",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
    val openaiKeyConfigured: Boolean = false,
    val nextDueAt: Instant? = null,
    val lastError: String? = null,
)
