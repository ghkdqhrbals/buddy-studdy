package com.buddystuddy.backend.common.adapter.inbound.web

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@ExtendWith(OutputCaptureExtension::class)
class RequestLoggingFilterTest {
    private val filter = RequestLoggingFilter()

    @Test
    fun `response body is preserved after content caching logging`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me/study")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.contentType = "application/json"
            servletResponse.writer.write("""{"ok":true}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
        assertThat(response.contentAsString).isEqualTo("""{"ok":true}""")
    }

    @Test
    fun `request body is still available to downstream handlers`() {
        val request = MockHttpServletRequest("PUT", "/api/v1/me/schedule")
        request.contentType = "application/json"
        request.setContent(
            """
                {"openaiApiKey":"sk-secret","schedules":[{"topic":"Swift"}]}
            """.trimIndent().toByteArray()
        )
        val response = MockHttpServletResponse()
        val chain = FilterChain { servletRequest, servletResponse ->
            val body = servletRequest.inputStream.readBytes().decodeToString()
            assertThat(body).contains("sk-secret")
            servletResponse.writer.write("""{"saved":true}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(response.contentAsString).isEqualTo("""{"saved":true}""")
    }

    @Test
    fun `api request and response are logged in a single exchange line`(output: CapturedOutput) {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/google")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer access-token")
        request.setContent("""{"idToken":"google-id-token"}""".toByteArray())
        val response = MockHttpServletResponse()
        val chain = FilterChain { servletRequest, servletResponse ->
            servletRequest.inputStream.readBytes()
            servletResponse.contentType = "application/json"
            servletResponse.writer.write("""{"accessToken":"app-token"}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(output.out).contains("api_exchange")
        assertThat(output.out).contains("\"request\":{")
        assertThat(output.out).contains("\"response\":{")
        assertThat(output.out).contains("\"path\":\"/api/v1/auth/google\"")
        assertThat(output.out).contains("\"Authorization\":\"Bearer access-token\"")
        assertThat(output.out).contains("\\\"idToken\\\":\\\"[REDACTED]\\\"")
        assertThat(output.out).contains("\\\"accessToken\\\":\\\"[REDACTED]\\\"")
        assertThat(output.out).doesNotContain("api_request")
        assertThat(output.out).doesNotContain("api_response {\"requestId\"")
    }

    @Test
    fun `json response body without charset is logged as utf8`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/study")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.contentType = "application/json"
            servletResponse.outputStream.write("""{"customPrompt":"짧고 명확하게"}""".toByteArray(Charsets.UTF_8))
        }

        filter.doFilter(request, response, chain)

        assertThat(response.contentAsByteArray.decodeToString()).contains("짧고 명확하게")
        assertThat(output.out).contains("짧고 명확하게")
        assertThat(output.out).doesNotContain("ì§§")
    }

    private fun interface FilterChain : jakarta.servlet.FilterChain {
        fun doFilterInternal(request: ServletRequest, response: ServletResponse)

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            doFilterInternal(request, response)
        }
    }
}
