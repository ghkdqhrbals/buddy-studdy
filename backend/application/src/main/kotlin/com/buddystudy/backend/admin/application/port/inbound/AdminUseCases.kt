package com.buddystudy.backend.admin.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.admin.application.model.APIStatusResponse
import com.buddystudy.backend.admin.application.model.APIValidationResponse
import com.buddystudy.backend.admin.application.model.OpenAIModelOptionResponse

interface AdminUseCase {
    suspend fun models(): List<OpenAIModelOptionResponse>

    /**
     * key 설정 여부
     */
    suspend fun apiStatus(principal: Principal): APIStatusResponse
    suspend fun validateApi(principal: Principal): APIValidationResponse
}
