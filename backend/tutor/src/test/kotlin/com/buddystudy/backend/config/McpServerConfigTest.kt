package com.buddystudy.backend.config

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
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
            fixture.client.post().uri(BuddyStudyMcpPort.ENDPOINT).mcpHeaders()
                .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .exchange().expectStatus().isOk
                .expectBody().jsonPath("$.result.tools.length()").isEqualTo(catalog.tools().size)
        } finally {
            fixture.server.close()
        }
    }

    private fun toolCall(arguments: Map<String, Any>) = mapOf(
        "jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
        "params" to mapOf("name" to "schema_probe", "arguments" to arguments),
    )

    private fun schemaProbe(
        calls: AtomicInteger,
        invalidOutput: Boolean = false,
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
                Mono.just(McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent("Synthetic result")))
                    .structuredContent(mapOf("study_id" to if (invalidOutput) "PRIVATE_OUTPUT_CANARY" else 7))
                    .isError(false).build())
            })
        }

        override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = emptyList()
    }

    private fun fixture(mcp: BuddyStudyMcpPort = EmptyMcpPort): Fixture {
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
        )
        return Fixture(
            client = WebTestClient.bindToRouterFunction(config.buddyStudyMcpRouterFunction(transport)).build(),
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
