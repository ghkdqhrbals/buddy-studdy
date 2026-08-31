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
    val changedStudyId: Long? = createdStudyId,
    val changeKind: VoiceTutorStudyChangeKind? = if (createdStudyId != null) VoiceTutorStudyChangeKind.CREATED else null,
    val deletedStudyIds: List<Long> = emptyList(),
    // Server-owned context revision. A tool's JSON cannot set a response's lesson level.
    val lessonRevision: Long? = null,
    // Only a verified persisted focus may update the compact call header; never parse model JSON for it.
    val lessonFocus: VoiceTutorLessonFocusSelection? = null,
    val lessonFocusCleared: Boolean = false,
)

enum class VoiceTutorStudyChangeKind { CREATED, UPDATED, DELETED }

/** Only persisted, accepted learner speech can advance a destructive-action confirmation. */
interface VoiceTutorMutationConfirmationPort {
    suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long?
    suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long?
}

object UnavailableVoiceTutorMutationConfirmationPort : VoiceTutorMutationConfirmationPort {
    override suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long? = null
    override suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long? = null
}

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
