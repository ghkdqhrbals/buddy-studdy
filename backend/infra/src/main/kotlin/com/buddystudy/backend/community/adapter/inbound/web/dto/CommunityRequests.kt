package com.buddystudy.backend.community.adapter.inbound.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ReportQuestionRequest(
    @field:NotBlank var reason: String = "",
    @field:Size(max = 1_000) var message: String = "",
)

data class CommunityCommentRequest(
    @field:NotBlank
    @field:Size(max = 1_000)
    var body: String = "",
    var sourceLanguage: String? = null,
)

data class SubmitFeedbackRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 1_000)
    var content: String = "",
)
