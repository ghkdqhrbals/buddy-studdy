package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.StudyRecordResponse

interface CreateCustomQuestionUseCase {
    suspend fun create(principal: Principal, studyId: Long, idempotencyKey: String, question: String, answer: String, language: String): StudyRecordResponse
}
