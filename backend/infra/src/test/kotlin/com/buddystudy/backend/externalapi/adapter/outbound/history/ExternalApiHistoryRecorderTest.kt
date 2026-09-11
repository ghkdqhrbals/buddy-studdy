package com.buddystudy.backend.externalapi.adapter.outbound.history

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.common.application.privacy.PrivateLearningContentLogScope
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.inbound.ExternalApiCallHistoryUseCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ExternalApiHistoryRecorderTest {
    @Test
    fun `failed history finish cannot turn a paid successful response into retry`() = runBlocking<Unit> {
        val history = RecordingHistoryUseCase().apply { finishFailure = IllegalStateException("database unavailable") }
        var calls = 0
        val result = recorder(history).record(
            ExternalApiRequest("openai", "generate-question", "POST", "https://api.openai.com/v1/chat/completions"),
        ) { calls++; ExternalApiResponse("paid result", body = "private result") }
        assertThat(result).isEqualTo("paid result")
        assertThat(calls).isEqualTo(1)
        assertThat(history.started).hasSize(1)
        assertThat(history.finished.size).isGreaterThan(1)
    }

    @Test
    fun `history failure does not replace the original provider error or cancellation`() = runBlocking<Unit> {
        for (original in listOf(IllegalArgumentException("provider rejected"), CancellationException("cancelled"))) {
            val history = RecordingHistoryUseCase().apply { finishFailure = IllegalStateException("database unavailable") }
            val thrown = runCatching {
                recorder(history).record<String>(
                    ExternalApiRequest("openai", "generate-question", "POST", "https://api.openai.com/v1/chat/completions"),
                ) { throw original }
            }.exceptionOrNull()
            assertThat(thrown).isSameAs(original)
        }
    }

    @Test
    fun `cancellation while finishing history stays cancelled and is never retried`() = runBlocking<Unit> {
        val cancelled = CancellationException("worker stopped")
        val history = RecordingHistoryUseCase().apply { finishFailure = cancelled }
        val thrown = runCatching {
            recorder(history).record(
                ExternalApiRequest("openai", "generate-question", "POST", "https://api.openai.com/v1/chat/completions"),
            ) { ExternalApiResponse("paid result") }
        }.exceptionOrNull()
        assertThat(thrown).isSameAs(cancelled)
        assertThat(history.finished).hasSize(1)
    }
    @Test
    fun `private learning scope bypasses provider history across child dispatchers and restores normal logging`() = runBlocking<Unit> {
        val history = RecordingHistoryUseCase()
        val recorder = recorder(history)
        val request = ExternalApiRequest("libretranslate", "translate-text", "POST", "https://translate.example/translate", body = "private voice answer")
        PrivateLearningContentLogScope.protecting {
            coroutineScope {
                assertThat(async(Dispatchers.Default) {
                    recorder.record(request) { ExternalApiResponse("translated private answer", body = "private translated body") }
                }.await()).isEqualTo("translated private answer")
            }
            withContext(Dispatchers.IO) {
                assertThat(recorder.recordBlocking(request) { ExternalApiResponse("blocking answer", body = "private blocking body") })
                    .isEqualTo("blocking answer")
            }
            assertThat(history.started).isEmpty()
            assertThat(history.finished).isEmpty()
        }
        assertThat(PrivateLearningContentLogScope.isActive()).isFalse()
        recorder.record(request.copy(body = "ordinary public content")) { ExternalApiResponse(Unit, body = "ordinary translation") }
        assertThat(history.started).hasSize(1)
        assertThat(history.finished).hasSize(1)
    }

    @Test
    fun `private provider exceptions never create history and nested scope restores even on failure`() = runBlocking<Unit> {
        val history = RecordingHistoryUseCase()
        val recorder = recorder(history)
        val request = ExternalApiRequest("libretranslate", "translate-text", "POST", "https://translate.example/translate", body = "private request")
        PrivateLearningContentLogScope.protecting {
            val failure = runCatching {
                PrivateLearningContentLogScope.protecting {
                    withContext(Dispatchers.Default) {
                        recorder.record<String>(request) { throw IllegalStateException("private provider response") }
                    }
                }
            }.exceptionOrNull()
            assertThat(failure).hasMessage("private provider response")
            assertThat(PrivateLearningContentLogScope.isActive()).isTrue()
        }
        assertThat(PrivateLearningContentLogScope.isActive()).isFalse()
        assertThat(history.started).isEmpty()
        assertThat(history.finished).isEmpty()
    }

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
        var finishFailure: Exception? = null

        override suspend fun start(command: StartExternalApiCallCommand) {
            started += command
        }

        override suspend fun finish(command: FinishExternalApiCallCommand) {
            finished += command
            finishFailure?.let { throw it }
        }
    }
}
