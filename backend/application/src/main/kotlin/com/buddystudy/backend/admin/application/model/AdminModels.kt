package com.buddystudy.backend.admin.application.model

data class APIStatusResponse(
    val openaiKeyConfigured: Boolean,
    val openaiModel: String,
    val usageUrl: String = "https://platform.openai.com/usage",
    val billingUrl: String = "https://platform.openai.com/settings/organization/billing/overview",
    val creditsUrl: String = "https://platform.openai.com/settings/organization/billing/overview",
)

data class APIValidationResponse(val openaiKeyConfigured: Boolean, val isValid: Boolean, val openaiModel: String)

data class OpenAIModelOptionResponse(
    val id: String,
    val displayName: String,
    val supportsTextVerbosity: Boolean = true,
    val supportsReasoning: Boolean = true,
    val defaultReasoningEffort: String? = "none",
)
