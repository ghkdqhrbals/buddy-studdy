package com.buddystudy.backend.admin.status.adapter.outbound.health

import com.buddystudy.backend.test.testExternalApiHistoryRecorder

import com.buddystudy.backend.config.BuddyStudyProperties
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList

class AdminTranslationProviderHealthProbeTest {
    @Test
    fun `checks LibreTranslate and OpenAI without exposing the OpenAI key`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<ClientRequest>()
        val properties = configuredProperties()
        val probe = probe(properties) { request ->
            requests += request
            ClientResponse.create(HttpStatus.OK).body("""{"translatedText":"Hello"}""").build()
        }

        val result = probe.checkTranslationProviders()

        assertThat(result.providers.map { it.provider }).containsExactly("libretranslate", "openai")
        assertThat(result.providers).allSatisfy { provider ->
            assertThat(provider.status).isEqualTo("UP")
            assertThat(provider.enabled).isTrue()
            assertThat(provider.latencyMs).isNotNull().isGreaterThanOrEqualTo(0)
        }
        assertThat(requests.map { it.url().toString() }).containsExactlyInAnyOrder(
            "https://libre.example/translate",
            "https://api.openai.com/v1/models",
        )
        assertThat(requests.single { it.url().host == "libre.example" }.method()).isEqualTo(org.springframework.http.HttpMethod.POST)
        val openAIRequest = requests.single { it.url().host == "api.openai.com" }
        assertThat(openAIRequest.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer secret-openai-key")
        assertThat(result.toString()).doesNotContain("secret-openai-key")
    }

    @Test
    fun `reports each provider independently when LibreTranslate is unavailable`() = runBlocking<Unit> {
        val properties = configuredProperties()
        val probe = probe(properties) { request ->
            if (request.url().host == "libre.example") {
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()
            } else {
                ClientResponse.create(HttpStatus.OK).body("""{"translatedText":"Hello"}""").build()
            }
        }

        val providers = probe.checkTranslationProviders().providers.associateBy { it.provider }

        assertThat(providers.getValue("libretranslate").status).isEqualTo("DOWN")
        assertThat(providers.getValue("libretranslate").detail).isEqualTo("Provider returned HTTP 503 Service Unavailable.")
        assertThat(providers.getValue("openai").status).isEqualTo("UP")
    }

    @Test
    fun `reports OpenAI as not configured without sending an OpenAI request`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<ClientRequest>()
        val properties = configuredProperties().apply { openai.userContentApiKey = "" }
        val probe = probe(properties) { request ->
            requests += request
            ClientResponse.create(HttpStatus.OK).body("""{"translatedText":"Hello"}""").build()
        }

        val providers = probe.checkTranslationProviders().providers.associateBy { it.provider }

        assertThat(providers.getValue("openai").status).isEqualTo("NOT_CONFIGURED")
        assertThat(providers.getValue("openai").latencyMs).isNull()
        assertThat(requests.map { it.url().host }).containsExactly("libre.example")
    }

    @Test
    fun `does not report a reachable provider with empty translations as healthy`() = runBlocking<Unit> {
        val check = probe(configuredProperties()) { request ->
            ClientResponse.create(HttpStatus.OK).body(
                if (request.url().host == "libre.example") """{"translatedText":" "}""" else "[]",
            ).build()
        }
        val providers = check.checkTranslationProviders().providers.associateBy { it.provider }
        assertThat(providers.getValue("libretranslate").status).isEqualTo("DOWN")
        assertThat(providers.getValue("libretranslate").detail).contains("empty translation")
        assertThat(providers.getValue("openai").status).isEqualTo("UP")
    }

    private fun configuredProperties() = BuddyStudyProperties().apply {
        translation.baseUrl = "https://libre.example/"
        translation.providerOrder = listOf("libretranslate", "openai")
        translation.timeoutMs = 1_000
        openai.userContentApiKey = "secret-openai-key"
    }

    private fun probe(
        properties: BuddyStudyProperties,
        response: (ClientRequest) -> ClientResponse,
    ): AdminTranslationProviderHealthProbe {
        val exchange = ExchangeFunction { request -> Mono.just(response(request)) }
        return AdminTranslationProviderHealthProbe(
            WebClient.builder().exchangeFunction(exchange),
            properties,
            testExternalApiHistoryRecorder(),
        )
    }
}
