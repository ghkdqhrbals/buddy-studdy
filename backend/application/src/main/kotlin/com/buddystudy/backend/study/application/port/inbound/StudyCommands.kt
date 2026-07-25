package com.buddystudy.backend.study.application.port.inbound

data class CreateStudyCommand(
    val topic: String,
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
)

data class CreateStudyTopicCommand(
    val topic: String,
    val sortOrder: Int = 0,
    val difficultyLevel: Int = 5,
    val activeForQuestions: Boolean = true,
)

data class UpdateStudyTopicActivationCommand(
    val active: Boolean,
)
