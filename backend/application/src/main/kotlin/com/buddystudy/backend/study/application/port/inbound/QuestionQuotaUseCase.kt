package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.QuestionQuotaResponse

interface QuestionQuotaUseCase {
    suspend fun status(principal: Principal): QuestionQuotaResponse
}
