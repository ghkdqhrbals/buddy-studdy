package com.buddystuddy.backend.common.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest

object ClientIpResolver {
    fun resolve(request: HttpServletRequest): String {
        forwardedHeaderValue(request, "CF-Connecting-IP")?.let { return it }
        forwardedHeaderValue(request, "X-Forwarded-For")
            ?.substringBefore(",")
            ?.trim()
            ?.takeIfValid()
            ?.let { return it }
        forwardedHeaderValue(request, "X-Real-IP")?.let { return it }
        forwardedFor(request)?.let { return it }
        return request.remoteAddr?.takeIfValid() ?: "unknown"
    }

    private fun forwardedHeaderValue(request: HttpServletRequest, name: String): String? =
        request.getHeader(name)?.trim()?.takeIfValid()

    private fun forwardedFor(request: HttpServletRequest): String? =
        request.getHeader("Forwarded")
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
