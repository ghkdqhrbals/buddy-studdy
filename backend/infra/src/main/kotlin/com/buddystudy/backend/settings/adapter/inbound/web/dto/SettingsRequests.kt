package com.buddystudy.backend.settings.adapter.inbound.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class ScheduleItemRequest(
    @field:NotBlank val topic: String,
    @field:Min(1) @field:Max(10) val difficultyLevel: Int = 5,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
)

data class ScheduleRequest(
    val topic: String = "",
    @field:Min(1) @field:Max(10) val difficultyLevel: Int = 5,
    @field:Min(1) @field:Max(1440) val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val openaiApiKey: String? = null,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val appLanguage: String = "ko",
    val openaiModel: String = "gpt-5.4",
    @field:Min(10) @field:Max(10_000) val maxHistoryCount: Int = 100,
    @field:Valid val schedules: List<ScheduleItemRequest>? = null,
)
