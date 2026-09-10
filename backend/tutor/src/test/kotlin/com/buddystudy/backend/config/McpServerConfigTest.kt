package com.buddystudy.backend.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeLogger
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpError
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpServerConfigTest {
    private val config = McpServerConfig()

    @Test
    fun `stateless transport initializes and lists tools over the authenticated api path`() {
        val fixture = fixture()
        try {
            fixture.client.post()
                .uri(BuddyStudyMcpPort.ENDPOINT)
                .mcpHeaders()
                .bodyValue(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":1,
                      "method":"initialize",
                      "params":{
                        "protocolVersion":"2025-11-25",
                        "capabilities":{},
                        "clientInfo":{"name":"buddystudy-test","version":"1.0.0"}
                      }
                    }
                    """.trimIndent(),
                )
                .exchange()
                .expectStatus().isOk
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.result.serverInfo.name").isEqualTo("buddystudy-mcp")
                .jsonPath("$.result.capabilities.tools").exists()
                .jsonPath("$.result.capabilities.resources").exists()

            fixture.client.post()
                .uri(BuddyStudyMcpPort.ENDPOINT)
                .mcpHeaders()
                .bodyValue("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.result.tools").isArray
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `transport rejects browser origins unless explicitly allowlisted`() {
        val fixture = fixture()
        try {
            val body = fixture.client.post()
                .uri(BuddyStudyMcpPort.ENDPOINT)
                .mcpHeaders()
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}""")
                .exchange()
                .expectStatus().isForbidden
                .expectBody(String::class.java)
                .returnResult()
                .responseBody

            assertThat(body).contains("Invalid Origin header")
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `transport routes matrix parameter variant through the mcp endpoint`() {
        val fixture = fixture()
        try {
            fixture.client.post()
                .uri("${BuddyStudyMcpPort.ENDPOINT};client=llm")
                .mcpHeaders()
                .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}""")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.result").exists()
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `transport requires json and event stream accept media types`() {
        val fixture = fixture()
        try {
            fixture.client.post()
                .uri(BuddyStudyMcpPort.ENDPOINT)
                .header(HttpHeaders.HOST, "localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}""")
                .exchange()
                .expectStatus().isBadRequest
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `HTTP MCP validates tool inputs with SDK Jackson3 while keeping the Jackson2 wire mapper`() {
        val calls = AtomicInteger()
        val fixture = fixture(schemaProbe(calls))
        try {
            assertThat(config.buddyStudyMcpJsonMapper(JsonMapperProvider.mapper).javaClass.name)
                .isEqualTo("io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper")
            val invalid = listOf(
                emptyMap<String, Any>(),
                mapOf("study_id" to 0),
                mapOf("study_id" to 1.5),
                mapOf("study_id" to "PRIVATE_ARGUMENT_CANARY"),
                mapOf("study_id" to 7, "foreign" to true),
            )
            invalid.forEach { arguments ->
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .bodyValue(toolCall(arguments)).exchange().expectStatus().isOk
                    .expectBody()
                    .jsonPath("$.result.isError").isEqualTo(true)
                    .jsonPath("$.result.content[0].text").value<String> {
                        assertThat(it).doesNotContain("PRIVATE_ARGUMENT_CANARY")
                    }
            }
            assertThat(calls.get()).isZero()
            fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                .bodyValue(toolCall(mapOf("study_id" to 7))).exchange().expectStatus().isOk
                .expectBody().jsonPath("$.result.isError").isEqualTo(false)
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `HTTP MCP output schema validation remains active and redacts invalid private result fields`() {
        val calls = AtomicInteger()
        val fixture = fixture(schemaProbe(calls, invalidOutput = true))
        try {
            fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                .bodyValue(toolCall(mapOf("study_id" to 7))).exchange().expectStatus().isOk
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.content[0].text").value<String> {
                    assertThat(it).doesNotContain("PRIVATE_OUTPUT_CANARY")
                }
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `HTTP MCP registration rejects structurally invalid tool schema with both JSON SDKs installed`() {
        assertThatThrownBy {
            fixture(schemaProbe(AtomicInteger(), inputSchema = mapOf("type" to "INVALID_SCHEMA_TYPE")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HTTP MCP registers every real catalog schema with the explicit compatible validator`() {
        val useCase = Proxy.newProxyInstance(
            BuddyStudyMcpUseCase::class.java.classLoader, arrayOf(BuddyStudyMcpUseCase::class.java),
        ) { _, method, _ -> error("Catalog registration must not call a use case: ${method.name}") } as BuddyStudyMcpUseCase
        val catalog = BuddyStudyMcpAdapter(useCase, JsonMapperProvider.mapper)
        val fixture = fixture(catalog)
        try {
            val responseBody = fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody
                ?: error("MCP tools/list returned no body")
            val names = JsonMapperProvider.mapper.readTree(responseBody)
                .path("result").path("tools").map { it.path("name").asText() }

            assertThat(names).hasSize(catalog.tools().size)
            assertThat(names).contains("create_root_study")
            assertThat(names).doesNotContain("create_study")
        } finally {
            fixture.server.close()
        }
    }

    @Test
    fun `HTTP tool and resource logs observe one final result with trusted user and parent request ID`() {
        val principal = Principal(7, "synthetic-mcp-device", 11, false)
        val calls = AtomicInteger()
        val useCase = Proxy.newProxyInstance(
            BuddyStudyMcpUseCase::class.java.classLoader, arrayOf(BuddyStudyMcpUseCase::class.java),
        ) { _, method, args ->
            assertThat(method.name).isEqualTo("listStudies")
            assertThat(args?.first()).isEqualTo(principal)
            calls.incrementAndGet()
            StudyPageResponse(emptyList(), 0, 100, 0, Instant.EPOCH)
        } as BuddyStudyMcpUseCase
        val fixture = fixture(BuddyStudyMcpAdapter(useCase, JsonMapperProvider.mapper), principal, "server-request-123")
        try {
            val exchanges = recordExchanges(2) {
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .header(RequestLoggingFilter.REQUEST_ID_HEADER, "untrusted-request-id")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer private-header-canary")
                    .bodyValue(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                        "params" to mapOf("name" to "list_studies", "arguments" to emptyMap<String, Any>())))
                    .exchange().expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .bodyValue(mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "resources/read",
                        "params" to mapOf("uri" to "buddystudy://studies")))
                    .exchange().expectStatus().isOk.expectBody().jsonPath("$.result.contents").isArray
            }
            assertThat(calls.get()).isEqualTo(2)
            assertThat(exchanges).hasSize(2)
            exchanges.forEach {
                assertThat(it.path("userId").asText()).isEqualTo("7")
                assertThat(it.path("parentRequestId").asText()).isEqualTo("server-request-123")
                assertThat(it.path("transport").asText()).isEqualTo("http")
                assertThat(it.path("status").asInt()).isEqualTo(200)
                assertThat(it.path("durationMs").asDouble()).isGreaterThanOrEqualTo(0.0)
                assertThat(it.path("responseBody").path("studies").isArray).isTrue()
                assertThat(it.toString()).doesNotContain("private-header-canary", "untrusted-request-id", "synthetic-mcp-device")
            }
            assertThat(exchanges[0].path("toolName").asText()).isEqualTo("list_studies")
            assertThat(exchanges[1].path("resourceName").asText()).isEqualTo("my-studies")
        } finally { fixture.server.close() }
    }

    @Test
    fun `HTTP input validation failure is logged once even when the tool handler does not run`() {
        val calls = AtomicInteger()
        val fixture = fixture(schemaProbe(calls))
        try {
            val exchanges = recordExchanges(1) {
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .bodyValue(toolCall(mapOf("study_id" to 0))).exchange().expectStatus().isOk
                    .expectBody().jsonPath("$.result.isError").isEqualTo(true)
            }
            assertThat(calls.get()).isZero()
            assertThat(exchanges).hasSize(1)
            assertThat(exchanges.single().path("status").asInt()).isEqualTo(400)
            assertThat(exchanges.single().path("toolName").asText()).isEqualTo("schema_probe")
            assertThat(exchanges.single().path("responseBody").path("content").isArray).isTrue()
        } finally { fixture.server.close() }
    }

    @Test
    fun `HTTP unknown tool JSON RPC error is logged once as a failed operation`() {
        val fixture = fixture()
        try {
            val exchanges = recordExchanges(1) {
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .bodyValue(toolCall(emptyMap())).exchange().expectStatus().isOk
                    .expectBody().jsonPath("$.error.code").isEqualTo(-32602)
            }
            assertThat(exchanges).hasSize(1)
            assertThat(exchanges.single().path("status").asInt()).isEqualTo(400)
            assertThat(exchanges.single().path("errorCode").asText()).isEqualTo("-32602")
            assertThat(exchanges.single().path("responseBody").path("error").path("code").asInt()).isEqualTo(-32602)
        } finally { fixture.server.close() }
    }

    @Test
    fun `HTTP JSON RPC internal and reserved server errors retain server failure classification`() {
        val codes = listOf(McpSchema.ErrorCodes.INTERNAL_ERROR, -32099, -32000)
        val exchanges = recordExchanges(codes.size) {
            codes.forEach { code ->
                val failure = McpError.builder(code).message("Synthetic server failure").build()
                val fixture = fixture(schemaProbe(AtomicInteger(), terminalError = failure))
                try {
                    fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                        .bodyValue(toolCall(mapOf("study_id" to 7))).exchange().expectStatus().isOk
                        .expectBody().jsonPath("$.error.code").isEqualTo(code)
                } finally { fixture.server.close() }
            }
        }
        assertThat(exchanges).hasSize(codes.size)
        assertThat(exchanges.map { it.path("status").asInt() }).containsOnly(500)
        assertThat(exchanges.map { it.path("errorCode").asInt() }).containsExactlyElementsOf(codes)
        exchanges.forEach { assertThat(it.path("responseBody").path("error").path("status").asInt()).isEqualTo(500) }
    }

    @Test
    fun `HTTP output validation logs the SDK error returned to the client instead of the discarded success`() {
        val calls = AtomicInteger()
        val fixture = fixture(schemaProbe(calls, invalidOutput = true))
        try {
            val exchanges = recordExchanges(1) {
                fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                    .bodyValue(toolCall(mapOf("study_id" to 7))).exchange().expectStatus().isOk
                    .expectBody().jsonPath("$.result.isError").isEqualTo(true)
            }
            assertThat(calls.get()).isEqualTo(1)
            assertThat(exchanges).hasSize(1)
            assertThat(exchanges.single().path("status").asInt()).isEqualTo(400)
            assertThat(exchanges.single().path("responseBody").toString())
                .contains("output validation failed").doesNotContain("PRIVATE_OUTPUT_CANARY", "Synthetic result")
        } finally { fixture.server.close() }
    }

    @Test
    fun `HTTP discovery requests are included in MCP exchanges`() {
        val fixture = fixture()
        try {
            val exchanges = recordExchanges(2) {
                listOf("tools/list", "resources/list").forEachIndexed { index, method ->
                    fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                        .bodyValue(mapOf("jsonrpc" to "2.0", "id" to index, "method" to method, "params" to emptyMap<String, Any>()))
                        .exchange().expectStatus().isOk.expectBody().jsonPath("$.result").exists()
                }
            }
            assertThat(exchanges.map { it.path("operation").asText() }).containsExactly("tools/list", "resources/list")
            assertThat(exchanges.map { it.path("status").asInt() }).containsOnly(200)
        } finally { fixture.server.close() }
    }

    private fun recordExchanges(expected: Int, action: () -> Unit): List<JsonNode> {
        val records = CopyOnWriteArrayList<JsonNode>()
        val received = CountDownLatch(expected)
        val logger = LoggerFactory.getLogger(McpExchangeLogger::class.java) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.formattedMessage.startsWith("mcp_exchange ")) {
                    records.add(JsonMapperProvider.mapper.readTree(event.formattedMessage.removePrefix("mcp_exchange ")))
                    received.countDown()
                }
            }
        }.apply { start() }
        logger.addAppender(appender)
        return try {
            action()
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
            records.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun toolCall(arguments: Map<String, Any>) = mapOf(
        "jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
        "params" to mapOf("name" to "schema_probe", "arguments" to arguments),
    )

    private fun schemaProbe(
        calls: AtomicInteger,
        invalidOutput: Boolean = false,
        terminalError: Throwable? = null,
        inputSchema: Map<String, Any> = mapOf(
            "type" to "object", "properties" to mapOf("study_id" to mapOf("type" to "integer", "minimum" to 1)),
            "required" to listOf("study_id"), "additionalProperties" to false,
        ),
    ): BuddyStudyMcpPort = object : BuddyStudyMcpPort {
        override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> {
            val tool = McpSchema.Tool.builder("schema_probe", inputSchema)
                .description("Synthetic schema contract probe")
                .apply { if (invalidOutput) outputSchema(inputSchema) }
                .build()
            return listOf(McpStatelessServerFeatures.AsyncToolSpecification(tool) { _, _ ->
                calls.incrementAndGet()
                if (terminalError != null) Mono.error(terminalError)
                else Mono.just(McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent("Synthetic result")))
                    .structuredContent(mapOf("study_id" to if (invalidOutput) "PRIVATE_OUTPUT_CANARY" else 7))
                    .isError(false).build())
            })
        }

        override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = emptyList()
    }

    private fun fixture(
        mcp: BuddyStudyMcpPort = EmptyMcpPort,
        principal: Principal? = null,
        parentRequestId: String? = null,
    ): Fixture {
        val properties = BuddyStudyProperties().apply {
            this.mcp.allowedHosts = listOf("localhost", "localhost:*")
            this.mcp.allowedOrigins = emptyList()
        }
        val jsonMapper = config.buddyStudyMcpJsonMapper(JsonMapperProvider.mapper)
        val transport = config.buddyStudyMcpTransport(jsonMapper, properties)
        val server = config.buddyStudyMcpServer(
            transport = transport,
            jsonMapper = jsonMapper,
            mcp = mcp,
            properties = properties,
            exchangeLogger = McpExchangeLogger(JsonMapperProvider.mapper),
            objectMapper = JsonMapperProvider.mapper,
        )
        return Fixture(
            client = WebTestClient.bindToRouterFunction(config.buddyStudyMcpRouterFunction(transport))
                .webFilter<WebTestClient.RouterFunctionSpec>(WebFilter { exchange, chain ->
                    principal?.let { exchange.attributes[BearerTokenFilter.AUTHENTICATED_PRINCIPAL_ATTRIBUTE] = it }
                    parentRequestId?.let { exchange.attributes[RequestLoggingFilter.REQUEST_ID_ATTRIBUTE] = it }
                    chain.filter(exchange)
                }).build(),
            server = server,
        )
    }

    private fun WebTestClient.RequestBodySpec.mcpHeaders(): WebTestClient.RequestBodySpec =
        header(HttpHeaders.HOST, "localhost")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)

    private data class Fixture(
        val client: WebTestClient,
        val server: McpStatelessAsyncServer,
    )

    private object EmptyMcpPort : BuddyStudyMcpPort {
        override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> = emptyList()
        override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = emptyList()
    }
}
