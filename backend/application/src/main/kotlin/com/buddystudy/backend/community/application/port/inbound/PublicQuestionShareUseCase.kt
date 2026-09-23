package com.buddystudy.backend.community.application.port.inbound

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview

interface PublicQuestionShareUseCase {
    suspend fun preview(questionId: Long, language: String): PublicQuestionSharePreview?
}
