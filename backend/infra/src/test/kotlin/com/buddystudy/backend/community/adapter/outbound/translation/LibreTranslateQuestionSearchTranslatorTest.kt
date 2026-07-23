package com.buddystudy.backend.community.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class LibreTranslateQuestionSearchTranslatorTest {
    private var server: DisposableServer? = null
    private val requestBodies = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() {
        server?.disposeNow()
        requestBodies.clear()
    }

    @Test
    fun `translation does not block a Reactor non-blocking thread`() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route { routes ->
                routes.post("/translate") { request, response ->
                    request.receive().aggregate().asString()
                        .doOnNext(requestBodies::add)
                        .then(
                            response.header("Content-Type", "application/json")
                                .sendString(Mono.just("""{"translatedText":"translated"}"""))
                                .then(),
                        )
                }
            }
            .bindNow()
        val properties = BuddyStudyProperties().apply {
            translation.baseUrl = "http://127.0.0.1:${server!!.port()}"
        }
        val translator = LibreTranslateQuestionSearchTranslator(
            properties,
            WebClient.builder(),
            jacksonObjectMapper(),
        )

        val translated = mono {
            translator.translateSearchText(
                sourceLanguage = "ko",
                targetLanguage = "en",
                topic = "주제",
                question = "질문",
                answer = "답변",
                feedback = null,
                explanation = null,
            )
        }
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(3))

        assertThat(translated?.topic).isEqualTo("translated")
        assertThat(translated?.question).isEqualTo("translated")
        assertThat(translated?.answer).isEqualTo("translated")
        assertThat(requestBodies).hasSize(3)
        assertThat(requestBodies).allSatisfy { body ->
            assertThat(body)
                .contains(""""source":"ko"""")
                .contains(""""target":"en"""")
        }
    }
}
