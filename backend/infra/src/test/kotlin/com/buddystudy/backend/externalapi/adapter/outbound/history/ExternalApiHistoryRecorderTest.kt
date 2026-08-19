package com.buddystudy.backend.externalapi.adapter.outbound.history

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.inbound.ExternalApiCallHistoryUseCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ExternalApiHistoryRecorderTest {
    @Test
    fun `stores full bodies while redacting credentials before persistence`() = runBlocking<Unit> {
        val useCase = RecordingHistoryUseCase()
        val recorder = recorder(useCase)

        val value = recorder.record(
            ExternalApiRequest(
                provider = "LibreTranslate",
                operation = "translate-text",
                method = "post",
                url = "https://translate.example/translate?id_token=raw-token",
                headers = mapOf("Authorization" to "Bearer secret", "X-Trace" to "trace-1"),
                body = """{"q":"hello","api_key":"secret-key"}""",
            ),
        ) {
            ExternalApiResponse(
                value = "안녕하세요",
                statusCode = 200,
                headers = mapOf("X-Request-ID" to "provider-1"),
                body = """{"translatedText":"안녕하세요","access_token":"secret"}""",
            )
        }

        assertThat(value).isEqualTo("안녕하세요")
        assertThat(useCase.started.single().provider).isEqualTo("libretranslate")
        assertThat(useCase.started.single().requestUrl).contains("id_token=[REDACTED]")
        assertThat(useCase.started.single().requestHeadersJson).contains("[REDACTED]").doesNotContain("Bearer secret")
        assertThat(useCase.started.single().requestBody).contains("\"q\":\"hello\"").contains("[REDACTED]")
        assertThat(useCase.finished.single().status).isEqualTo("SUCCEEDED")
        assertThat(useCase.finished.single().responseBody).contains("안녕하세요").contains("[REDACTED]")
    }

    @Test
    fun `records failed calls before rethrowing the provider exception`() {
        val useCase = RecordingHistoryUseCase()
        val recorder = recorder(useCase)

        assertThatThrownBy {
            runBlocking {
                recorder.record(
                    ExternalApiRequest("openai", "generate-question", "POST", "https://api.openai.com/v1/chat/completions"),
                ) { throw IllegalStateException("provider unavailable") }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("provider unavailable")
        assertThat(useCase.started).hasSize(1)
        assertThat(useCase.finished.single().status).isEqualTo("FAILED")
        assertThat(useCase.finished.single().errorType).isEqualTo(IllegalStateException::class.java.name)
        assertThat(useCase.finished.single().errorMessage).isEqualTo("provider unavailable")
    }

    @Test
    fun `blocking provider calls retain the inbound request correlation id`() {
        val useCase = RecordingHistoryUseCase()
        val recorder = recorder(useCase)
        MDC.put("requestId", "request-from-api-log")
        try {
            recorder.recordBlocking(
                ExternalApiRequest("openai", "validate-api-key", "POST", "https://api.openai.com/v1/chat/completions"),
            ) { ExternalApiResponse(Unit, body = "{}") }
        } finally {
            MDC.remove("requestId")
        }

        assertThat(useCase.started.single().correlationId).isEqualTo("request-from-api-log")
    }

    private fun recorder(useCase: ExternalApiCallHistoryUseCase): ExternalApiHistoryRecorder {
        val mapper = jacksonObjectMapper()
        return ExternalApiHistoryRecorder(useCase, SensitiveDataRedactor(mapper), mapper)
    }

    private class RecordingHistoryUseCase : ExternalApiCallHistoryUseCase {
        val started = mutableListOf<StartExternalApiCallCommand>()
        val finished = mutableListOf<FinishExternalApiCallCommand>()

        override suspend fun start(command: StartExternalApiCallCommand) {
            started += command
        }

        override suspend fun finish(command: FinishExternalApiCallCommand) {
            finished += command
        }
    }
}
