package com.buddystudy.backend.config

import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "buddystudy.mcp", name = ["enabled"], havingValue = "true")
class McpServerConfig {
    @Bean
    fun buddyStudyMcpJsonMapper(objectMapper: ObjectMapper): McpJsonMapper =
        JacksonMcpJsonMapper(objectMapper)

    @Bean
    fun buddyStudyMcpTransport(
        jsonMapper: McpJsonMapper,
        properties: BuddyStudyProperties,
    ): WebFluxStatelessServerTransport {
        val allowedHosts = properties.mcp.allowedHosts.map(String::trim).filter(String::isNotEmpty)
        require(allowedHosts.isNotEmpty()) {
            "buddystudy.mcp.allowed-hosts must contain at least one trusted Host value."
        }
        val securityValidator = DefaultServerTransportSecurityValidator.builder()
            .allowedOrigins(properties.mcp.allowedOrigins.map(String::trim).filter(String::isNotEmpty))
            .allowedHosts(allowedHosts)
            .build()

        return WebFluxStatelessServerTransport.builder()
            .jsonMapper(jsonMapper)
            .messageEndpoint(BuddyStudyMcpPort.ENDPOINT)
            .contextExtractor { request ->
                val principal = request.attribute(BearerTokenFilter.AUTHENTICATED_PRINCIPAL_ATTRIBUTE).orElse(null)
                if (principal == null) {
                    McpTransportContext.EMPTY
                } else {
                    McpTransportContext.create(mapOf(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to principal))
                }
            }
            .securityValidator(securityValidator)
            .build()
    }

    @Bean
    fun buddyStudyMcpRouterFunction(
        transport: WebFluxStatelessServerTransport,
    ): RouterFunction<*> = transport.routerFunction

    @Bean(destroyMethod = "close")
    fun buddyStudyMcpServer(
        transport: WebFluxStatelessServerTransport,
        jsonMapper: McpJsonMapper,
        mcp: BuddyStudyMcpPort,
        properties: BuddyStudyProperties,
    ): McpStatelessAsyncServer = McpServer.async(transport)
        .jsonMapper(jsonMapper)
        .serverInfo("buddystudy-mcp", "0.1.0")
        .instructions(SERVER_INSTRUCTIONS)
        .requestTimeout(Duration.ofSeconds(properties.mcp.requestTimeoutSeconds.coerceIn(5, 120)))
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(false)
                .resources(false, false)
                .build(),
        )
        .tools(mcp.tools())
        .resources(mcp.resources())
        .build()

    private companion object {
        val SERVER_INSTRUCTIONS = """
            BuddyStudy data is private to the authenticated account. Never request or infer a user ID.
            Preserve user-authored answers exactly. Root-study creation, child-topic creation, and question generation are separate operations.
            Question generation and answer grading are asynchronous: return the correlation ID and poll the matching process tool.
            Delete a study subtree only after explicit user confirmation and pass confirm=true.
            Interpret scores topic by topic and with difficulty context; do not manufacture a global average across unrelated topics.
        """.trimIndent()
    }
}
