package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext

data class VoiceTutorMcpToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>,
)

data class VoiceTutorMcpToolResult(
    val output: String,
    val isError: Boolean,
    val studyTreeChanged: Boolean = false,
    val createdStudyId: Long? = null,
)

/** Executes the existing account-scoped MCP tools without exposing account credentials. */
interface VoiceTutorMcpToolPort {
    fun definitions(): List<VoiceTutorMcpToolDefinition>

    suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult
}

/** Legacy transports and test fixtures must not acquire tool permissions implicitly. */
object UnavailableVoiceTutorMcpToolPort : VoiceTutorMcpToolPort {
    override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

    override suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ) = VoiceTutorMcpToolResult(
        output = """{"error":{"code":"MCP_UNAVAILABLE","message":"Study tools are unavailable for this call."}}""",
        isError = true,
    )
}
