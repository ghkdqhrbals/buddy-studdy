package com.buddystudy.backend.auth.adapter.outbound.google

import com.buddystudy.backend.test.testExternalApiHistoryRecorder

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

class GoogleIdentityWebClientAdapterTest {
    private var server: DisposableServer? = null

    @AfterEach
    fun tearDown() {
        server?.disposeNow()
    }

    @Test
    fun `token verification does not block a Reactor non-blocking thread`() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route { routes ->
                routes.get("/tokeninfo") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(
                            Mono.just(
                                """{"sub":"provider-id","email":"user@example.com","name":"User Name"}""",
                            ),
                        )
                }
            }
            .bindNow()
        val verifier = GoogleIdentityWebClientAdapter(
            webClientBuilder = WebClient.builder(),
            objectMapper = jacksonObjectMapper(),
            baseUrl = "http://127.0.0.1:${server!!.port()}",
            history = testExternalApiHistoryRecorder(),
        )

        val identity = mono { verifier.verify("id-token") }
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(3))

        assertThat(identity?.providerId).isEqualTo("provider-id")
        assertThat(identity?.email).isEqualTo("user@example.com")
        assertThat(identity?.name).isEqualTo("User Name")
    }
}
