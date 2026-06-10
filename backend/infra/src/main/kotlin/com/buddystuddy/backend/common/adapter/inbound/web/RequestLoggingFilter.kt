package com.buddystuddy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestLoggingFilter(
    objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val formatter = ApiExchangeLogFormatter(objectMapper)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val requestId = UUID.randomUUID().toString()
        val requestWrapper = ContentCachingRequestWrapper(request, MAX_BODY_BYTES)
        val responseWrapper = ContentCachingResponseWrapper(response)
        requestWrapper.setAttribute("requestId", requestId)
        val started = System.nanoTime()
        try {
            filterChain.doFilter(requestWrapper, responseWrapper)
        } finally {
            val durationMs = (System.nanoTime() - started) / 1_000_000.0
            if (requestWrapper.requestURI.startsWith("/api/")) {
                log.info(
                    "api_exchange {}",
                    formatter.apiExchangeJson(requestId, requestWrapper, responseWrapper, durationMs),
                )
            } else {
                log.info(
                    "api_response {}",
                    formatter.apiResponseJson(requestId, requestWrapper, responseWrapper, durationMs, includeBody = false),
                )
            }
            responseWrapper.copyBodyToResponse()
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 8_192
    }
}
