package com.buddystuddy.backend.admin.application.service

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.admin.application.model.APIStatusResponse
import com.buddystuddy.backend.admin.application.model.APIValidationResponse
import com.buddystuddy.backend.admin.application.model.OpenAIModelOptionResponse
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminService(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val studies: StudyPort,
) : AdminUseCase {
    override fun models() = listOf(
        OpenAIModelOptionResponse("gpt-5.4", "GPT-5.4"),
        OpenAIModelOptionResponse("gpt-5.2", "GPT-5.2"),
        OpenAIModelOptionResponse("gpt-4.1", "GPT-4.1", supportsTextVerbosity = false, supportsReasoning = false, defaultReasoningEffort = null),
    )

    @Transactional(readOnly = true)
    override fun apiStatus(principal: Principal): APIStatusResponse {
        val user = users.findById(principal.userId).orElse(null)
        val fallbackStudy = studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
        return APIStatusResponse(!user?.openaiApiKeyCipher.isNullOrBlank(), fallbackStudy?.openaiModel ?: properties.openai.model)
    }

    @Transactional(readOnly = true)
    override fun validateApi(principal: Principal): APIValidationResponse {
        val status = apiStatus(principal)
        return APIValidationResponse(status.openaiKeyConfigured, status.openaiKeyConfigured, status.openaiModel)
    }
}
