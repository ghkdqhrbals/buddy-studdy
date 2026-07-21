package com.buddystudy.backend.common.adapter.inbound.web

import org.springframework.http.server.reactive.ServerHttpRequest

object ClientIpResolver {
    fun resolve(request: ServerHttpRequest): String {
        forwardedHeaderValue(request, "CF-Connecting-IP")?.let { return it }
        forwardedHeaderValue(request, "X-Forwarded-For")
            ?.substringBefore(",")
            ?.trim()
            ?.takeIfValid()
            ?.let { return it }
        forwardedHeaderValue(request, "X-Real-IP")?.let { return it }
        forwardedFor(request)?.let { return it }
        return request.remoteAddress?.address?.hostAddress?.takeIfValid() ?: "unknown"
    }

    private fun forwardedHeaderValue(request: ServerHttpRequest, name: String): String? =
        request.headers.getFirst(name)?.trim()?.takeIfValid()

    private fun forwardedFor(request: ServerHttpRequest): String? =
        request.headers.getFirst("Forwarded")
            ?.split(";")
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("for=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"')
            ?.trim('[', ']')
            ?.takeIfValid()

    private fun String.takeIfValid(): String? =
        takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
}
