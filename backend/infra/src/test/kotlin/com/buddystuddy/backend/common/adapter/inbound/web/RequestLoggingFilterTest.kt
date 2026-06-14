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
        val request = MockHttpServletRequest("GET", "/api/v1/studies")
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
        val request = MockHttpServletRequest("PUT", "/api/v1/settings")
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
        request.addHeader("CF-Connecting-IP", "203.0.113.10")
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
        assertThat(output.out).contains("\"clientIp\":\"203.0.113.10\"")
        assertThat(output.out).contains("\"path\":\"/api/v1/auth/google\"")
        assertThat(output.out).contains("\"Authorization\":\"Bearer access-token\"")
        assertThat(output.out).contains("\"body\":{\"idToken\":\"[REDACTED]\"}")
        assertThat(output.out).contains("\"body\":{\"accessToken\":\"[REDACTED]\"}")
        assertThat(output.out).doesNotContain("api_request")
        assertThat(output.out).doesNotContain("api_response {\"requestId\"")
    }

    @Test
    fun `json response body without charset is logged as utf8`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/studies")
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

    @Test
    fun `json response body is logged as nested json without escaped quotes`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/records")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.contentType = "application/json"
            servletResponse.writer.write("""{"records":[{"id":1,"question":"Swift?"}]}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(output.out).contains("\"body\":{\"records\":[{\"id\":1,\"question\":\"Swift?\"}]}")
        assertThat(output.out).doesNotContain("\"body\":\"{\\\"records\\\"")
    }

    @Test
    fun `json-like response body without content type is logged as nested json`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/records")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.writer.write("""{"records":[{"id":1}]}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(output.out).contains("\"body\":{\"records\":[{\"id\":1}]}")
        assertThat(output.out).doesNotContain("\"body\":\"{\\\"records\\\"")
    }

    @Test
    fun `x forwarded for first address is logged as client ip`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/records")
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.2")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            servletResponse.writer.write("""{"ok":true}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(output.out).contains("\"clientIp\":\"198.51.100.7\"")
    }

    @Test
    fun `server error api exchanges are logged at error level`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/v1/stats")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, servletResponse ->
            val httpResponse = servletResponse as HttpServletResponse
            httpResponse.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            httpResponse.contentType = "application/json"
            httpResponse.writer.write("""{"error":{"code":"INTERNAL_SERVER_ERROR"}}""")
        }

        filter.doFilter(request, response, chain)

        assertThat(output.all).contains("ERROR")
        assertThat(output.all).contains("api_exchange")
        assertThat(output.all).contains("\"status\":500")
    }

    private fun interface FilterChain : jakarta.servlet.FilterChain {
        fun doFilterInternal(request: ServletRequest, response: ServletResponse)

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            doFilterInternal(request, response)
        }
    }
}
