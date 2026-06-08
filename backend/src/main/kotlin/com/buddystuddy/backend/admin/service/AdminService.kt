package com.buddystuddy.backend.admin.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.dto.APIStatusResponse
import com.buddystuddy.backend.dto.APIValidationResponse
import com.buddystuddy.backend.dto.OpenAIModelOptionResponse
import com.buddystuddy.backend.study.repository.ScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminService(
    private val properties: BuddyStuddyProperties,
    private val schedules: ScheduleRepository,
) {
    fun models() = listOf(
        OpenAIModelOptionResponse("gpt-5.4", "GPT-5.4"),
        OpenAIModelOptionResponse("gpt-5.2", "GPT-5.2"),
        OpenAIModelOptionResponse("gpt-4.1", "GPT-4.1", supportsTextVerbosity = false, supportsReasoning = false, defaultReasoningEffort = null),
    )

    @Transactional(readOnly = true)
    fun apiStatus(principal: Principal): APIStatusResponse {
        val schedule = schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
        return APIStatusResponse(!schedule?.openaiApiKeyCipher.isNullOrBlank(), schedule?.openaiModel ?: properties.openai.model)
    }

    @Transactional(readOnly = true)
    fun validateApi(principal: Principal): APIValidationResponse {
        val status = apiStatus(principal)
        return APIValidationResponse(status.openaiKeyConfigured, status.openaiKeyConfigured, status.openaiModel)
    }
}
