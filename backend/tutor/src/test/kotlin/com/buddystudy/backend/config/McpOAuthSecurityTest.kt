package com.buddystudy.backend.config

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.mcpoauth.McpOAuthUseCase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.StaticMessageSource
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

class McpOAuthSecurityTest {
    private val oauth = Mockito.mock(McpOAuthUseCase::class.java)
    private val mapper = JsonMapperProvider.mapper
    private val errors = ApiErrorResponseFactory(StaticMessageSource())
    private val policy = ApiLoggingPolicy("compact")
    private val bearer = BearerTokenFilter(TokenProvider(BuddyStudyProperties()),
        Mockito.mock(DevicePort::class.java), Mockito.mock(UserDevicePort::class.java), mapper, errors, policy,
        oauth, true, "https://api.example.com")
    private val security = SecurityConfig().securityWebFilterChain(ServerHttpSecurity.http(), bearer, mapper, errors, policy)
    private val router = RouterFunctions.route().POST("/api/v1/mcp") { request ->
        val principal = request.attributes()[BearerTokenFilter.AUTHENTICATED_PRINCIPAL_ATTRIBUTE] as Principal
        ServerResponse.ok().bodyValue(mapOf("userId" to principal.userId))
    }.GET("/api/v1/profile") { ServerResponse.ok().bodyValue("app-only") }.build()
    private val client = WebTestClient.bindToRouterFunction(router).webFilter<WebTestClient.RouterFunctionSpec>(WebFilterChainProxy(security)).build()

    @Test
    fun `missing bearer prompts OAuth discovery instead of requiring app credentials`() {
        client.post().uri("/api/v1/mcp").exchange().expectStatus().isUnauthorized
            .expectHeader().valueMatches("WWW-Authenticate", ".*oauth-protected-resource/api/v1/mcp.*")
    }
    @Test
    fun `OAuth bearer is accepted only at MCP and propagates its owner`(): Unit = runBlocking {
        val raw = "bs_mcp_a.test.secret"
        Mockito.`when`(oauth.authenticate(raw)).thenReturn(Principal(7, "mcp:test", 0, false, oauthScopes = setOf("learning:read")))
        client.post().uri("/api/v1/mcp").header("Authorization", "Bearer $raw").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.userId").isEqualTo(7)
        client.get().uri("/api/v1/profile").header("Authorization", "Bearer $raw").exchange().expectStatus().isUnauthorized
    }
    @Test
    fun `invalid OAuth and ordinary app tokens cannot authenticate OAuth-only MCP`() {
        for (raw in listOf("bs_mcp_a.invalid", "ordinary-app-token")) {
            client.post().uri("/api/v1/mcp").header("Authorization", "Bearer $raw").exchange().expectStatus().isUnauthorized
        }
    }
}
