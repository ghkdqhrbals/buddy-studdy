package com.buddystudy.backend.mcp.adapter.inbound

import io.modelcontextprotocol.server.McpStatelessServerFeatures

interface BuddyStudyMcpPort {
    fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification>
    fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification>

    companion object {
        const val ENDPOINT = "/api/v1/mcp"
        const val PRINCIPAL_CONTEXT_KEY = "buddystudy.authenticated-principal"
    }
}
