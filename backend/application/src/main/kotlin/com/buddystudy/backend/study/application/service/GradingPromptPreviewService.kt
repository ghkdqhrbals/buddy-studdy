package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.study.application.model.GradingPromptPreviewCommand
import com.buddystudy.backend.study.application.model.GradingPromptPreviewResponse
import com.buddystudy.backend.study.application.port.inbound.GradingPromptPreviewUseCase
import com.buddystudy.backend.study.application.port.outbound.GradingPromptPreviewPort
import org.springframework.stereotype.Service

@Service
class GradingPromptPreviewService(
    private val previews: GradingPromptPreviewPort,
) : GradingPromptPreviewUseCase {
    override suspend fun compare(command: GradingPromptPreviewCommand): GradingPromptPreviewResponse =
        previews.compare(command)
}
