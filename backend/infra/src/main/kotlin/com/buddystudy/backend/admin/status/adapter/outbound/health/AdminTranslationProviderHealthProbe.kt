package com.buddystudy.backend.admin.status.adapter.outbound.health

import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealth
import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse
import com.buddystudy.backend.admin.status.application.port.outbound.AdminProviderHealthPort
import com.buddystudy.backend.config.BuddyStudyProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.supervisorScope
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

@Component
class AdminTranslationProviderHealthProbe(
    webClientBuilder: WebClient.Builder,
    private val properties: BuddyStudyProperties,
) : AdminProviderHealthPort {
    private val client = webClientBuilder.clone().build()

    override suspend fun checkTranslationProviders(): AdminTranslationProviderHealthResponse = supervisorScope {
        val checkedAt = Instant.now()
        val enabled = properties.translation.providerOrder.map(String::lowercase).toSet()
        val providers = listOf(
            async { checkLibreTranslate("libretranslate" in enabled) },
            async { checkOpenAI("openai" in enabled) },
        ).awaitAll()
        AdminTranslationProviderHealthResponse(checkedAt = checkedAt, providers = providers)
    }

    private suspend fun checkLibreTranslate(enabled: Boolean): AdminTranslationProviderHealth {
        val baseUrl = properties.translation.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return notConfigured(PROVIDER_LIBRETRANSLATE, enabled, "LibreTranslate base URL is not configured.")
        }
        return probe(PROVIDER_LIBRETRANSLATE, enabled) {
            client.get()
                .uri("$baseUrl/languages")
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout())
                .awaitSingle()
        }
    }

    private suspend fun checkOpenAI(enabled: Boolean): AdminTranslationProviderHealth {
        val apiKey = properties.openai.userContentApiKey.trim()
        if (apiKey.isBlank()) {
            return notConfigured(PROVIDER_OPENAI, enabled, "OpenAI API key is not configured.")
        }
        return probe(PROVIDER_OPENAI, enabled) {
            client.get()
                .uri(OPENAI_MODELS_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout())
                .awaitSingle()
        }
    }

    private suspend fun probe(
        provider: String,
        enabled: Boolean,
        request: suspend () -> Unit,
    ): AdminTranslationProviderHealth {
        val startedAt = System.nanoTime()
        return try {
            request()
            AdminTranslationProviderHealth(
                provider = provider,
                status = STATUS_UP,
                enabled = enabled,
                latencyMs = elapsedMillis(startedAt),
                detail = "Provider API responded successfully.",
            )
        } catch (error: Exception) {
            AdminTranslationProviderHealth(
                provider = provider,
                status = STATUS_DOWN,
                enabled = enabled,
                latencyMs = elapsedMillis(startedAt),
                detail = safeFailureDetail(error),
            )
        }
    }

    private fun timeout(): Duration =
        Duration.ofMillis(properties.translation.timeoutMs.coerceIn(100, MAX_TIMEOUT_MS))

    private fun elapsedMillis(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)

    private fun notConfigured(provider: String, enabled: Boolean, detail: String) =
        AdminTranslationProviderHealth(
            provider = provider,
            status = STATUS_NOT_CONFIGURED,
            enabled = enabled,
            latencyMs = null,
            detail = detail,
        )

    private fun safeFailureDetail(error: Exception): String = when (error) {
        is WebClientResponseException -> "Provider returned HTTP ${error.statusCode.value()} ${error.statusText}."
        is WebClientRequestException -> "Could not connect to the provider API."
        is TimeoutException -> "Provider check timed out after ${timeout().toMillis()} ms."
        else -> if (error.cause is TimeoutException) {
            "Provider check timed out after ${timeout().toMillis()} ms."
        } else {
            "Provider check failed (${error.javaClass.simpleName})."
        }
    }

    private companion object {
        const val PROVIDER_LIBRETRANSLATE = "libretranslate"
        const val PROVIDER_OPENAI = "openai"
        const val STATUS_UP = "UP"
        const val STATUS_DOWN = "DOWN"
        const val STATUS_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val MAX_TIMEOUT_MS = 15_000L
        const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
    }
}
