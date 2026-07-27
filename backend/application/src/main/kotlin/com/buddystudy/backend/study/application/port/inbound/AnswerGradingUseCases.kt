package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.AnswerGradingProgressResponse
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import kotlinx.coroutines.flow.Flow

interface ProcessAnswerGradingUseCase {
    suspend fun process(event: AnswerGradingRequestedEvent)
}

interface ObserveAnswerGradingUseCase {
    suspend fun observe(
        principal: Principal,
        recordId: Long,
        afterId: Long,
    ): Flow<AnswerGradingProgressResponse>
}
