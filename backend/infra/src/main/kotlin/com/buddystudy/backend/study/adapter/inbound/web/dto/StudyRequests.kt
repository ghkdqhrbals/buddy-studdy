package com.buddystudy.backend.study.adapter.inbound.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateStudyRequest(
    @field:NotBlank var topic: String = "",
    @field:Min(1) @field:Max(10) var difficultyLevel: Int = 5,
    @field:Min(1) @field:Max(1440) var intervalMinutes: Int = 15,
    var enabled: Boolean = true,
    var notificationSound: String? = null,
    var customPrompt: String? = null,
    var openaiModel: String = "gpt-5.4",
    @field:Min(10) @field:Max(10_000) var maxHistoryCount: Int = 100,
)

data class CreateStudyTopicRequest(
    @field:NotBlank var topic: String = "",
    @field:Min(0) var sortOrder: Int = 0,
    @field:Min(1) @field:Max(10) var difficultyLevel: Int = 5,
    var activeForQuestions: Boolean = true,
)

data class AnswerRequest(
    var answer: String = "",
    var sourceLanguage: String? = null,
)
data class RecordPublicityRequest(var isPublic: Boolean = true)
data class StudyTopicActivationRequest(var active: Boolean = true)
