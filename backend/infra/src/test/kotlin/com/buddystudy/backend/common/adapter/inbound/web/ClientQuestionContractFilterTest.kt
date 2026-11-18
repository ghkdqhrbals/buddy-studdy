package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.application.port.outbound.withQuestionApiVersion
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class ClientQuestionContractFilterTest {
    private val contract = ReactiveClientQuestionContractAdapter()
    private val client = WebTestClient.bindToController(ContractController(contract))
        .webFilter<WebTestClient.ControllerSpec>(ClientQuestionContractFilter()).build()

    @Test
    fun `missing or unknown version keeps legacy representation and varies without replacing other keys`() {
        client.get().uri("/api/question-contract").exchange().expectStatus().isOk
            .expectHeader().valueEquals(QUESTION_API_VERSION_HEADER, "1")
            .expectHeader().valueEquals(HttpHeaders.VARY, "Accept-Language, $QUESTION_API_VERSION_HEADER")
            .expectBody().jsonPath("$.version").isEqualTo(1)
        client.get().uri("/api/question-contract").header(QUESTION_API_VERSION_HEADER, "unknown")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    @Test
    fun `explicit version three propagates into suspend execution and does not leak into following request`() {
        client.get().uri("/api/question-contract").header(QUESTION_API_VERSION_HEADER, "3")
            .exchange().expectStatus().isOk.expectHeader().valueEquals(QUESTION_API_VERSION_HEADER, "3")
            .expectBody().jsonPath("$.version").isEqualTo(3)
        client.get().uri("/api/question-contract?star=true").exchange().expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.VARY, "*")
            .expectBody().jsonPath("$.version").isEqualTo(1)
    }

    @Test
    fun `non HTTP adapters must opt into version three explicitly and context is restored`(): Unit = runBlocking {
        assertThat(contract.currentVersion()).isEqualTo(1)
        withQuestionApiVersion(3) { assertThat(contract.currentVersion()).isEqualTo(3) }
        assertThat(contract.currentVersion()).isEqualTo(1)
    }

    @RestController
    class ContractController(private val contract: ReactiveClientQuestionContractAdapter) {
        @GetMapping("/api/question-contract")
        suspend fun get(@RequestParam(defaultValue = "false") star: Boolean): ResponseEntity<Map<String, Int>> =
            ResponseEntity.ok().header(HttpHeaders.VARY, if (star) "*" else "Accept-Language")
                .body(mapOf("version" to contract.currentVersion()))
    }
}
