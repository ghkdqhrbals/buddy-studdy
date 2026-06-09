package com.buddystuddy.backend.common.adapter.inbound.web

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

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
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.body)
        assertThat(json["error"]["code"].asText()).isEqualTo(ApiErrorCode.INTERNAL_SERVER_ERROR.name)
        assertThat(json["error"]["message"].asText()).isEqualTo("Internal backend error.")
        assertThat(json["error"]["requestId"].asText()).isEqualTo("req-1")
        assertThat(json["error"]["reason"].asText()).isEqualTo("IllegalStateException: database unavailable")
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
        val serialized = mapper.writeValueAsString(response.body)
        assertThat(serialized).contains("\"code\":\"RECORD_NOT_FOUND\"")
        assertThat(serialized).doesNotContain("reason")
    }
}
