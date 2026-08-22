package com.buddystudy.backend.config

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

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

    private fun fixture(): Fixture {
        val properties = BuddyStudyProperties().apply {
            mcp.allowedHosts = listOf("localhost", "localhost:*")
            mcp.allowedOrigins = emptyList()
        }
        val jsonMapper = config.buddyStudyMcpJsonMapper(JsonMapperProvider.mapper)
        val transport = config.buddyStudyMcpTransport(jsonMapper, properties)
        val server = config.buddyStudyMcpServer(
            transport = transport,
            jsonMapper = jsonMapper,
            mcp = EmptyMcpPort,
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
