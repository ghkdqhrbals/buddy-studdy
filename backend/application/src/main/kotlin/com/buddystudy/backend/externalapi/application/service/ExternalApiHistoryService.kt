package com.buddystudy.backend.externalapi.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryQuery
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.inbound.AdminExternalApiHistoryUseCase
import com.buddystudy.backend.externalapi.application.port.inbound.ExternalApiCallHistoryUseCase
import com.buddystudy.backend.externalapi.application.port.outbound.ExternalApiHistoryPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ExternalApiHistoryService(
    private val history: ExternalApiHistoryPort,
) : ExternalApiCallHistoryUseCase, AdminExternalApiHistoryUseCase {
    override suspend fun start(command: StartExternalApiCallCommand) = history.start(command)

    override suspend fun finish(command: FinishExternalApiCallCommand) {
        check(history.finish(command)) { "External API history call was not found: ${command.callId}" }
    }

    override suspend fun page(
        cursor: String?,
        limit: Int,
        provider: String?,
        status: String?,
        query: String?,
    ): ExternalApiHistoryPage = history.page(
        ExternalApiHistoryQuery(
            cursor = cursor?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            limit = limit.coerceIn(1, 100),
            provider = provider.normalized()?.lowercase(),
            status = status.normalized()?.uppercase(),
            query = query.normalized(),
        ),
    )

    override suspend fun detail(id: Long): ExternalApiCallHistory = history.find(id)
        ?: throw ApiException(
            HttpStatus.NOT_FOUND,
            ApiErrorCode.RESOURCE_NOT_FOUND,
            "External API call history was not found.",
        )

    private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
