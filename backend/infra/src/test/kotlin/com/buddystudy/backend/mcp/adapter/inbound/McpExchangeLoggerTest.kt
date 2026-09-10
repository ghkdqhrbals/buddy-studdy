package com.buddystudy.backend.mcp.adapter.inbound

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpExchangeLoggerTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val observer = McpExchangeLogger(mapper)
    private val context = McpExchangeContext(42, "voice", "parent-123", "session-123", "call-123")

    @Test
    fun `successful suspended operation retains request response correlation and elapsed milliseconds`() {
        val rows = captureLogs {
            val actual = runBlocking {
                observer.observe(context, "tools/call", "list_studies", mapOf("name" to "list_studies", "arguments" to mapOf("limit" to 10)),
                    response = { value: Map<String, Any> -> McpExchangeResponse(value) }) {
                    delay(25)
                    mapOf("studies" to listOf(mapOf("id" to 7, "topic" to "자료구조")))
                }
            }
            assertThat(actual).containsKey("studies")
        }
        val row = rows.single()
        assertThat(row.path("protocol").asText()).isEqualTo("mcp")
        assertThat(row.path("method").asText()).isEqualTo("MCP")
        assertThat(row.path("path").asText()).isEqualTo("/mcp/tools/call/list_studies")
        assertThat(row.path("transport").asText()).isEqualTo("voice")
        assertThat(row.path("userId").asText()).isEqualTo("42")
        assertThat(row.path("parentRequestId").asText()).isEqualTo("parent-123")
        assertThat(row.path("sessionId").asText()).isEqualTo("session-123")
        assertThat(row.path("callId").asText()).isEqualTo("call-123")
        assertThat(row.path("requestBody").path("arguments").path("limit").asInt()).isEqualTo(10)
        assertThat(row.path("responseBody").path("studies")[0].path("topic").asText()).isEqualTo("자료구조")
        assertThat(row.path("status").asInt()).isEqualTo(200)
        assertThat(row.path("durationMs").asText().toDouble()).isGreaterThanOrEqualTo(20.0)
        assertThat(Instant.parse(row.path("completedAt").asText())).isAfterOrEqualTo(Instant.parse(row.path("startedAt").asText()))
    }

    @Test
    fun `structured MCP failures keep their application status and error code`() {
        val rows = captureLogs {
            observer.observeMono(context, "tools/call", "submit_answer", emptyMap<String, Any>(),
                response = { output: String -> McpExchangeResponse(output, true) }) {
                Mono.just("""{"error":{"code":"VALIDATION_ERROR","status":422,"message":"Answer required"}}""")
            }.block()
        }
        assertThat(rows.single().path("status").asInt()).isEqualTo(422)
        assertThat(rows.single().path("errorCode").asText()).isEqualTo("VALIDATION_ERROR")
        assertThat(rows.single().path("responseBody").path("error").path("message").asText()).isEqualTo("Answer required")
    }

    @Test
    fun `voice errors without explicit status distinguish outages permissions and learner state`() {
        for ((code, expectedStatus) in mapOf(
            "MCP_UNAVAILABLE" to 503, "TOOL_UNAVAILABLE" to 503,
            "INTERNAL_SERVER_ERROR" to 500, "INVALID_TOOL_RESULT" to 500, "INVALID_QUESTION_RESULT" to 500,
            "CALL_NOT_AUTHORIZED" to 403, "STUDY_SCOPE_DENIED" to 403, "TOOL_NOT_ALLOWED" to 403,
            "ANSWER_UNAVAILABLE" to 400, "QUESTION_CONTEXT_UNAVAILABLE" to 400, "LESSON_FOCUS_UNAVAILABLE" to 400,
        )) {
            val rows = captureLogs {
                observer.observeMono(context, "tools/call", "get_study", null,
                    response = { _: String -> McpExchangeResponse(mapOf("error" to mapOf("code" to code)), true) }) {
                    Mono.just("error")
                }.block()
            }
            assertThat(rows.single().path("status").asInt()).describedAs(code).isEqualTo(expectedStatus)
        }
    }

    @Test
    fun `retry stays inside one observation and cold subscriptions receive distinct IDs`() {
        var attempts = 0
        val call = observer.observeMono(context, "tools/call", "get_study", mapOf("study_id" to 7),
            response = { value: String -> McpExchangeResponse(value) }) {
            Mono.defer {
                attempts += 1
                if (attempts % 2 == 1) Mono.error(IllegalStateException("temporary"))
                else Mono.just("""{"id":7}""").delayElement(Duration.ofMillis(5))
            }.retry(1)
        }
        val rows = captureLogs(expected = 2) { call.block(); call.block() }
        assertThat(attempts).isEqualTo(4)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.path("requestId").asText() }.distinct()).hasSize(2)
        assertThat(rows.map { it.path("status").asInt() }).containsOnly(200)
    }

    @Test
    fun `reactive cancellation is observed once without turning into success`() {
        val rows = captureLogs {
            val subscription = observer.observeMono(context, "tools/call", "get_study", null,
                response = { value: String -> McpExchangeResponse(value) }) { Mono.never() }.subscribe()
            subscription.dispose()
            subscription.dispose()
        }
        assertThat(rows.single().path("status").asInt()).isEqualTo(499)
        assertThat(rows.single().path("errorCode").asText()).isEqualTo("CANCELLED")
    }

    @Test
    fun `suspend cancellation and unexpected errors propagate while recording only safe failure details`() {
        for (failure in listOf(CancellationException("private-exception-value"), IllegalStateException("private-exception-value"))) {
            val rows = captureLogs {
                assertThatThrownBy {
                    runBlocking {
                        observer.observe(context, "tools/call", "get_study", emptyMap<String, Any>(),
                            response = { _: Unit -> McpExchangeResponse(null) }) { throw failure }
                    }
                }.isSameAs(failure)
            }
            assertThat(rows.single().path("status").asInt()).isEqualTo(if (failure is CancellationException) 499 else 500)
            assertThat(rows.single().toString()).doesNotContain("private-exception-value")
        }
    }

    @Test
    fun `credentials nested JSON binary and signed URLs are removed before truncation`() {
        val canaries = listOf("secret-a", "secret-b", "secret-c", "dXNlcjpwYXNz", "url-password", "secret-query", "secret-signature", "binary-audio", "secret-malformed", "encoded-signing-value")
        val request = mapOf(
            "access_token" to canaries[0],
            "nested" to mapOf("CLIENT-SECRET" to canaries[1]),
            "content" to listOf(mapOf("text" to """{"apiKey":"secret-c","topic":"graphs"}""")),
            "answer" to "Authorization: Basic dXNlcjpwYXNz and https://user:url-password@example.test/path?client_secret=secret-query",
            "link" to "https://storage.test/file?X-Amz-Signature=secret-signature",
            "encodedLink" to "https://storage.test/file?%73ig=encoded-signing-value",
            "audio" to canaries[7],
            "broken" to """{"accessToken":"secret-malformed""",
            "longAnswer" to "visible-learning-content ".repeat(200),
        )
        val rows = captureLogs {
            observer.observeMono(context, "tools/call", "submit_answer", request,
                response = { value: Map<String, Any> -> McpExchangeResponse(value) }) { Mono.just(request) }.block()
        }
        val row = rows.single()
        for (secret in canaries) assertThat(row.toString()).doesNotContain(secret)
        assertThat(row.path("requestBody").path("truncated").asBoolean()).isTrue()
        assertThat(row.path("requestBody").path("preview").asText()).contains("graphs", "visible-learning-content", "REDACTED")
        assertThat(row.path("requestBody").path("preview").asText().length).isLessThan(2_030)
        assertThat(row.path("responseBody").path("truncated").asBoolean()).isTrue()
    }

    @Test
    fun `unknown method metadata cannot bypass the bounded redacted body`() {
        val method = "unknown-method-".repeat(1_000)
        val rows = captureLogs {
            observer.observeMono(context, method, method, mapOf("method" to method),
                response = { _: String -> McpExchangeResponse(mapOf("error" to mapOf("code" to -32601)), true) }) {
                Mono.just("method-not-found")
            }.block()
        }
        val row = rows.single()
        assertThat(row.path("operation").asText()).isEqualTo("unknown")
        assertThat(row.path("path").asText()).isEqualTo("/mcp/unknown/unknown")
        assertThat(row.path("requestBody").path("truncated").asBoolean()).isTrue()
        assertThat(row.toString().length).isLessThan(3_000)
    }

    @Test
    fun `unserializable observation data does not fail or replay the business action`() {
        var calls = 0
        class Unserializable { val value: String get() = error("private-serialization-failure") }
        val rows = captureLogs {
            val actual = runBlocking {
                observer.observe(context, "tools/call", "list_studies", Unserializable(),
                    response = { _: String -> McpExchangeResponse(Unserializable()) }) {
                    calls += 1
                    "actual-result"
                }
            }
            assertThat(actual).isEqualTo("actual-result")
        }
        assertThat(calls).isEqualTo(1)
        assertThat(rows.single().path("requestBody").path("unavailable").asBoolean()).isTrue()
        assertThat(rows.single().path("responseBody").path("unavailable").asBoolean()).isTrue()
        assertThat(rows.single().toString()).doesNotContain("private-serialization-failure")
    }

    private fun captureLogs(expected: Int = 1, action: () -> Unit): List<JsonNode> {
        val logger = LoggerFactory.getLogger(McpExchangeLogger::class.java) as Logger
        val ready = CountDownLatch(expected)
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                super.append(event)
                if (event.formattedMessage.startsWith("mcp_exchange ")) ready.countDown()
            }
        }.apply { start() }
        logger.addAppender(appender)
        try {
            action()
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(appender.list).allSatisfy { assertThat(it.throwableProxy).isNull() }
            return appender.list.filter { it.formattedMessage.startsWith("mcp_exchange ") }
                .map { mapper.readTree(it.formattedMessage.substringAfter("mcp_exchange ")) }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
