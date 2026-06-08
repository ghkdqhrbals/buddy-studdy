package com.buddystuddy.backend.admin.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.APIStatusResponse
import com.buddystuddy.backend.dto.APIValidationResponse
import com.buddystuddy.backend.dto.OpenAIModelOptionResponse

interface AdminUseCase {
    fun models(): List<OpenAIModelOptionResponse>
    fun apiStatus(principal: Principal): APIStatusResponse
    fun validateApi(principal: Principal): APIValidationResponse
}
