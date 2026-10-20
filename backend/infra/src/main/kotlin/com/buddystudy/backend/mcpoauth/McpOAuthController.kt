package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.time.Duration
import kotlinx.coroutines.reactor.awaitSingle

interface McpOAuthWebPort {
    suspend fun start(parameters: Map<String, String>, remoteAddress: String): McpOAuthStart
    suspend fun manage(remoteAddress: String): McpOAuthStart
    suspend fun browserConnections(id: String, secret: String): List<McpOAuthConnection>
    suspend fun browserDisconnect(id: String, secret: String, connectionId: String)
    suspend fun sendCode(id: String, secret: String, email: String, remoteAddress: String): Boolean
    suspend fun verify(id: String, secret: String, code: String): Boolean
    suspend fun consent(id: String, secret: String, allow: Boolean): String
    suspend fun token(parameters: Map<String, String>): McpOAuthResult
    suspend fun revoke(token: String, clientId: String)
    suspend fun connections(userId: Long): List<McpOAuthConnection>
    suspend fun disconnect(userId: Long, id: String)
}
@Component
class McpOAuthWebAdapter(private val oauth: McpOAuthUseCase) : McpOAuthWebPort {
    override suspend fun start(parameters: Map<String, String>, remoteAddress: String) = oauth.start(McpOAuthRequest(
        parameters["client_id"].orEmpty(), parameters["redirect_uri"].orEmpty(), parameters["state"].orEmpty(),
        parameters["code_challenge"].orEmpty(), parameters["resource"].orEmpty(), parameters["scope"].orEmpty(),
        parameters["response_type"].orEmpty(), parameters["code_challenge_method"].orEmpty(), remoteAddress))
    override suspend fun manage(remoteAddress: String) = oauth.startManagement(remoteAddress)
    override suspend fun browserConnections(id: String, secret: String) = oauth.browserConnections(id, secret)
    override suspend fun browserDisconnect(id: String, secret: String, connectionId: String) = oauth.browserDisconnect(id, secret, connectionId)
    override suspend fun sendCode(id: String, secret: String, email: String, remoteAddress: String) = oauth.sendCode(id, secret, email, remoteAddress)
    override suspend fun verify(id: String, secret: String, code: String) = oauth.verifyCode(id, secret, code)
    override suspend fun consent(id: String, secret: String, allow: Boolean) = oauth.consent(id, secret, allow)
    override suspend fun token(parameters: Map<String, String>) = oauth.token(parameters)
    override suspend fun revoke(token: String, clientId: String) = oauth.revoke(token, clientId)
    override suspend fun connections(userId: Long) = oauth.connections(userId)
    override suspend fun disconnect(userId: Long, id: String) = oauth.disconnect(userId, id)
}

@RestController
class McpOAuthController(
    private val oauth: McpOAuthWebPort,
    @Value("\${buddystudy.mcp.oauth.issuer:https://api.ghkdqhrbals.org}") private val issuer: String,
    @Value("\${buddystudy.mcp.oauth.client-id:buddystudy-chatgpt}") private val clientId: String,
    @Value("\${buddystudy.mcp.oauth.trust-proxy:false}") private val trustProxy: Boolean = false,
) {
    private val resource get() = "$issuer/api/v1/mcp"
    @GetMapping("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/v1/mcp")
    fun resourceMetadata() = mapOf("resource" to resource, "authorization_servers" to listOf(issuer),
        "scopes_supported" to listOf("learning:read", "offline_access"), "bearer_methods_supported" to listOf("header"))

    @GetMapping("/.well-known/oauth-authorization-server")
    fun authorizationMetadata() = mapOf(
        "issuer" to issuer, "authorization_endpoint" to "$issuer/oauth/authorize",
        "token_endpoint" to "$issuer/oauth/token", "revocation_endpoint" to "$issuer/oauth/revoke",
        "response_types_supported" to listOf("code"), "grant_types_supported" to listOf("authorization_code", "refresh_token"),
        "token_endpoint_auth_methods_supported" to listOf("none"), "revocation_endpoint_auth_methods_supported" to listOf("none"),
        "code_challenge_methods_supported" to listOf("S256"),
        "authorization_response_iss_parameter_supported" to true,
        "scopes_supported" to listOf("learning:read", "offline_access"))

    @GetMapping("/oauth/authorize")
    suspend fun authorize(@RequestParam parameters: MultiValueMap<String, String>, exchange: ServerWebExchange): ResponseEntity<Void> {
        require(parameters.values.all { it.size == 1 })
        val start = oauth.start(parameters.toSingleValueMap(), remote(exchange))
        setBrowserCookie(start, exchange)
        return ResponseEntity.status(HttpStatus.FOUND).location(URI("/oauth/connect.html?id=${start.id}")).build()
    }

    @GetMapping("/oauth/manage")
    suspend fun manage(exchange: ServerWebExchange): ResponseEntity<Void> {
        val start = oauth.manage(remote(exchange))
        setBrowserCookie(start, exchange)
        return ResponseEntity.status(HttpStatus.FOUND).location(URI("/oauth/connect.html?id=${start.id}&manage=1")).build()
    }
    @PostMapping("/oauth/connections")
    suspend fun browserConnections(@RequestBody body: BrowserInput, exchange: ServerWebExchange) =
        oauth.browserConnections(body.id, browserSecret(body.id, exchange))
    @PostMapping("/oauth/disconnect")
    suspend fun browserDisconnect(@RequestBody body: BrowserInput, exchange: ServerWebExchange): Map<String, Boolean> {
        oauth.browserDisconnect(body.id, browserSecret(body.id, exchange), body.connectionId)
        return mapOf("disconnected" to true)
    }

    @PostMapping("/oauth/email-code")
    suspend fun emailCode(@RequestBody body: BrowserInput, exchange: ServerWebExchange): Map<String, Boolean> {
        val secret = browserSecret(body.id, exchange)
        require(body.email.length <= 254)
        return mapOf("sent" to oauth.sendCode(body.id, secret, body.email, remote(exchange)))
    }
    @PostMapping("/oauth/verify")
    suspend fun verify(@RequestBody body: BrowserInput, exchange: ServerWebExchange): Map<String, Boolean> =
        mapOf("verified" to oauth.verify(body.id, browserSecret(body.id, exchange), body.code))

    @PostMapping("/oauth/consent")
    suspend fun consent(@RequestBody body: BrowserInput, exchange: ServerWebExchange): Map<String, String> =
        mapOf("redirect" to oauth.consent(body.id, browserSecret(body.id, exchange), body.allow))

    @PostMapping("/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun token(exchange: ServerWebExchange): ResponseEntity<Any> {
        val parameters = exchange.formData.awaitSingle()
        require(parameters.values.all { it.size == 1 })
        val result = oauth.token(parameters.toSingleValueMap())
        return if (result.tokens != null) ResponseEntity.ok().body(result.tokens)
            else ResponseEntity.badRequest().body(mapOf("error" to result.error))
    }
    @PostMapping("/oauth/revoke", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun revoke(exchange: ServerWebExchange): ResponseEntity<Void> {
        val form = exchange.formData.awaitSingle()
        require(form.values.all { it.size == 1 })
        oauth.revoke(form.getFirst("token").orEmpty(), form.getFirst("client_id").orEmpty())
        return ResponseEntity.ok().build()
    }

    @GetMapping("/api/v1/me/mcp-connections")
    suspend fun connections(authentication: Authentication) = oauth.connections(authentication.principalOrThrow().userId)
    @DeleteMapping("/api/v1/me/mcp-connections/{id}")
    suspend fun disconnect(@PathVariable id: String, authentication: Authentication): ResponseEntity<Void> {
        oauth.disconnect(authentication.principalOrThrow().userId, id)
        return ResponseEntity.noContent().build()
    }
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest() = ResponseEntity.badRequest().body(mapOf("error" to "invalid_request"))

    private fun setBrowserCookie(start: McpOAuthStart, exchange: ServerWebExchange) {
        exchange.response.addCookie(ResponseCookie.from(cookieName(start.id), start.browserSecret)
            .httpOnly(true).secure(true).sameSite("Lax").path("/oauth").maxAge(Duration.ofMinutes(10)).build())
    }
    private fun browserSecret(id: String, exchange: ServerWebExchange): String {
        require(exchange.request.headers.origin == issuer)
        require(exchange.request.headers.getFirst("X-OAuth-Intent") == "connect")
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        return exchange.request.cookies.getFirst(cookieName(id))?.value ?: throw IllegalArgumentException()
    }
    // Opt in only behind the backend gateway, which overwrites X-Real-IP and does not expose port 8080.
    private fun remote(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Real-IP")
        if (trustProxy && forwarded != null && forwarded.length <= 64 && forwarded.matches(Regex("[0-9a-fA-F:.]+"))) return forwarded
        return exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
    }
    private fun cookieName(id: String) = "__Secure-bs-oauth-$id"
    data class BrowserInput(var id: String = "", var email: String = "", var code: String = "", var allow: Boolean = false, var connectionId: String = "")
}
