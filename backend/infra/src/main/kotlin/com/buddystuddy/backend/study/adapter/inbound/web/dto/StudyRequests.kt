package com.buddystuddy.backend.study.adapter.inbound.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateStudyRequest(
    @field:NotBlank val topic: String,
    @field:Min(1) @field:Max(10) val difficultyLevel: Int = 5,
    @field:Min(1) @field:Max(1440) val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
    @field:Min(10) @field:Max(10_000) val maxHistoryCount: Int = 100,
    val isQuestionPublic: Boolean = true,
)

data class CreateQuestionRequest(val topic: String? = null)
data class AnswerRequest(val answer: String)
data class RecordPublicityRequest(val isPublic: Boolean)
