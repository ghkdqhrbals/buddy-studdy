package com.buddystuddy.backend.config

import com.buddystuddy.backend.auth.TokenProvider
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.common.adapter.inbound.web.ApiError
import com.buddystuddy.backend.common.adapter.inbound.web.ApiErrorEnvelope
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Configuration
class SecurityConfig {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper::class)
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    @Bean
    fun securityFilterChain(http: HttpSecurity, bearerTokenFilter: BearerTokenFilter, objectMapper: ObjectMapper): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/health", "/api/v1/health").permitAll()
                it.requestMatchers("/actuator/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/openai/models").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/devices/register").permitAll()
                it.requestMatchers("/api/v1/auth/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    writeSecurityError(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED,
                        "Access token is required.",
                    )
                }
            }
            .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
}

@Component
class BearerTokenFilter(
    private val tokenProvider: TokenProvider,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        SecurityContextHolder.clearContext()
        try {
            authenticate(request)
            filterChain.doFilter(request, response)
        } catch (error: ApiException) {
            writeSecurityError(objectMapper, request, response, error.status, error.code, error.message)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun authenticate(request: HttpServletRequest) {
        val authorization = request.getHeader("Authorization")
        if (authorization.isNullOrBlank()) return
        if (!authorization.startsWith("Bearer ")) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid access token.")
        }

        val principal = tokenProvider.parse(authorization.removePrefix("Bearer ").trim())
        val session = userDevices.findByIdAndUserId(principal.sessionId, principal.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Access token principal is no longer valid.")
        if (session.deviceId != principal.deviceId) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_DEVICE_MISMATCH, "Access token device is no longer valid.")
        }

        val authenticatedPrincipal = principal.copy(
            anonymous = devices.findByDeviceId(principal.deviceId)?.userId == null || principal.anonymous,
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            authenticatedPrincipal,
            null,
            emptyList(),
        )
    }

}

private fun writeSecurityError(
    objectMapper: ObjectMapper,
    request: HttpServletRequest,
    response: HttpServletResponse,
    status: HttpStatus,
    code: ApiErrorCode,
    message: String,
) {
    if (response.isCommitted) return
    val requestId = request.getAttribute("requestId") as? String ?: UUID.randomUUID().toString()
    response.status = status.value()
    response.contentType = "application/json"
    objectMapper.writeValue(response.outputStream, ApiErrorEnvelope(ApiError(code.name, message, requestId, status.value())))
}
