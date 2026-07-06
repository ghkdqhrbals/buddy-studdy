package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

@ExtendWith(OutputCaptureExtension::class)
class ErrorHandlerTest {
    private val handler = ErrorHandler()
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    @Test
    fun `fallback internal server error response includes reason`() {
        val request = MockHttpServletRequest("GET", "/api/v1/records").apply {
            setAttribute("requestId", "req-1")
        }

        val response = handler.fallback(IllegalStateException("database unavailable"), request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.body)
        assertThat(json["error"]["errorCode"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.name)
        assertThat(json["error"]["code"].asInt()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.code())
        assertThat(json["error"]["description"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.description())
        assertThat(json["error"]["showPopup"].asBoolean()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.showPopup)
        assertThat(json["error"]["message"].asText()).isEqualTo("Internal backend error.")
        assertThat(json["error"]["requestId"].asText()).isEqualTo("req-1")
        assertThat(json["error"]["reason"].asText()).isEqualTo("IllegalStateException: database unavailable")
    }

    @Test
    fun `fallback internal server error logs exception stack trace`(output: CapturedOutput) {
        val request = MockHttpServletRequest("POST", "/api/v1/devices/register").apply {
            setAttribute("requestId", "req-stack")
            addHeader("X-Forwarded-For", "203.0.113.10")
        }

        handler.fallback(IllegalStateException("jwt init failed"), request)

        assertThat(output.all).contains("api_error requestId=req-stack")
        assertThat(output.all).contains("clientIp=203.0.113.10")
        assertThat(output.all).contains("path=/api/v1/devices/register")
        assertThat(output.all).contains("java.lang.IllegalStateException: jwt init failed")
    }

    @Test
    fun `api exception response omits reason`() {
        val request = MockHttpServletRequest("GET", "/api/v1/records").apply {
            setAttribute("requestId", "req-2")
        }

        val response = handler.api(
            ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"errorCode\":\"RECORD_NOT_FOUND\"")
        assertThat(serialized).contains("\"code\":${ApiErrorCode.RECORD_NOT_FOUND.code()}")
        assertThat(serialized).contains("\"description\":\"${ApiErrorCode.RECORD_NOT_FOUND.description()}\"")
        assertThat(serialized).contains("\"showPopup\":${ApiErrorCode.RECORD_NOT_FOUND.showPopup}")
        assertThat(serialized).doesNotContain("reason")
    }

    @Test
    fun `not found response is json`() {
        val request = MockHttpServletRequest("GET", "/missing").apply {
            setAttribute("requestId", "req-3")
        }

        val response = handler.notFound(NoSuchElementException("missing"), request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"errorCode\":\"RESOURCE_NOT_FOUND\"")
        assertThat(serialized).contains("\"code\":${ApiErrorCode.RESOURCE_NOT_FOUND.code()}")
    }

    @Test
    fun `error code numeric ranges are reserved by category`() {
        assertThat(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN.code()).isBetween(100, 199)
        assertThat(ApiErrorCode.OPENAI_API_KEY_MISSING.code()).isBetween(200, 299)
        assertThat(ApiErrorCode.PERMISSION_DENIED.code()).isBetween(300, 399)
        assertThat(ApiErrorCode.RECORD_NOT_FOUND.code()).isBetween(400, 499)
        assertThat(ApiErrorCode.VALIDATION_ERROR.code()).isBetween(500, 599)
        assertThat(ApiErrorCode.INTERNAL_SERVER_ERROR.code()).isBetween(900, 999)
    }
}
