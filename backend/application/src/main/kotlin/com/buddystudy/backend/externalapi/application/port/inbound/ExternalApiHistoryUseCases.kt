package com.buddystudy.backend.externalapi.application.port.inbound

import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand

interface ExternalApiCallHistoryUseCase {
    suspend fun start(command: StartExternalApiCallCommand)
    suspend fun finish(command: FinishExternalApiCallCommand)
}

interface AdminExternalApiHistoryUseCase {
    suspend fun page(
        cursor: String?,
        limit: Int,
        provider: String?,
        status: String?,
        query: String?,
    ): ExternalApiHistoryPage

    suspend fun detail(id: Long): ExternalApiCallHistory
}
