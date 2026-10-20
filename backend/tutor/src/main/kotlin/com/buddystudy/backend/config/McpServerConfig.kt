package com.buddystudy.backend.config

import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeLogger
import com.buddystudy.backend.mcp.adapter.inbound.McpLoggingServerTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import org.springframework.beans.factory.annotation.Value
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
class McpServerConfig(
    @Value("\${buddystudy.mcp.oauth.enabled:false}") private val oauthEnabled: Boolean = false,
) {
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
                    McpTransportContext.create(buildMap {
                        put(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY, principal)
                        request.attribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE).orElse(null)?.let {
                            put(BuddyStudyMcpPort.PARENT_REQUEST_ID_CONTEXT_KEY, it)
                        }
                    })
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
        exchangeLogger: McpExchangeLogger,
        objectMapper: ObjectMapper,
    ): McpStatelessAsyncServer = McpServer.async(McpLoggingServerTransport(
        delegate = transport,
        exchangeLogger = exchangeLogger,
        objectMapper = objectMapper,
        resourceNames = mcp.resources().associate { it.resource().uri() to it.resource().name() },
    ))
        .jsonMapper(jsonMapper)
        .jsonSchemaValidator(McpJsonSchemaValidatorProvider.create())
        .serverInfo("buddystudy-mcp", "0.1.0")
        .instructions(SERVER_INSTRUCTIONS)
        .requestTimeout(Duration.ofSeconds(properties.mcp.requestTimeoutSeconds.coerceIn(5, 120)))
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(false)
                .resources(false, false)
                .build(),
        )
        .tools(mcp.tools().filter { !oauthEnabled || it.tool().name() in BuddyStudyMcpAdapter.OAUTH_TOOL_SCOPES })
        .resources(mcp.resources().filter { !oauthEnabled || it.resource().uri() in BuddyStudyMcpAdapter.OAUTH_READ_RESOURCES })
        .build()

    private companion object {
        val SERVER_INSTRUCTIONS = """
            BuddyStudy data is private to the authenticated account. Never request or infer a user ID.
            Preserve user-authored answers exactly. Root-study creation, child-topic creation, and question generation are separate operations.
            Users speak naturally and never need database IDs, tool names or submission syntax. Resolve studies by name using list_studies; resolve the current question from the question most recently presented in this conversation and verify it with list_pending_questions/get_record. Keep IDs and correlation IDs internal unless requested for troubleshooting.
            During an active BuddyStudy exercise, a learner's answer to the presented question is a submission request even without words such as submit, grade or a record number. Submit that exact answer through submit_answer, poll get_grading_process, then read the saved record before reporting feedback. Never replace saved grading with your own score or merely praise an answer without saving it. Explicit drafts, requests for hints/explanations, quoted historical answers and hypothetical answers are not submission requests.
            If multiple questions fit and conversation context cannot resolve the target, briefly identify them by topic/question text and ask which one; never ask the learner to find an ID or silently choose the newest record. Resolve 'this', 'that answer' and 'a follow-up' from the established exercise context.
            Honor the learner's requested language when presenting questions and feedback. Read get_record with language and view=localized after generation; use source_language for the learner's actual answer language. Do not impose an incidental English-answer instruction from a generated prompt when the learner requested Korean practice; preserve the original stored question and submit their original Korean answer without translating or rewriting it.
            Question generation and answer grading are asynchronous: keep correlation IDs internal and poll the matching process tool until terminal. Show the persisted question itself, not only a generation receipt or record ID. A generation failure must be reported accurately.
            Delete a study subtree or learning record only after explicit user confirmation of the exact target and pass confirm=true. Existing read-only grants cannot write; reconnect and grant the requested scopes.
            Interpret scores topic by topic and with difficulty context; do not manufacture a global average across unrelated topics.
        """.trimIndent()
    }
}
