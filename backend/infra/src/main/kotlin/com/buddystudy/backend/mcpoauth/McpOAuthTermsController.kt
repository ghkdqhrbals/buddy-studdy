package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

interface McpOAuthTermsWebPort {
    suspend fun pending(id: String, secret: String): List<TermsResponse>
    suspend fun agree(id: String, secret: String, agreements: List<OAuthTermsIdentity>, ip: String?, userAgent: String?): List<TermsResponse>
}

data class OAuthTermsIdentity(var code: String = "", var version: String = "", var contentHash: String = "", var agreed: Boolean = false)
data class OAuthTermsInput(var id: String = "", var agreements: List<OAuthTermsIdentity> = emptyList())

/** Browser-only account policy adapter. MCP learning tokens cannot call this route. */
@Component
class McpOAuthTermsWebAdapter(private val oauth: McpOAuthUseCase, private val terms: TermsUseCase) : McpOAuthTermsWebPort {
    override suspend fun pending(id: String, secret: String) =
        terms.activeTerms(oauth.verifiedBrowserPrincipal(id, secret)).filter { it.required && !it.agreed }

    @Transactional
    override suspend fun agree(id: String, secret: String, agreements: List<OAuthTermsIdentity>, ip: String?, userAgent: String?): List<TermsResponse> {
        val principal = oauth.verifiedBrowserPrincipal(id, secret)
        val active = terms.activeTerms(principal).filter { it.required }
        val byCode = active.associateBy { it.code }
        require(agreements.isNotEmpty() && agreements.size <= 10 && agreements.map { it.code }.toSet().size == agreements.size) { "invalid_request" }
        require(active.filter { !it.agreed }.all { term -> agreements.any { it.code == term.code } }) { "invalid_request" }
        // Validate the entire selection before recording any legal acceptance.
        agreements.forEach { selected ->
            val term = byCode[selected.code]
            require(selected.agreed && term != null && selected.version == term.version && selected.contentHash == term.contentHash) { "invalid_request" }
        }
        agreements.forEach { selected ->
            val term = byCode.getValue(selected.code)
            if (!term.agreed) terms.saveAgreement(principal, TermsAgreementCommand(
                type = term.type, action = "AGREED", source = "REQUIRED_GATE", version = term.version,
                contentHash = term.contentHash, ipAddress = ip, userAgent = userAgent?.take(1000),
            ))
        }
        return pending(id, secret)
    }
}

@RestController
class McpOAuthTermsController(
    private val terms: McpOAuthTermsWebPort,
    @Value("\${buddystudy.mcp.oauth.issuer:https://api.ghkdqhrbals.org}") private val issuer: String,
    @Value("\${buddystudy.mcp.oauth.trust-proxy:false}") private val trustProxy: Boolean = false,
) {
    @PostMapping("/oauth/terms")
    suspend fun pending(@RequestBody body: OAuthTermsInput, exchange: ServerWebExchange) = terms.pending(body.id, secret(body.id, exchange))

    @PostMapping("/oauth/terms/agree")
    suspend fun agree(@RequestBody body: OAuthTermsInput, exchange: ServerWebExchange): List<TermsResponse> {
        val secret = secret(body.id, exchange)
        val forwarded = exchange.request.headers.getFirst("X-Real-IP")
        val ip = if (trustProxy && forwarded != null && forwarded.length <= 64 && forwarded.matches(Regex("[0-9a-fA-F:.]+"))) forwarded
            else exchange.request.remoteAddress?.address?.hostAddress
        return terms.agree(body.id, secret, body.agreements, ip, exchange.request.headers.getFirst(HttpHeaders.USER_AGENT))
    }

    private fun secret(id: String, exchange: ServerWebExchange): String {
        require(exchange.request.headers.origin == issuer && exchange.request.headers.getFirst("X-OAuth-Intent") == "connect")
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        return exchange.request.cookies.getFirst("__Secure-bs-oauth-$id")?.value ?: throw IllegalArgumentException()
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid() = ResponseEntity.badRequest().body(mapOf("error" to "invalid_request"))
}
