package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.auth.Principal
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import reactor.core.publisher.Mono

/** Observe the final SDK result, including validation and dispatch failures. */
class McpLoggingServerTransport(
    private val delegate: McpStatelessServerTransport,
    private val exchangeLogger: McpExchangeLogger,
    private val objectMapper: ObjectMapper,
    private val resourceNames: Map<String, String>,
) : McpStatelessServerTransport by delegate {
    override fun setMcpHandler(mcpHandler: McpStatelessServerHandler) {
        delegate.setMcpHandler(object : McpStatelessServerHandler {
            override fun handleRequest(
                transportContext: McpTransportContext,
                request: McpSchema.JSONRPCRequest,
            ): Mono<McpSchema.JSONRPCResponse> {
                // Preserve every existing context value without accepting client metadata.
                val observedContext = McpTransportContext { key ->
                    if (key == BuddyStudyMcpPort.SUPPRESS_EXCHANGE_LOG_CONTEXT_KEY) true
                    else transportContext.get(key)
                }
                val params = request.params() as? Map<*, *>
                val target = when (request.method()) {
                    "tools/call" -> params?.get("name") as? String ?: "unknown-tool"
                    "resources/read" -> (params?.get("uri") as? String)?.let(resourceNames::get) ?: "unknown-resource"
                    else -> request.method()
                }
                return exchangeLogger.observeMono(
                    context = McpExchangeContext(
                        userId = (transportContext.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal)?.userId,
                        transport = "http",
                        parentRequestId = transportContext.get(BuddyStudyMcpPort.PARENT_REQUEST_ID_CONTEXT_KEY) as? String,
                    ),
                    operation = request.method(),
                    target = target,
                    requestBody = mapOf("id" to request.id(), "method" to request.method(), "params" to request.params()),
                    response = ::observedResponse,
                ) { mcpHandler.handleRequest(observedContext, request) }
            }

            override fun handleNotification(
                transportContext: McpTransportContext,
                notification: McpSchema.JSONRPCNotification,
            ): Mono<Void> = mcpHandler.handleNotification(transportContext, notification)
        })
    }

    private fun observedResponse(response: McpSchema.JSONRPCResponse): McpExchangeResponse {
        response.error()?.let { error ->
            val status = if (error.code() == McpSchema.ErrorCodes.INTERNAL_ERROR || error.code() in -32099..-32000) 500 else 400
            return McpExchangeResponse(mapOf("error" to mapOf(
                "code" to error.code(), "status" to status, "message" to error.message(), "data" to error.data(),
            )), isError = true)
        }
        return when (val result = response.result()) {
            is McpSchema.CallToolResult -> McpExchangeResponse(
                result.structuredContent() ?: mapOf("content" to result.content()),
                isError = result.isError() == true,
            )
            is McpSchema.ReadResourceResult -> {
                val text = (result.contents().singleOrNull() as? McpSchema.TextResourceContents)?.text()
                val structured = text?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
                McpExchangeResponse(structured ?: result)
            }
            else -> McpExchangeResponse(result)
        }
    }
}
