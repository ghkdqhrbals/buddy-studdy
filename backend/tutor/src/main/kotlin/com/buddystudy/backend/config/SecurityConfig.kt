package com.buddystudy.backend.config

import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ClientIpResolver
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.util.matcher.RequestMatcher
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
    fun kotlinJacksonModule(): KotlinModule = KotlinModule.Builder().build()

    @Bean
    fun javaTimeJacksonModule(): JavaTimeModule = JavaTimeModule()

    @Bean
    fun userDetailsService(): UserDetailsService = UserDetailsService {
        throw UsernameNotFoundException("BuddyStudy uses bearer token authentication only.")
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        bearerTokenFilter: BearerTokenFilter,
        objectMapper: ObjectMapper,
        errorResponseFactory: ApiErrorResponseFactory,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(NonApiRoutes.requestMatcher).permitAll()
                AnonymousRoutes.requestMatchers.forEach { matcher -> it.requestMatchers(matcher).permitAll() }
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
                        errorResponseFactory,
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
    private val errorResponseFactory: ApiErrorResponseFactory,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        SecurityContextHolder.clearContext()
        try {
            authenticate(request)
            filterChain.doFilter(request, response)
        } catch (error: ApiRuntimeException) {
            if (AnonymousRoutes.matches(request) || NonApiRoutes.matches(request)) {
                SecurityContextHolder.clearContext()
                logIgnoredAuthenticationFailure(request, error)
                filterChain.doFilter(request, response)
            } else {
                writeSecurityError(
                    objectMapper,
                    request,
                    response,
                    error.status,
                    error.errorCode,
                    errorResponseFactory,
                    requiredPermissions = error.requiredPermissions,
                )
            }
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

        val rawToken = authorization.removePrefix("Bearer ").trim()
        if (!tokenProvider.validate(rawToken)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid access token.")
        }

        val principal = tokenProvider.parse(rawToken)
        val session = userDevices.findByIdAndUserId(principal.sessionId, principal.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Access token principal is no longer valid.")
        if (!session.isActive()) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Access token session is no longer active.")
        }
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

private object NonApiRoutes {
    val requestMatcher: RequestMatcher = RequestMatcher { request -> matches(request) }

    fun matches(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api")
}

private object AnonymousRoutes {
    private val routes = listOf(
        Route(null, "/health"),
        Route(null, "/health/**"),
        Route(null, "/api/v1/health"),
        Route(null, "/api/v1/health/**"),
        Route(null, "/actuator/**"),
        Route(HttpMethod.GET, "/docs"),
        Route(HttpMethod.GET, "/docs/**"),
        Route(HttpMethod.GET, "/swagger-ui.html"),
        Route(HttpMethod.GET, "/swagger-ui/**"),
        Route(HttpMethod.GET, "/openapi.json"),
        Route(HttpMethod.GET, "/v3/api-docs/**"),
        Route(HttpMethod.POST, "/api/v1/devices/register"),
        Route(null, "/api/v1/auth/**"),
        Route(HttpMethod.GET, "/api/v1/openai/models"),
        Route(HttpMethod.GET, "/api/v1/public/**"),
        Route(HttpMethod.GET, "/api/v2/public/**"),
        Route(null, "/api/v1/admin/**"),
    )

    val requestMatchers: Array<RequestMatcher> = routes.map { route ->
        RequestMatcher { request -> route.matches(request) }
    }.toTypedArray()

    fun matches(request: HttpServletRequest): Boolean =
        routes.any { it.matches(request) }

    private data class Route(val method: HttpMethod?, val pattern: String) {
        fun matches(request: HttpServletRequest): Boolean =
            (method == null || request.method == method.name()) && matchesPattern(request.requestURI)

        private fun matchesPattern(path: String): Boolean {
            if (pattern.endsWith("/**")) {
                val prefix = pattern.removeSuffix("/**")
                return path == prefix || path.startsWith("$prefix/")
            }
            return path == pattern
        }
    }
}

private fun logIgnoredAuthenticationFailure(request: HttpServletRequest, error: ApiRuntimeException) {
    val requestId = request.getAttribute("requestId") as? String ?: UUID.randomUUID().toString()
    securityLog.debug(
        "api_auth_ignored requestId={} clientIp={} method={} path={} status={} code={} message={}",
        requestId,
        ClientIpResolver.resolve(request),
        request.method,
        request.requestURI,
        error.status.value(),
        error.errorCode.name,
        error.message,
    )
}

private fun writeSecurityError(
    objectMapper: ObjectMapper,
    request: HttpServletRequest,
    response: HttpServletResponse,
    status: HttpStatus,
    code: ApiErrorCode,
    errorResponseFactory: ApiErrorResponseFactory,
    requiredPermissions: List<String>? = null,
) {
    if (response.isCommitted) return
    val body = errorResponseFactory.envelope(
        code = code,
        status = status,
        request = request,
        requiredPermissions = requiredPermissions,
    )
    securityLog.warn(
        "api_auth_failed requestId={} clientIp={} method={} path={} status={} code={} message={}",
        body.error.requestId,
        ClientIpResolver.resolve(request),
        request.method,
        request.requestURI,
        status.value(),
        code.name,
        body.error.message,
    )
    response.status = status.value()
    response.contentType = "application/json"
    objectMapper.writeValue(
        response.outputStream,
        body,
    )
}

private val securityLog = LoggerFactory.getLogger("com.buddystudy.backend.security")
