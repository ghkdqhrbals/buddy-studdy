package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.study.application.model.GradingPromptPreviewCommand
import com.buddystudy.backend.study.application.model.GradingPromptPreviewResponse

interface GradingPromptPreviewUseCase {
    suspend fun compare(command: GradingPromptPreviewCommand): GradingPromptPreviewResponse
}
