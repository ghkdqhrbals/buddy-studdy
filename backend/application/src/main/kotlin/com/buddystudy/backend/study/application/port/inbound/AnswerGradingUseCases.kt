package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.AnswerGradingProcessResponse
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent

interface ProcessAnswerGradingUseCase {
    suspend fun process(event: AnswerGradingRequestedEvent)
}

interface GetAnswerGradingProcessUseCase {
    suspend fun get(
        principal: Principal,
        correlationId: String,
        afterId: Long,
    ): AnswerGradingProcessResponse
}
