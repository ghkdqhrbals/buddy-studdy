package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent

interface ProcessQuestionTranslationUseCase {
    suspend fun process(event: QuestionGeneratedEvent)
}
