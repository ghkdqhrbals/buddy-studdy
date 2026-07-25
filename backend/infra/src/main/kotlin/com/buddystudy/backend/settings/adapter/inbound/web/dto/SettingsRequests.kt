package com.buddystudy.backend.settings.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class ScheduleItemRequest @JsonCreator constructor(
    @field:NotBlank @JsonProperty("topic") val topic: String,
    @field:Min(1) @field:Max(10) @JsonProperty("difficultyLevel") val difficultyLevel: Int = 5,
    @JsonProperty("customPrompt") val customPrompt: String = "",
    @JsonProperty("openaiModel") val openaiModel: String = "gpt-5.4",
)

data class ScheduleRequest @JsonCreator constructor(
    @JsonProperty("topic") val topic: String = "",
    @field:Min(1) @field:Max(10) @JsonProperty("difficultyLevel") val difficultyLevel: Int = 5,
    @field:Min(1) @field:Max(1440) @JsonProperty("intervalMinutes") val intervalMinutes: Int = 15,
    @JsonProperty("enabled") val enabled: Boolean = true,
    @JsonProperty("openaiApiKey") val openaiApiKey: String? = null,
    @JsonProperty("notificationSound") val notificationSound: String? = null,
    @JsonProperty("customPrompt") val customPrompt: String = "",
    @JsonProperty("appLanguage") val appLanguage: String = "ko",
    @JsonProperty("openaiModel") val openaiModel: String = "gpt-5.4",
    @field:Min(10) @field:Max(10_000) @JsonProperty("maxHistoryCount") val maxHistoryCount: Int = 100,
    @field:Valid @JsonProperty("schedules") val schedules: List<ScheduleItemRequest>? = null,
)
