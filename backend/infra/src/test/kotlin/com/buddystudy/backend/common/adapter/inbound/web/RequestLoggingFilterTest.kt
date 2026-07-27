package com.buddystudy.backend.common.adapter.inbound.web

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@ExtendWith(OutputCaptureExtension::class)
class RequestLoggingFilterTest {
    private val filter = RequestLoggingFilter(ApiLoggingPolicy("detailed"))
    private val compactFilter = RequestLoggingFilter(ApiLoggingPolicy("compact"))

    @Test
    fun `response body is preserved after reactive logging`(): Unit = runBlocking {
        val exchange = execute(MockServerHttpRequest.get("/api/v1/studies").build()) { current ->
            writeJson(current, """{"ok":true}""")
        }

        assertThat(exchange.response.statusCode ?: HttpStatus.OK).isEqualTo(HttpStatus.OK)
        assertThat(exchange.response.bodyAsString.block()).isEqualTo("""{"ok":true}""")
    }

    @Test
    fun `request body is still available to downstream handlers`(): Unit = runBlocking {
        val requestBody = """{"openaiApiKey":"sk-secret","schedules":[{"topic":"Swift"}]}"""
        val exchange = execute(
            MockServerHttpRequest.put("/api/v1/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody),
        ) { current ->
            readBody(current).flatMap { body ->
                assertThat(body).contains("sk-secret")
                writeJson(current, """{"saved":true}""")
            }
        }

        assertThat(exchange.response.bodyAsString.block()).isEqualTo("""{"saved":true}""")
    }

    @Test
    fun `api request and response are logged in a single exchange line with secrets redacted`(output: CapturedOutput) = runBlocking {
        val exchange = execute(
            MockServerHttpRequest.post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer access-token")
                .header("CF-Connecting-IP", "203.0.113.10")
                .body("""{"idToken":"google-id-token"}"""),
        ) { current ->
            current.attributes[RequestLoggingFilter.AUTHENTICATED_USER_ID_ATTRIBUTE] = 42L
            readBody(current).flatMap { writeJson(current, """{"accessToken":"app-token"}""") }
        }

        assertThat(exchange.response.bodyAsString.block()).contains("app-token")
        assertThat(output.out).contains("api_exchange")
        assertThat(output.out).contains("\"method\":\"POST\"")
        assertThat(output.out).contains("\"path\":\"/api/v1/auth/google\"")
        assertThat(output.out).contains("\"requestBody\":{\"idToken\":\"[REDACTED]\"}")
        assertThat(output.out).contains("\"responseBody\":{\"accessToken\":\"[REDACTED]\"}")
        assertThat(output.out).contains("\"clientIp\":\"203.0.113.10\"")
        assertThat(output.out).contains("\"userId\":\"42\"")
        assertThat(output.out).contains("\"Authorization\":\"[REDACTED]\"")
        assertThat(output.out).doesNotContain("Bearer access-token")
        assertThat(output.out).doesNotContain("google-id-token")
    }

    @Test
    fun `json response is logged as nested utf8 json without escaped quotes`(output: CapturedOutput) = runBlocking {
        val exchange = execute(MockServerHttpRequest.get("/api/v1/records").build()) { current ->
            writeJson(current, """{"records":[{"id":1,"question":"짧고 명확하게"}]}""")
        }

        assertThat(exchange.response.bodyAsString.block()).contains("짧고 명확하게")
        assertThat(output.out).contains("\"responseBody\":{\"records\":[{\"id\":1,\"question\":\"짧고 명확하게\"}]}")
        assertThat(output.out).doesNotContain("\"responseBody\":\"{\\\"records\\\"")
    }

    @Test
    fun `json-like header values are logged as nested json`(output: CapturedOutput) = runBlocking {
        execute(
            MockServerHttpRequest.get("/api/v1/records")
                .header("Cf-Visitor", """{"scheme":"https"}""")
                .build(),
        ) { current -> writeJson(current, """{"ok":true}""") }

        assertThat(output.out).contains("\"Cf-Visitor\":{\"scheme\":\"https\"}")
    }

    @Test
    fun `large response body capture is bounded and response remains complete`(output: CapturedOutput) = runBlocking {
        val records = (1..300).joinToString(",") { index ->
            """{"id":$index,"question":"question-$index-${"x".repeat(40)}"}"""
        }
        val responseBody = """{"records":[$records]}"""
        val exchange = execute(MockServerHttpRequest.get("/api/v1/records").build()) { current ->
            writeJson(current, responseBody)
        }

        assertThat(exchange.response.bodyAsString.block()).contains("question-300")
        assertThat(output.out).contains("\"responseBody\":{\"truncated\":true")
        assertThat(output.out).contains("\"observedBytes\":")
        assertThat(output.out).contains("\"preview\":")
        assertThat(output.out).doesNotContain("question-300")
    }

    @Test
    fun `x forwarded for first address is logged as client ip`(output: CapturedOutput) = runBlocking {
        execute(
            MockServerHttpRequest.get("/api/v1/records")
                .header("X-Forwarded-For", "198.51.100.7, 10.0.0.2")
                .build(),
        ) { current -> writeJson(current, """{"ok":true}""") }

        assertThat(output.out).contains("\"clientIp\":\"198.51.100.7\"")
        assertThat(output.out).contains("\"userId\":\"-\"")
    }

    @Test
    fun `server error api exchanges are logged at error level`(output: CapturedOutput) = runBlocking {
        execute(MockServerHttpRequest.get("/api/v1/stats").build()) { current ->
            current.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            writeJson(current, """{"error":{"code":"INTERNAL_SERVER_ERROR"}}""")
        }

        assertThat(output.all).contains("ERROR")
        assertThat(output.all).contains("api_exchange")
        assertThat(output.all).contains("\"status\":500")
    }

    @Test
    fun `compact api log omits request metadata headers and bodies`(output: CapturedOutput) = runBlocking {
        val requestBody = """{"topic":"Redis"}"""
        val exchange = execute(
            request = MockServerHttpRequest.post("/api/v1/studies")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer access-token")
                .header("CF-Connecting-IP", "203.0.113.10")
                .body(requestBody),
            activeFilter = compactFilter,
        ) { current ->
            current.attributes[RequestLoggingFilter.AUTHENTICATED_USER_ID_ATTRIBUTE] = 42L
            readBody(current).flatMap { body ->
                assertThat(body).isEqualTo(requestBody)
                writeJson(current, """{"id":10}""")
            }
        }

        assertThat(exchange.response.bodyAsString.block()).isEqualTo("""{"id":10}""")
        assertThat(output.out).containsPattern(
            """api_exchange \{"method":"POST","path":"/api/v1/studies","status":200,"durationMs":"\d+\.\d{2}"}""",
        )
        assertThat(output.out).doesNotContain("requestId")
        assertThat(output.out).doesNotContain("clientIp")
        assertThat(output.out).doesNotContain("userId")
        assertThat(output.out).doesNotContain("requestHeaders")
        assertThat(output.out).doesNotContain("requestBody")
        assertThat(output.out).doesNotContain("responseHeaders")
        assertThat(output.out).doesNotContain("responseBody")
        assertThat(output.out).doesNotContain("203.0.113.10")
        assertThat(output.out).doesNotContain("Redis")
    }

    private fun execute(
        request: MockServerHttpRequest,
        activeFilter: RequestLoggingFilter = filter,
        handler: (ServerWebExchange) -> Mono<Void>,
    ): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(request)
        activeFilter.filter(exchange, WebFilterChain(handler)).block()
        return exchange
    }

    private fun readBody(exchange: ServerWebExchange): Mono<String> =
        DataBufferUtils.join(exchange.request.body).map { buffer ->
            val bytes = ByteArray(buffer.readableByteCount())
            buffer.read(bytes)
            DataBufferUtils.release(buffer)
            bytes.decodeToString()
        }

    private fun writeJson(exchange: ServerWebExchange, body: String): Mono<Void> {
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
