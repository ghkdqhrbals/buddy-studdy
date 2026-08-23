package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.study.application.model.GradingPromptPreviewCommand
import com.buddystudy.backend.study.application.model.GradingPromptPreviewResponse
import com.buddystudy.backend.study.application.port.inbound.GradingPromptPreviewUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Profile("dev")
@RestController
@RequestMapping("/api/v1/admin/grading-prompts")
class GradingPromptPreviewController(
    private val previews: GradingPromptPreviewWebPort,
) {
    @PostMapping("/preview")
    suspend fun preview(
        @RequestHeader("Authorization") authorization: String?,
        @Valid @RequestBody request: GradingPromptPreviewRequest,
    ): GradingPromptPreviewResponse =
        previews.compare(
            authorization.gradingPreviewBearerToken(),
            GradingPromptPreviewCommand(
                question = request.question,
                answer = request.answer,
                topic = request.topic,
                level = request.level,
                language = request.language,
            ),
        )
}

data class GradingPromptPreviewRequest(
    @field:NotBlank var question: String = "",
    @field:NotBlank var answer: String = "",
    @field:NotBlank var topic: String = "",
    @field:Min(1) @field:Max(10) var level: Int = 1,
    @field:NotBlank var language: String = "ko",
)

interface GradingPromptPreviewWebPort {
    suspend fun compare(
        adminToken: String,
        command: GradingPromptPreviewCommand,
    ): GradingPromptPreviewResponse
}

@Profile("dev")
@Component
class GradingPromptPreviewWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val previews: GradingPromptPreviewUseCase,
) : GradingPromptPreviewWebPort {
    override suspend fun compare(
        adminToken: String,
        command: GradingPromptPreviewCommand,
    ): GradingPromptPreviewResponse {
        authentication.validate(adminToken)
        return previews.compare(command)
    }
}

private fun String?.gradingPreviewBearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
