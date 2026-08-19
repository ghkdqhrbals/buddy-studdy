package com.buddystudy.backend.externalapi.adapter.outbound.history

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.inbound.ExternalApiCallHistoryUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.util.UUID

data class ExternalApiRequest(
    val provider: String,
    val operation: String,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class ExternalApiResponse<T>(
    val value: T,
    val statusCode: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

@Component
class ExternalApiHistoryRecorder(
    private val history: ExternalApiCallHistoryUseCase,
    private val redactor: SensitiveDataRedactor,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun <T> record(
        request: ExternalApiRequest,
        call: suspend () -> ExternalApiResponse<T>,
    ): T = recordWithCorrelation(request, MDC.get("requestId"), call)

    private suspend fun <T> recordWithCorrelation(
        request: ExternalApiRequest,
        correlationId: String?,
        call: suspend () -> ExternalApiResponse<T>,
    ): T {
        val callId = start(request, correlationId)
        val response = try {
            call()
        } catch (error: Throwable) {
            val httpError = httpFailure(error)
            finish(
                callId = callId,
                status = when {
                    error is CancellationException -> "CANCELLED"
                    httpError != null -> "HTTP_ERROR"
                    else -> "FAILED"
                },
                responseStatus = httpError?.statusCode,
                responseHeaders = httpError?.headers.orEmpty(),
                responseBody = httpError?.body,
                errorType = error.javaClass.name,
                errorMessage = error.message,
            )
            throw error
        }
        finish(
            callId = callId,
            status = if (response.statusCode in 200..299) "SUCCEEDED" else "HTTP_ERROR",
            responseStatus = response.statusCode,
            responseHeaders = response.headers,
            responseBody = response.body,
        )
        return response.value
    }

    fun <T> recordBlocking(
        request: ExternalApiRequest,
        call: () -> ExternalApiResponse<T>,
    ): T {
        val correlationId = MDC.get("requestId")
        return runBlocking(Dispatchers.IO) { recordWithCorrelation(request, correlationId) { call() } }
    }

    fun json(value: Any?): String? = value?.let { objectMapper.writeValueAsString(it) }

    private fun httpFailure(error: Throwable): ExternalHttpFailure? =
        generateSequence(error) { it.cause }.mapNotNull { cause ->
            when (cause) {
                is WebClientResponseException -> ExternalHttpFailure(
                    cause.statusCode.value(),
                    cause.headers.toSingleValueMap(),
                    cause.responseBodyAsString,
                )
                is RestClientResponseException -> ExternalHttpFailure(
                    cause.statusCode.value(),
                    cause.responseHeaders?.toSingleValueMap().orEmpty(),
                    cause.responseBodyAsString,
                )
                else -> null
            }
        }.firstOrNull()

    private suspend fun start(request: ExternalApiRequest, correlationId: String?): String {
        val callId = UUID.randomUUID().toString()
        history.start(
            StartExternalApiCallCommand(
                callId = callId,
                correlationId = correlationId?.trim()?.takeIf(String::isNotEmpty),
                provider = request.provider.trim().lowercase(),
                operation = request.operation.trim(),
                httpMethod = request.method.trim().uppercase(),
                requestUrl = redactor.url(request.url),
                requestHeadersJson = objectMapper.writeValueAsString(redactor.fields(request.headers)),
                requestBody = request.body?.let(redactor::text),
                startedAt = Instant.now(),
            ),
        )
        return callId
    }

    private suspend fun finish(
        callId: String,
        status: String,
        responseStatus: Int?,
        responseHeaders: Map<String, String>,
        responseBody: String?,
        errorType: String? = null,
        errorMessage: String? = null,
    ) {
        val command = FinishExternalApiCallCommand(
            callId = callId,
            status = status,
            responseStatus = responseStatus,
            responseHeadersJson = objectMapper.writeValueAsString(redactor.fields(responseHeaders)),
            responseBody = responseBody?.let(redactor::text),
            errorType = errorType,
            errorMessage = errorMessage,
            finishedAt = Instant.now(),
        )
        var lastFailure: Throwable? = null
        repeat(FINISH_ATTEMPTS) { attempt ->
            try {
                history.finish(command)
                return
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt + 1 < FINISH_ATTEMPTS) delay(FINISH_RETRY_DELAY_MS)
            }
        }
        logger.error("external_api_history_finish_failed callId={} status={}", callId, status, lastFailure)
        throw IllegalStateException("External API response history could not be persisted for call $callId.", lastFailure)
    }

    private companion object {
        const val FINISH_ATTEMPTS = 3
        const val FINISH_RETRY_DELAY_MS = 50L
    }

    private data class ExternalHttpFailure(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String,
    )
}
