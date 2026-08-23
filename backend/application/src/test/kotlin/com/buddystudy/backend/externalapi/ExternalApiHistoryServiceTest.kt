package com.buddystudy.backend.externalapi

import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryQuery
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.outbound.ExternalApiHistoryPort
import com.buddystudy.backend.externalapi.application.service.ExternalApiHistoryService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExternalApiHistoryServiceTest {
    @Test
    fun `administrator query normalizes filters cursor and bounded page size`() = runBlocking<Unit> {
        val port = RecordingPort()
        val service = ExternalApiHistoryService(port)

        service.page("42", 500, " OpenAI ", " failed ", " translate ")

        assertThat(port.lastQuery).isEqualTo(
            ExternalApiHistoryQuery(42, 100, "openai", "FAILED", "translate"),
        )
    }

    @Test
    fun `missing detail returns resource not found`() {
        val service = ExternalApiHistoryService(RecordingPort())

        assertThatThrownBy { runBlocking { service.detail(99) } }
            .isInstanceOf(ApiException::class.java)
            .hasMessageContaining("External API call history was not found")
    }

    private class RecordingPort : ExternalApiHistoryPort {
        var lastQuery: ExternalApiHistoryQuery? = null
        override suspend fun start(command: StartExternalApiCallCommand) = Unit
        override suspend fun finish(command: FinishExternalApiCallCommand) = true
        override suspend fun page(query: ExternalApiHistoryQuery): ExternalApiHistoryPage {
            lastQuery = query
            return ExternalApiHistoryPage(emptyList(), null, false, query.limit)
        }
        override suspend fun find(id: Long): ExternalApiCallHistory? = null
    }
}
