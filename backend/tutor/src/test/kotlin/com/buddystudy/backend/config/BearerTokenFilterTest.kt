package com.buddystudy.backend.config

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.StaticMessageSource
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant

class BearerTokenFilterTest {
    @Test
    fun `valid bearer token stores verified principal without copying raw token to attributes`(): Unit = runBlocking {
        val properties = BuddyStudyProperties().apply {
            auth.jwtSecret = "test-jwt-secret"
        }
        val tokenProvider = TokenProvider(properties)
        val principal = Principal(
            userId = 42,
            deviceId = "device-42",
            sessionId = 99,
            anonymous = false,
            status = "ACTIVE",
        )
        val (accessToken, _) = tokenProvider.create(
            userId = principal.userId,
            deviceId = principal.deviceId,
            sessionId = principal.sessionId,
            anonymous = principal.anonymous,
            status = principal.status,
        )
        val devices = Mockito.mock(DevicePort::class.java)
        val userDevices = Mockito.mock(UserDevicePort::class.java)
        Mockito.`when`(devices.findByDeviceId(principal.deviceId)).thenReturn(
            DeviceEntity(deviceId = principal.deviceId, userId = principal.userId),
        )
        Mockito.`when`(userDevices.findByIdAndUserId(principal.sessionId, principal.userId)).thenReturn(
            UserDeviceEntity(
                id = principal.sessionId,
                userId = principal.userId,
                deviceId = principal.deviceId,
                sessionExpiresAt = Instant.now().plusSeconds(3_600),
            ),
        )
        val filter = BearerTokenFilter(
            tokenProvider = tokenProvider,
            devices = devices,
            userDevices = userDevices,
            objectMapper = JsonMapperProvider.mapper,
            errorResponseFactory = ApiErrorResponseFactory(StaticMessageSource()),
            loggingPolicy = ApiLoggingPolicy("detailed"),
        )
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/profile")
                .header("Authorization", "Bearer $accessToken")
                .build(),
        )
        var downstreamPrincipal: Principal? = null

        filter.filter(
            exchange,
            WebFilterChain { current ->
                downstreamPrincipal = current.getAttribute(BearerTokenFilter.AUTHENTICATED_PRINCIPAL_ATTRIBUTE)
                Mono.empty()
            },
        ).block()

        assertThat(downstreamPrincipal).isEqualTo(principal)
        assertThat(exchange.getAttribute<Principal>(BearerTokenFilter.AUTHENTICATED_PRINCIPAL_ATTRIBUTE))
            .isEqualTo(principal)
        assertThat(exchange.getAttribute<Long>(RequestLoggingFilter.AUTHENTICATED_USER_ID_ATTRIBUTE))
            .isEqualTo(principal.userId)
        assertThat(exchange.attributes.values).doesNotContain(accessToken)
    }
}
