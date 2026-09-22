package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview

interface PublicQuestionSharePort {
    /** A side-effect-free, currently public and completed projection, or null. */
    suspend fun findPreview(questionId: Long, language: String): PublicQuestionSharePreview?
}
