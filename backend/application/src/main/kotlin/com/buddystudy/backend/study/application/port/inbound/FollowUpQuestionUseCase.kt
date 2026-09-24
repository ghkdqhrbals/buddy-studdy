package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.model.QuestionThreadResponse

interface FollowUpQuestionUseCase {
    suspend fun request(principal: Principal, recordId: Long, idempotencyKey: String): QuestionGenerationAcceptedResponse
    suspend fun thread(principal: Principal, recordId: Long, language: String, view: String): QuestionThreadResponse
}
