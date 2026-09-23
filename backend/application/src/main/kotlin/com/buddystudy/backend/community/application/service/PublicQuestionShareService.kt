package com.buddystudy.backend.community.application.service

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview
import com.buddystudy.backend.community.application.port.inbound.PublicQuestionShareUseCase
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionSharePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PublicQuestionShareService(private val questions: PublicQuestionSharePort) : PublicQuestionShareUseCase {
    @Transactional(readOnly = true)
    override suspend fun preview(questionId: Long, language: String): PublicQuestionSharePreview? {
        if (questionId <= 0) return null
        val normalizedLanguage = language.takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
        return questions.findPreview(questionId, normalizedLanguage)?.let {
            it.copy(topic = it.topic.take(120), question = it.question.take(720))
        }
    }
}
