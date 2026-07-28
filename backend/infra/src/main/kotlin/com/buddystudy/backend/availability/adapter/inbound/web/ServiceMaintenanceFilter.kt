package com.buddystudy.backend.availability.adapter.inbound.web

import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.inbound.ServiceAvailabilityUseCase
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.mono
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ServiceMaintenanceFilter(
    private val availability: ServiceAvailabilityUseCase,
    private val errors: ApiErrorResponseFactory,
    private val objectMapper: ObjectMapper = JsonMapperProvider.mapper,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (isExempt(exchange.request.path.value())) {
            return chain.filter(exchange)
        }
        return mono { MaintenanceLookup(availability.activeMaintenance()) }
            .flatMap { lookup ->
                lookup.window?.let { maintenanceResponse(exchange, it) }
                    ?: chain.filter(exchange)
            }
    }

    private fun maintenanceResponse(
        exchange: ServerWebExchange,
        maintenance: ServiceMaintenanceWindow,
    ): Mono<Void> {
        val locale = exchange.localeContext.locale ?: java.util.Locale.getDefault()
        val content = maintenance.content.forLocale(locale)
        val now = Instant.now()
        val retryAfter = maintenance.endsAt
            ?.let { Duration.between(now, it).seconds.coerceIn(15, 300) }
            ?: 60
        val metadata = mapOf(
            "maintenanceId" to maintenance.id,
            "title" to content.title,
            "message" to content.message,
            "startsAt" to maintenance.startsAt,
            "endsAt" to maintenance.endsAt,
            "retryAfterSeconds" to retryAfter,
        )
        val envelope = errors.envelope(
            code = ApiErrorCode.SERVICE_UNDER_MAINTENANCE,
            status = HttpStatus.SERVICE_UNAVAILABLE,
            exchange = exchange,
            debugDescription = "Service maintenance window ${maintenance.id} is active.",
            metadata = metadata,
        ).let { body ->
            body.copy(error = body.error.copy(message = content.message))
        }
        val bytes = objectMapper.writeValueAsBytes(envelope)
        exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        exchange.response.headers.set("Retry-After", retryAfter.toString())
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }

    private fun isExempt(path: String): Boolean =
        !path.startsWith("/api/") ||
            path == "/api/v1/service-status" ||
            path.startsWith("/api/v1/admin/") ||
            path == "/api/v1/health" ||
            path.startsWith("/api/v1/health/")

    private data class MaintenanceLookup(
        val window: ServiceMaintenanceWindow?,
    )
}
