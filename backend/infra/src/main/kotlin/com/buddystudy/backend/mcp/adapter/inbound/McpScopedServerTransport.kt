package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.auth.Principal
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import reactor.core.publisher.Mono

/** Discovery describes this request's grant; handler checks remain authoritative. */
class McpScopedServerTransport(
    private val delegate: McpStatelessServerTransport,
) : McpStatelessServerTransport by delegate {
    override fun setMcpHandler(mcpHandler: McpStatelessServerHandler) {
        delegate.setMcpHandler(object : McpStatelessServerHandler {
            override fun handleRequest(
                transportContext: McpTransportContext,
                request: McpSchema.JSONRPCRequest,
            ): Mono<McpSchema.JSONRPCResponse> {
                val scopes = (transportContext.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal)?.oauthScopes
                return mcpHandler.handleRequest(transportContext, request).map { response ->
                    if (scopes == null || response.error() != null) response
                    else filterCatalog(request.method(), response, scopes)
                }
            }

            override fun handleNotification(
                transportContext: McpTransportContext,
                notification: McpSchema.JSONRPCNotification,
            ): Mono<Void> = mcpHandler.handleNotification(transportContext, notification)
        })
    }

    private fun filterCatalog(
        method: String,
        response: McpSchema.JSONRPCResponse,
        scopes: Set<String>,
    ): McpSchema.JSONRPCResponse {
        val result = when (val result = response.result()) {
            is McpSchema.ListToolsResult -> if (method == "tools/list") McpSchema.ListToolsResult(
                result.tools().filter { BuddyStudyMcpAdapter.OAUTH_TOOL_SCOPES[it.name()] in scopes },
                result.nextCursor(), result.meta(),
            ) else return response
            is McpSchema.ListResourcesResult -> if (method == "resources/list") McpSchema.ListResourcesResult(
                result.resources().filter { BuddyStudyMcpAdapter.OAUTH_RESOURCE_SCOPES[it.uri()] in scopes },
                result.nextCursor(), result.meta(),
            ) else return response
            else -> return response
        }
        return McpSchema.JSONRPCResponse(response.jsonrpc(), response.id(), result, null)
    }
}
