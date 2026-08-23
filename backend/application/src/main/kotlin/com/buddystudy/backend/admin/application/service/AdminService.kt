package com.buddystudy.backend.admin.application.service

import com.buddystudy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.admin.application.model.APIStatusResponse
import com.buddystudy.backend.admin.application.model.APIValidationResponse
import com.buddystudy.backend.admin.application.model.OpenAIModelOptionResponse
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
) : AdminUseCase {
    override suspend fun models() = listOf(
        OpenAIModelOptionResponse("gpt-5.4", "GPT-5.4"),
        OpenAIModelOptionResponse("gpt-5.2", "GPT-5.2"),
        OpenAIModelOptionResponse("gpt-4.1", "GPT-4.1", supportsTextVerbosity = false, supportsReasoning = false, defaultReasoningEffort = null),
    )

    @Transactional(readOnly = true)
    override suspend fun apiStatus(principal: Principal): APIStatusResponse {
        val fallbackStudy = studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
        return APIStatusResponse(properties.openai.userContentApiKey.isNotBlank(), fallbackStudy?.openaiModel ?: properties.openai.model)
    }

    @Transactional(readOnly = true)
    override suspend fun validateApi(principal: Principal): APIValidationResponse {
        val status = apiStatus(principal)
        return APIValidationResponse(status.openaiKeyConfigured, status.openaiKeyConfigured, status.openaiModel)
    }
}
