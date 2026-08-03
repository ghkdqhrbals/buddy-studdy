package com.buddystudy.backend.common.adapter.inbound.web

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.support.StaticMessageSource
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.MethodNotAllowedException
import reactor.netty.channel.AbortedException
import java.net.URI
import java.util.Locale

@ExtendWith(OutputCaptureExtension::class)
class ErrorHandlerTest {
    private val messageSource = StaticMessageSource().apply {
        addMessage("error.record.not_found", Locale.ENGLISH, "Record was not found.")
        addMessage("error.record.not_found", Locale.KOREAN, "기록을 찾을 수 없습니다.")
        addMessage("error.record.not_found", Locale.KOREA, "기록을 찾을 수 없습니다.")
        addMessage("error.resource.not_found", Locale.ENGLISH, "Resource was not found.")
        addMessage("error.internal.server_error", Locale.ENGLISH, "Internal backend error.")
        addMessage("error.internal.server_error", Locale.US, "Internal backend error.")
        addMessage("error.internal.server_error", Locale.KOREAN, "Internal backend error.")
        addMessage("error.internal.server_error", Locale.KOREA, "Internal backend error.")
        addMessage("error.internal.server_error", Locale.KOREAN, "Internal backend error.")
        addMessage("error.validation", Locale.ENGLISH, "Invalid request.")
        addMessage("error.request.method_not_allowed", Locale.ENGLISH, "Request method is not supported.")
        addMessage("error.server.busy", Locale.ENGLISH, "Server is temporarily busy.")
    }
    private val responseFactory = ApiErrorResponseFactory(messageSource)
    private val handler = ErrorHandler(responseFactory, ApiLoggingPolicy("detailed"))
    private val compactHandler = ErrorHandler(responseFactory, ApiLoggingPolicy("compact"))
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    @Test
    fun `fallback internal server error response includes reason`(): Unit = runBlocking {
        val exchange = exchange("GET", "/api/v1/records", "req-1")

        val response = handler.fallback(IllegalStateException("database unavailable"), exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.body)
        assertThat(json["error"]["errorCode"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.name)
        assertThat(json["error"]["code"].asInt()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.code)
        assertThat(json["error"]["messageKey"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.messageKey)
        assertThat(json["error"]["debugDescription"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.debugDescription)
        assertThat(json["error"]["message"].asText()).isEqualTo("Internal backend error.")
        assertThat(json["error"]["requestId"].asText()).isEqualTo("req-1")
        assertThat(json["error"]["reason"].asText()).isEqualTo("IllegalStateException: database unavailable")
    }

    @Test
    fun `fallback internal server error logs exception stack trace`(output: CapturedOutput) = runBlocking {
        val exchange = exchange(
            method = "POST",
            path = "/api/v1/devices/register",
            requestId = "req-stack",
            forwardedFor = "203.0.113.10",
        )

        handler.fallback(IllegalStateException("jwt init failed"), exchange)

        assertThat(output.all).contains("api_error requestId=req-stack")
        assertThat(output.all).contains("clientIp=203.0.113.10")
        assertThat(output.all).contains("path=/api/v1/devices/register")
        assertThat(output.all).contains("java.lang.IllegalStateException: jwt init failed")
    }

    @Test
    fun `nested linkage root cause is logged with searchable cause and origin`(output: CapturedOutput) = runBlocking {
        val exchange = exchange(
            method = "POST",
            path = "/api/v1/studies/2/questions",
            requestId = "req-linkage",
        )
        val rootCause = ExceptionInInitializerError("jOOQ SQLDataType initialization failed").apply {
            stackTrace = arrayOf(StackTraceElement("org.jooq.impl.DSL", "using", "DSL.java", 918))
        }
        val error = NoClassDefFoundError("Could not initialize class org.jooq.impl.DefaultDSLContext").apply {
            initCause(rootCause)
        }
        val translated = IllegalStateException("jOOQ runtime initialization failed.", error)

        val response = handler.fallback(translated, exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.body)
        assertThat(json["error"]["requestId"].asText()).isEqualTo("req-linkage")
        assertThat(json["error"]["reason"].asText())
            .contains("IllegalStateException: jOOQ runtime initialization failed.")
            .contains("ExceptionInInitializerError: jOOQ SQLDataType initialization failed")
        assertThat(output.all).contains("requestId=req-linkage")
        assertThat(output.all).contains("path=/api/v1/studies/2/questions")
        assertThat(output.all).contains("exceptionType=IllegalStateException")
        assertThat(output.all).contains("rootCauseType=ExceptionInInitializerError")
        assertThat(output.all).contains("origin=org.jooq.impl.DSL.using(DSL.java:918)")
    }

    @Test
    fun `compact error log omits request identity and full stack trace`(output: CapturedOutput) = runBlocking {
        val exchange = exchange(
            method = "POST",
            path = "/api/v1/devices/register",
            requestId = "req-compact",
            forwardedFor = "203.0.113.10",
        )
        val error = IllegalStateException("jwt init failed")

        compactHandler.fallback(error, exchange)

        assertThat(output.all).contains(
            "api_error method=POST path=/api/v1/devices/register status=500 code=INTERNAL_SERVER_ERROR",
        )
        assertThat(output.all).contains("cause=IllegalStateException:jwt init failed")
        assertThat(output.all).doesNotContain("requestId=req-compact")
        assertThat(output.all).doesNotContain("clientIp=203.0.113.10")
        assertThat(output.all).doesNotContain("\tat ")
    }

    @Test
    fun `client disconnect bypasses internal server error handling`(output: CapturedOutput) {
        val exchange = exchange("GET", "/api/v1/studies", "req-disconnected")
        val error = AbortedException(IllegalStateException("connection closed"))

        assertThatThrownBy { handler.fallback(error, exchange) }
            .isSameAs(error)
        assertThat(output.all).doesNotContain("api_error")
    }

    @Test
    fun `api runtime exception response uses request locale and omits reason`(): Unit = runBlocking {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/records")
                .header("Accept-Language", "ko-KR")
                .build()
        ).also { it.attributes[RequestLoggingFilter.REQUEST_ID_ATTRIBUTE] = "req-2" }

        val response = handler.api(ApiRuntimeException(ApiErrorCode.RECORD_NOT_FOUND), exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"errorCode\":\"RECORD_NOT_FOUND\"")
        assertThat(serialized).contains("\"message\":\"기록을 찾을 수 없습니다.\"")
        assertThat(serialized).doesNotContain("reason")
    }

    @Test
    fun `not found response is json`(): Unit = runBlocking {
        val exchange = exchange("GET", "/missing", "req-3")
        val error = NoResourceFoundException(URI.create("/missing"), "missing")

        val response = handler.notFound(error, exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"errorCode\":\"RESOURCE_NOT_FOUND\"")
    }

    @Test
    fun `unsupported request method remains 405 without an error log`(output: CapturedOutput): Unit = runBlocking {
        val exchange = exchange("GET", "/api/v1/admin/users/733/notifications", "req-method")
        val error = MethodNotAllowedException(HttpMethod.GET, listOf(HttpMethod.POST))

        val response = handler.methodNotAllowed(error, exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.headers.allow).containsExactly(HttpMethod.POST)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.body)
        assertThat(json["error"]["errorCode"].asText()).isEqualTo(ApiErrorCode.METHOD_NOT_ALLOWED.name)
        assertThat(json["error"]["code"].asInt()).isEqualTo(ApiErrorCode.METHOD_NOT_ALLOWED.code)
        assertThat(json["error"]["message"].asText()).isEqualTo("Request method is not supported.")
        assertThat(output.all).doesNotContain("api_error")
    }

    @Test
    fun `blocking executor saturation returns service unavailable`(): Unit = runBlocking {
        val exchange = exchange("POST", "/api/v1/study", "req-busy")

        val response = handler.serverBusy(TaskRejectedException("blocking queue full"), exchange)

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"errorCode\":\"SERVER_BUSY\"")
        assertThat(serialized).contains("\"code\":902")
        assertThat(serialized).contains("\"message\":\"Server is temporarily busy.\"")
    }

    @Test
    fun `error code numeric ranges are reserved by category`(): Unit = runBlocking {
        assertThat(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN.code).isBetween(100, 199)
        assertThat(ApiErrorCode.OPENAI_API_KEY_MISSING.code).isBetween(200, 299)
        assertThat(ApiErrorCode.PERMISSION_DENIED.code).isBetween(300, 399)
        assertThat(ApiErrorCode.RECORD_NOT_FOUND.code).isBetween(400, 499)
        assertThat(ApiErrorCode.VALIDATION_ERROR.code).isBetween(500, 599)
        assertThat(ApiErrorCode.INTERNAL_SERVER_ERROR.code).isBetween(900, 999)
    }

    private fun exchange(
        method: String,
        path: String,
        requestId: String,
        forwardedFor: String? = null,
    ): MockServerWebExchange {
        val builder = MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
        forwardedFor?.let { builder.header("X-Forwarded-For", it) }
        return MockServerWebExchange.from(builder.build()).also {
            it.attributes[RequestLoggingFilter.REQUEST_ID_ATTRIBUTE] = requestId
        }
    }

}
