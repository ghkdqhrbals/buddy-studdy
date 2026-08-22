package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.PathContainer
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebExchangeDecorator
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter(
    private val loggingPolicy: ApiLoggingPolicy,
    objectMapper: ObjectMapper = JsonMapperProvider.mapper,
) : WebFilter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val formatter = ApiExchangeLogFormatter(objectMapper)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val requestId = UUID.randomUUID().toString()
        val capturesBodies = loggingPolicy.capturesBodies && !isMcpEndpoint(exchange)
        val requestCapture = BodyCapture(if (capturesBodies) MAX_BODY_BYTES else 0)
        val responseCapture = BodyCapture(if (capturesBodies) MAX_BODY_BYTES else 0)
        val started = System.nanoTime()
        val logged = AtomicBoolean(false)

        exchange.attributes[REQUEST_ID_ATTRIBUTE] = requestId
        exchange.response.headers.set(REQUEST_ID_HEADER, requestId)

        val decorated = if (capturesBodies) decorate(exchange, requestCapture, responseCapture) else exchange

        return chain.filter(decorated)
            .doFinally {
                if (logged.compareAndSet(false, true)) {
                    logExchange(
                        requestId = requestId,
                        exchange = decorated,
                        requestBody = requestCapture.snapshot(),
                        responseBody = responseCapture.snapshot(),
                        durationMs = (System.nanoTime() - started) / 1_000_000.0,
                    )
                }
            }
    }

    private fun decorate(
        exchange: ServerWebExchange,
        requestCapture: BodyCapture,
        responseCapture: BodyCapture,
    ): ServerWebExchange {
        val request = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> =
                super.getBody().doOnNext(requestCapture::capture)
        }
        val response = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
                super.writeWith(Flux.from(body).doOnNext(responseCapture::capture))

            override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
                super.writeAndFlushWith(
                    Flux.from(body).map { publisher ->
                        Flux.from(publisher).doOnNext(responseCapture::capture)
                    }
                )
        }
        return object : ServerWebExchangeDecorator(exchange) {
            override fun getRequest() = request
            override fun getResponse() = response
        }
    }

    private fun isMcpEndpoint(exchange: ServerWebExchange): Boolean =
        exchange.request.path.pathWithinApplication().elements()
            .filterIsInstance<PathContainer.PathSegment>()
            .map { it.valueToMatch() } == MCP_ENDPOINT_SEGMENTS

    private fun logExchange(
        requestId: String,
        exchange: ServerWebExchange,
        requestBody: CapturedBody,
        responseBody: CapturedBody,
        durationMs: Double,
    ) {
        val status = exchange.response.statusCode?.value() ?: 200
        try {
            if (loggingPolicy.includesRequestIdInMdc) {
                MDC.put("requestId", requestId)
            }
            if (exchange.request.path.value().startsWith("/api/")) {
                logApiExchange(
                    if (loggingPolicy.includesRequestMetadata) {
                        formatter.apiExchangeJson(
                            requestId,
                            exchange.getAttribute<Long>(AUTHENTICATED_USER_ID_ATTRIBUTE)?.toString() ?: ANONYMOUS_USER_ID,
                            exchange.request,
                            exchange.response,
                            requestBody,
                            responseBody,
                            durationMs,
                        )
                    } else {
                        formatter.compactApiExchangeJson(
                            exchange.request,
                            exchange.response,
                            requestBody,
                            responseBody,
                            durationMs,
                        )
                    },
                    status,
                )
            } else {
                logApiResponse(
                    if (loggingPolicy.includesRequestMetadata) {
                        formatter.apiResponseJson(
                            requestId,
                            exchange.request,
                            exchange.response,
                            responseBody,
                            durationMs,
                            includeBody = false,
                        )
                    } else {
                        formatter.compactApiResponseJson(
                            exchange.request,
                            exchange.response,
                            responseBody,
                            durationMs,
                        )
                    },
                    status,
                )
            }
        } catch (error: Exception) {
            log.warn("api_exchange_logging_failed {}", loggingPolicy.loggingFailure(requestId, error.message))
        } finally {
            if (loggingPolicy.includesRequestIdInMdc) {
                MDC.remove("requestId")
            }
        }
    }

    private fun logApiExchange(message: String, status: Int) {
        when {
            status >= 500 -> log.error("api_exchange {}", message)
            status >= 400 -> log.warn("api_exchange {}", message)
            else -> log.info("api_exchange {}", message)
        }
    }

    private fun logApiResponse(message: String, status: Int) {
        when {
            status >= 500 -> log.error("api_response {}", message)
            status >= 400 -> log.warn("api_response {}", message)
            else -> log.info("api_response {}", message)
        }
    }

    companion object {
        const val REQUEST_ID_ATTRIBUTE = "requestId"
        const val AUTHENTICATED_USER_ID_ATTRIBUTE = "authenticatedUserId"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val ANONYMOUS_USER_ID = "-"
        private val MCP_ENDPOINT_SEGMENTS = listOf("api", "v1", "mcp")
        private const val MAX_BODY_BYTES = 8_192
    }
}

private class BodyCapture(
    private val limit: Int,
) {
    private val output = ByteArrayOutputStream(limit)
    private val observedBytes = AtomicLong()

    @Synchronized
    fun capture(buffer: DataBuffer) {
        val readable = buffer.readableByteCount()
        observedBytes.addAndGet(readable.toLong())
        val remaining = limit - output.size()
        if (remaining <= 0 || readable <= 0) return

        val length = minOf(readable, remaining)
        val destination = ByteBuffer.allocate(length)
        buffer.toByteBuffer(buffer.readPosition(), destination, 0, length)
        output.write(destination.array())
    }

    @Synchronized
    fun snapshot(): CapturedBody {
        val observed = observedBytes.get()
        return CapturedBody(
            bytes = output.toByteArray(),
            observedBytes = observed,
            truncated = observed > output.size(),
        )
    }
}
