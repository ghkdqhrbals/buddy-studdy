package com.buddystudy.backend.externalapi.application.port.outbound

import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryQuery
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand

interface ExternalApiHistoryPort {
    suspend fun start(command: StartExternalApiCallCommand)

    suspend fun finish(command: FinishExternalApiCallCommand): Boolean

    suspend fun page(query: ExternalApiHistoryQuery): ExternalApiHistoryPage

    suspend fun find(id: Long): ExternalApiCallHistory?
}
