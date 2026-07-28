package com.buddystudy.backend.availability.adapter.inbound.web

import com.buddystudy.backend.availability.application.model.LocalizedMaintenanceContent
import com.buddystudy.backend.availability.application.model.ServiceAvailability
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.inbound.ServiceAvailabilityUseCase
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Locale

class ServiceMaintenanceFilterTest {
    private val availability = StubServiceAvailability()
    private val filter = ServiceMaintenanceFilter(
        availability = availability,
        errors = ApiErrorResponseFactory(StaticMessageSource()),
    )

    @Test
    fun `active maintenance blocks application APIs with localized 503 response`() {
        availability.active = maintenanceWindow()
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/studies")
                .header("Accept-Language", "ko")
                .build(),
        )
        var downstreamCalled = false

        filter.filter(exchange, WebFilterChain {
            downstreamCalled = true
            Mono.empty()
        }).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exchange.response.headers.getFirst("Retry-After")).isNotBlank()
        assertThat(exchange.response.bodyAsString.block())
            .contains("\"errorCode\":\"SERVICE_UNDER_MAINTENANCE\"")
            .contains("\"maintenanceId\":9")
            .contains("점검 안내")
        assertThat(downstreamCalled).isFalse()
    }

    @Test
    fun `active maintenance also blocks versioned public APIs`() {
        availability.active = maintenanceWindow()
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v2/public/questions/search").build(),
        )
        var downstreamCalled = false

        filter.filter(exchange, WebFilterChain {
            downstreamCalled = true
            Mono.empty()
        }).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(downstreamCalled).isFalse()
    }

    @Test
    fun `status admin and health endpoints remain available during maintenance`() {
        availability.active = maintenanceWindow()
        listOf(
            "/api/v1/service-status",
            "/api/v1/admin/service-maintenance",
            "/api/v1/health",
            "/api/v1/health/readiness",
        ).forEach { path ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
            var downstreamCalled = false

            filter.filter(exchange, WebFilterChain {
                downstreamCalled = true
                Mono.empty()
            }).block()

            assertThat(downstreamCalled).describedAs(path).isTrue()
        }
    }

    private fun maintenanceWindow(): ServiceMaintenanceWindow {
        val now = Instant.now()
        return ServiceMaintenanceWindow(
            id = 9,
            content = LocalizedMaintenanceContent(
                titleKo = "점검 안내",
                titleEn = "Maintenance",
                titleJa = "メンテナンス",
                messageKo = "잠시 후 다시 이용해 주세요.",
                messageEn = "Please try again shortly.",
                messageJa = "しばらくしてからお試しください。",
            ),
            startsAt = now.minusSeconds(30),
            endsAt = now.plusSeconds(120),
            terminatedAt = null,
            createdBy = "admin",
            terminatedBy = null,
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )
    }
}

private class StubServiceAvailability : ServiceAvailabilityUseCase {
    var active: ServiceMaintenanceWindow? = null

    override suspend fun availability(locale: Locale): ServiceAvailability =
        ServiceAvailability(status = "OPERATIONAL", checkedAt = Instant.now())

    override suspend fun activeMaintenance(): ServiceMaintenanceWindow? = active
}
