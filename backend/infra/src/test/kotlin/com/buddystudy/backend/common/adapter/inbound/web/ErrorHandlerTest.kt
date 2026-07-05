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
        assertThat(json["error"]["code"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.name)
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
        assertThat(serialized).contains("\"code\":\"RECORD_NOT_FOUND\"")
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
        assertThat(serialized).contains("\"code\":\"RESOURCE_NOT_FOUND\"")
    }
}
