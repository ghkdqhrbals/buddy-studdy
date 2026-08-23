package com.buddystudy.backend.study.application.openai

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class UserContentOpenAIKeyProvider(
    private val properties: BuddyStudyProperties,
) {
    fun requireApiKey(): String =
        properties.openai.userContentApiKey.takeIf { it.isNotBlank() }
            ?: throw missingKey("User-content OpenAI API key is not configured.")
}

@Component
class SystemOpenAIKeyProvider(
    private val properties: BuddyStudyProperties,
) {
    fun requireApiKey(): String =
        properties.openai.systemApiKey.takeIf { it.isNotBlank() }
            ?: throw missingKey("System OpenAI API key is not configured.")
}

private fun missingKey(message: String) =
    ApiException(
        HttpStatus.BAD_REQUEST,
        ApiErrorCode.OPENAI_API_KEY_MISSING,
        message,
    )
