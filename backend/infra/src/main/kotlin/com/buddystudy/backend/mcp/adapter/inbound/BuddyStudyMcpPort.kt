package com.buddystudy.backend.mcp.adapter.inbound

import io.modelcontextprotocol.server.McpStatelessServerFeatures

interface BuddyStudyMcpPort {
    fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification>
    fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification>

    companion object {
        const val ENDPOINT = "/api/v1/mcp"
        const val PRINCIPAL_CONTEXT_KEY = "buddystudy.authenticated-principal"
        /** Call-handler-only optimistic fence fields; intentionally absent from the public MCP schema. */
        const val VOICE_EXPECTED_TOPIC_ARGUMENT = "_buddystudy_voice_expected_topic"
        const val VOICE_EXPECTED_DIFFICULTY_ARGUMENT = "_buddystudy_voice_expected_difficulty_level"
        const val VOICE_EXPECTED_PARENT_ARGUMENT = "_buddystudy_voice_expected_parent_study_id"
    }
}
