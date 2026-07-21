package com.buddystudy.backend.config

import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ClientIpResolver
import com.buddystudy.backend.common.adapter.inbound.web.ReactiveRequestDetails
import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.util.UUID

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper::class)
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    @Bean
    fun kotlinJacksonModule(): KotlinModule = KotlinModule.Builder().build()

    @Bean
    fun javaTimeJacksonModule(): JavaTimeModule = JavaTimeModule()

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        bearerTokenFilter: BearerTokenFilter,
        objectMapper: ObjectMapper,
        errorResponseFactory: ApiErrorResponseFactory,
    ): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges.matchers(ServerWebExchangeMatchers.pathMatchers("/health", "/health/**")).permitAll()
                exchanges.matchers(ServerWebExchangeMatchers.pathMatchers("/actuator/**")).permitAll()
                AnonymousRoutes.routes.forEach { route ->
                    val matcher = if (route.method == null) {
                        ServerWebExchangeMatchers.pathMatchers(route.pattern)
                    } else {
                        ServerWebExchangeMatchers.pathMatchers(route.method, route.pattern)
                    }
                    exchanges.matchers(matcher).permitAll()
                }
                exchanges.pathMatchers("/api/**").authenticated()
                exchanges.anyExchange().permitAll()
            }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { exchange, _ ->
                    writeSecurityError(
                        objectMapper = objectMapper,
                        exchange = exchange,
                        status = HttpStatus.UNAUTHORIZED,
                        code = ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED,
                        errorResponseFactory = errorResponseFactory,
                    )
                }
            }
            .addFilterAt(bearerTokenFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
}

@Component
class BearerTokenFilter(
    private val tokenProvider: TokenProvider,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val objectMapper: ObjectMapper,
    private val errorResponseFactory: ApiErrorResponseFactory,
    @param:Qualifier("webFluxBlockingScheduler") private val blockingScheduler: Scheduler,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val authorization = exchange.request.headers.getFirst("Authorization")
        val filtered = if (authorization.isNullOrBlank()) {
            chain.filter(exchange)
        } else {
            authentication(exchange.request, authorization)
                .flatMap { authentication ->
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                }
        }
        return filtered.onErrorResume(ApiRuntimeException::class.java) { error ->
            if (AnonymousRoutes.matches(exchange.request) || NonApiRoutes.matches(exchange.request)) {
                logIgnoredAuthenticationFailure(exchange, error)
                chain.filter(exchange)
            } else {
                writeSecurityError(
                    objectMapper = objectMapper,
                    exchange = exchange,
                    status = error.status,
                    code = error.errorCode,
                    errorResponseFactory = errorResponseFactory,
                    requiredPermissions = error.requiredPermissions,
                )
            }
        }
    }

    private fun authentication(request: ServerHttpRequest, authorization: String): Mono<Authentication> =
        Mono.fromCallable { authenticateBlocking(request, authorization) }
            .subscribeOn(blockingScheduler)

    private fun authenticateBlocking(request: ServerHttpRequest, authorization: String): Authentication {
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
        return UsernamePasswordAuthenticationToken(
            authenticatedPrincipal,
            null,
            emptyList(),
        ).apply {
            details = ReactiveRequestDetails(request.headers.getFirst("X-App-Version"))
        }
    }
}

private object NonApiRoutes {
    fun matches(request: ServerHttpRequest): Boolean =
        !request.path.value().startsWith("/api")
}

private object AnonymousRoutes {
    val routes = listOf(
        Route(null, "/api/v1/health"),
        Route(null, "/api/v1/health/**"),
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

    fun matches(request: ServerHttpRequest): Boolean =
        routes.any { it.matches(request) }

    data class Route(val method: HttpMethod?, val pattern: String) {
        fun matches(request: ServerHttpRequest): Boolean =
            (method == null || request.method == method) && matchesPattern(request.path.value())

        private fun matchesPattern(path: String): Boolean {
            if (pattern.endsWith("/**")) {
                val prefix = pattern.removeSuffix("/**")
                return path == prefix || path.startsWith("$prefix/")
            }
            return path == pattern
        }
    }
}

private fun logIgnoredAuthenticationFailure(exchange: ServerWebExchange, error: ApiRuntimeException) {
    val request = exchange.request
    val requestId = exchange.getAttribute<String>(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE)
        ?: UUID.randomUUID().toString()
    securityLog.debug(
        "api_auth_ignored requestId={} clientIp={} method={} path={} status={} code={} message={}",
        requestId,
        ClientIpResolver.resolve(request),
        request.method,
        request.path.value(),
        error.status.value(),
        error.errorCode.name,
        error.message,
    )
}

private fun writeSecurityError(
    objectMapper: ObjectMapper,
    exchange: ServerWebExchange,
    status: HttpStatus,
    code: ApiErrorCode,
    errorResponseFactory: ApiErrorResponseFactory,
    requiredPermissions: List<String>? = null,
): Mono<Void> {
    val response = exchange.response
    if (response.isCommitted) return Mono.empty()
    val body = errorResponseFactory.envelope(
        code = code,
        status = status,
        exchange = exchange,
        requiredPermissions = requiredPermissions,
    )
    securityLog.warn(
        "api_auth_failed requestId={} clientIp={} method={} path={} status={} code={} message={}",
        body.error.requestId,
        ClientIpResolver.resolve(exchange.request),
        exchange.request.method,
        exchange.request.path.value(),
        status.value(),
        code.name,
        body.error.message,
    )
    val bytes = objectMapper.writeValueAsBytes(body)
    response.statusCode = status
    response.headers.contentType = MediaType.APPLICATION_JSON
    response.headers.contentLength = bytes.size.toLong()
    return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
}

private val securityLog = LoggerFactory.getLogger("com.buddystudy.backend.security")
