package com.buddystuddy.backend.admin.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.admin.application.model.APIStatusResponse
import com.buddystuddy.backend.admin.application.model.APIValidationResponse
import com.buddystuddy.backend.admin.application.model.OpenAIModelOptionResponse

interface AdminUseCase {
    fun models(): List<OpenAIModelOptionResponse>

    /**
     * key 설정 여부
     */
    fun apiStatus(principal: Principal): APIStatusResponse
    fun validateApi(principal: Principal): APIValidationResponse
}
