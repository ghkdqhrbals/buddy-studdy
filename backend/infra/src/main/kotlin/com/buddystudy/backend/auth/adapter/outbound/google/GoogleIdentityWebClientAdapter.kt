package com.buddystudy.backend.auth.adapter.outbound.google

import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentity
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentityPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiHistoryRecorder
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiRequest
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiResponse
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Component
class GoogleIdentityWebClientAdapter(
    webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
    @Value("\${buddystudy.auth.google-token-info-base-url:https://oauth2.googleapis.com}")
    baseUrl: String,
    private val history: ExternalApiHistoryRecorder,
) : GoogleIdentityPort {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val client = webClientBuilder
        .baseUrl(baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .responseTimeout(RESPONSE_TIMEOUT),
            ),
        )
        .build()

    override suspend fun verify(idToken: String): GoogleIdentity? = history.record(
        ExternalApiRequest(
            provider = "google",
            operation = "verify-id-token",
            method = "GET",
            url = "$normalizedBaseUrl/tokeninfo?id_token=$idToken",
        ),
    ) {
        val entity = client.get()
            .uri { builder -> builder.path("/tokeninfo").queryParam("id_token", idToken).build() }
            .exchangeToMono { response -> response.toEntity(String::class.java) }
            .awaitSingleOrNull()
        val payload = entity?.body.orEmpty()
        ExternalApiResponse(
            value = entity?.takeIf { it.statusCode.is2xxSuccessful }?.let { parseIdentity(payload) },
            statusCode = entity?.statusCode?.value() ?: 204,
            headers = entity?.headers?.toSingleValueMap().orEmpty(),
            body = payload,
        )
    }

    private fun parseIdentity(payload: String): GoogleIdentity? {
        val json = objectMapper.readTree(payload)
        val providerId = json.path("sub").asText().takeIf { it.isNotBlank() } ?: return null
        return GoogleIdentity(
            providerId = providerId,
            email = json.path("email").asText(""),
            name = json.path("name").asText().takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
