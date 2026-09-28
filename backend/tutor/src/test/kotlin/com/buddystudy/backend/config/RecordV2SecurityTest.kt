package com.buddystudy.backend.config

import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

class RecordV2SecurityTest {
    private val mapper = JsonMapperProvider.mapper
    private val errors = ApiErrorResponseFactory(StaticMessageSource())
    private val logging = ApiLoggingPolicy("minimal")
    private val bearer = BearerTokenFilter(
        mock(TokenProvider::class.java), mock(DevicePort::class.java), mock(UserDevicePort::class.java),
        mapper, errors, logging,
    )
    private val chain = SecurityConfig().securityWebFilterChain(ServerHttpSecurity.http(), bearer, mapper, errors, logging)
    private val client = WebTestClient.bindToRouterFunction(
        RouterFunctions.route(RequestPredicates.GET("/api/**")) { ServerResponse.ok().bodyValue(mapOf("ok" to true)) },
    ).webFilter<WebTestClient.RouterFunctionSpec>(WebFilterChainProxy(chain)).build()

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/v1/public/questions/liked", "/api/v2/public/questions/liked",
        "/api/v2/records", "/api/v2/records/42", "/api/v2/records/42/thread",
        "/api/v2/studies", "/api/v2/studies/10", "/api/v2/studies/10/learning-records",
    ])
    fun `owned and liked records reject missing and invalid tokens`(path: String) {
        client.get().uri(path).exchange().expectStatus().isUnauthorized.expectBody()
            .jsonPath("$.error.errorCode").isEqualTo("AUTH_ACCESS_TOKEN_REQUIRED")
        client.get().uri(path).header("Authorization", "Bearer invalid").exchange().expectStatus().isUnauthorized.expectBody()
            .jsonPath("$.error.errorCode").isEqualTo("AUTH_INVALID_ACCESS_TOKEN")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v2/public/questions", "/api/v2/public/questions/search", "/api/v2/public/questions/42"])
    fun `public v2 browsing still allows anonymous and invalid-token fallback`(path: String) {
        client.get().uri(path).exchange().expectStatus().isOk
        client.get().uri(path).header("Authorization", "Bearer invalid").exchange().expectStatus().isOk
    }
}
