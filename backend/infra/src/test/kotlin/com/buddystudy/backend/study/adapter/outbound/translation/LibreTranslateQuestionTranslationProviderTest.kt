package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class LibreTranslateQuestionTranslationProviderTest {
    @Test
    fun `waits for every parallel field request before reporting provider failure`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/translate") { exchange ->
                requests.incrementAndGet()
                val body = "{\"error\":\"invalid request\"}".toByteArray()
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val properties = BuddyStudyProperties().apply {
                translation.baseUrl = "http://127.0.0.1:${server.address.port}"
                translation.timeoutMs = 2_000
            }
            val provider = LibreTranslateQuestionTranslationProvider(WebClient.builder(), properties)

            val failure = runCatching {
                runBlocking {
                    provider.translate(
                        QuestionTranslationRequest(
                            topic = "주제",
                            question = "질문입니다.",
                            hint = "힌트입니다.",
                            sourceLanguage = "ko",
                            targetLanguage = "en",
                        ),
                    )
                }
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(requests).hasValue(3)
        } finally {
            server.stop(0)
        }
    }
}
