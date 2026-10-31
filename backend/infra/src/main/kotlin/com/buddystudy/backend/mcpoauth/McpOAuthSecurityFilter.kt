package com.buddystudy.backend.mcpoauth

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class McpOAuthSecurityFilter(
    @Value("\${buddystudy.mcp.oauth.enabled:false}") private val enabled: Boolean,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val segments = exchange.request.path.pathWithinApplication().elements()
            .filterIsInstance<PathContainer.PathSegment>().map { it.valueToMatch() }
        val oauthPath = segments.firstOrNull() == "oauth"
        val metadataPath = segments.firstOrNull() == ".well-known" && segments.getOrNull(1) in setOf("oauth-protected-resource", "oauth-authorization-server")
        if (!oauthPath && !metadataPath) return chain.filter(exchange)
        exchange.response.headers.set("Cache-Control", "no-store")
        exchange.response.headers.set("Pragma", "no-cache")
        exchange.response.headers.set("Referrer-Policy", "no-referrer")
        exchange.response.headers.set("X-Frame-Options", "DENY")
        exchange.response.headers.set("Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
        if (!enabled) {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }
}
