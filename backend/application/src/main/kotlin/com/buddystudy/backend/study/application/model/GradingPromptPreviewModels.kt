package com.buddystudy.backend.study.application.model

enum class GradingResponseStyle(
    val id: String,
) {
    COMPACT_SUMMARY("compact-summary-v1"),
    STRUCTURED_BRIEF("structured-brief-v1"),
    ACTION_COACH("action-coach-v1"),
    ;

    companion object {
        fun from(value: String?): GradingResponseStyle =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
                ?: STRUCTURED_BRIEF
    }
}

data class GradingPromptPreviewCommand(
    val question: String,
    val answer: String,
    val topic: String,
    val level: Int,
    val language: String,
)

data class GradingResponsePreview(
    val style: String,
    val configured: Boolean,
    val score: Int,
    val verdict: String,
    val confidence: Double,
    val feedback: String,
    val explanation: String,
)

data class GradingPromptPreviewResponse(
    val configuredStyle: String,
    val variants: List<GradingResponsePreview>,
)
