package com.buddystudy.backend.settings.application.port.inbound

data class ScheduleItemCommand(
    val topic: String,
    val difficultyLevel: Int = 5,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
    val questionFormatPreference: com.buddystudy.study.domain.entity.QuestionFormatPreference? = null,
)

data class ScheduleCommand(
    val topic: String = "",
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val openaiApiKey: String? = null,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val appLanguage: String = "ko",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
    val schedules: List<ScheduleItemCommand>? = null,
    val questionFormatPreference: com.buddystudy.study.domain.entity.QuestionFormatPreference? = null,
)
