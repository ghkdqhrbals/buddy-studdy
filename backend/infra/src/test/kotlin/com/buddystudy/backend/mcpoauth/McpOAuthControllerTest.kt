package com.buddystudy.backend.mcpoauth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class McpOAuthControllerTest {
    private val port = Mockito.mock(McpOAuthWebPort::class.java)
    private val issuer = "https://api.example.com"
    private val id = "x".repeat(43)
    private val client = WebTestClient.bindToController(McpOAuthController(port, issuer, "client"))
        .webFilter<WebTestClient.ControllerSpec>(McpOAuthSecurityFilter(true)).build()

    @Test
    fun `scope display comes from the cookie bound server request`(): Unit = runBlocking {
        Mockito.`when`(port.browserScopes(id, "secret")).thenReturn(setOf("learning:read", "learning:write"))
        client.post().uri("/oauth/session").header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
            .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("id" to id)).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.scopes[0]").isEqualTo("learning:read")
            .jsonPath("$.scopes[1]").isEqualTo("learning:write")
        client.post().uri("/oauth/session").header(HttpHeaders.ORIGIN, "https://evil.example").header("X-OAuth-Intent", "connect")
            .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("id" to id)).exchange().expectStatus().isBadRequest
        Mockito.verify(port, Mockito.times(1)).browserScopes(id, "secret")
    }

    @Test
    fun `discovery advertises exact audience PKCE public client and issuer response validation`() {
        client.get().uri("/.well-known/oauth-protected-resource/api/v1/mcp").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.resource").isEqualTo("$issuer/api/v1/mcp")
            .jsonPath("$.authorization_servers[0]").isEqualTo(issuer)
        client.get().uri("/.well-known/oauth-authorization-server").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.code_challenge_methods_supported[0]").isEqualTo("S256")
            .jsonPath("$.token_endpoint_auth_methods_supported[0]").isEqualTo("none")
            .jsonPath("$.authorization_response_iss_parameter_supported").isEqualTo(true)
    }

    @Test
    fun `disabled OAuth returns 404 for browser and discovery endpoints`() {
        val disabled = WebTestClient.bindToController(McpOAuthController(port, issuer, "client"))
            .webFilter<WebTestClient.ControllerSpec>(McpOAuthSecurityFilter(false)).build()
        for (path in listOf("/oauth/authorize", "/oauth/connect.html", "/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server"))
            disabled.get().uri(path).exchange().expectStatus().isNotFound
        Mockito.verifyNoInteractions(port)
    }

    @Test
    fun `cross origin missing intent and missing cookie cannot verify identity`() {
        for (origin in listOf("https://evil.example", "null", issuer)) {
            client.post().uri("/oauth/verify").header(HttpHeaders.ORIGIN, origin)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("id" to id, "code" to "12345678"))
                .exchange().expectStatus().isBadRequest
        }
        client.post().uri("/oauth/verify").header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("id" to id, "code" to "12345678"))
            .exchange().expectStatus().isBadRequest
        Mockito.verifyNoInteractions(port)
    }

    @Test
    fun `same origin browser request forwards cookie to identity verifier`(): Unit = runBlocking {
        Mockito.`when`(port.verify(id, "secret", "12345678")).thenReturn(true)
        client.post().uri("/oauth/verify").header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
            .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("id" to id, "code" to "12345678")).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectBody().jsonPath("$.verified").isEqualTo(true)
    }

    @Test
    fun `form encoded token errors follow OAuth JSON format`(): Unit = runBlocking {
        val parameters = mapOf("grant_type" to "client_credentials", "client_id" to "client")
        Mockito.`when`(port.token(parameters)).thenReturn(McpOAuthResult(error = "unsupported_grant_type"))
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=client_credentials&client_id=client").exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.error").isEqualTo("unsupported_grant_type")
    }

    @Test
    fun `successful token JSON uses OAuth field names`(): Unit = runBlocking {
        val parameters = mapOf("grant_type" to "authorization_code")
        Mockito.`when`(port.token(parameters)).thenReturn(McpOAuthResult(tokens = McpOAuthTokens("access", "refresh")))
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=authorization_code").exchange().expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.access_token").isEqualTo("access")
            .jsonPath("$.refresh_token").isEqualTo("refresh")
            .jsonPath("$.token_type").isEqualTo("Bearer")
            .jsonPath("$.expires_in").isEqualTo(900)
    }

    @Test
    fun `account switch replaces the browser request cookie`(): Unit = runBlocking {
        val nextId = "y".repeat(43)
        Mockito.`when`(port.restart(id, "secret", "unknown")).thenReturn(McpOAuthStart(nextId, "next-secret"))
        client.post().uri("/oauth/restart").header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
            .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("id" to id)).exchange().expectStatus().isOk
            .expectHeader().valueMatches("Set-Cookie", ".*__Secure-bs-oauth-$nextId=next-secret.*Secure.*HttpOnly.*")
            .expectBody().jsonPath("$.redirect").isEqualTo("/oauth/connect.html?id=$nextId")
    }

    @Test
    fun `authorization only uses server generated browser cookie and local login destination`(): Unit = runBlocking {
        val parameters = mapOf("client_id" to "client")
        Mockito.`when`(port.start(parameters, "unknown")).thenReturn(McpOAuthStart(id, "secret"))
        client.get().uri("/oauth/authorize?client_id=client").exchange().expectStatus().isFound
            .expectHeader().valueEquals("Location", "/oauth/connect.html?id=$id")
            .expectHeader().valueMatches("Set-Cookie", ".*Secure.*HttpOnly.*SameSite=Lax.*")
    }
}
