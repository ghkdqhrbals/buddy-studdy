package com.buddystuddy.backend.common.adapter.inbound.web

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestLoggingFilterTest {
    private val filter = RequestLoggingFilter()

    @Test
    fun `response body is preserved after content caching logging`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me/snapshot")
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

    private fun interface FilterChain : jakarta.servlet.FilterChain {
        fun doFilterInternal(request: ServletRequest, response: ServletResponse)

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            doFilterInternal(request, response)
        }
    }
}
