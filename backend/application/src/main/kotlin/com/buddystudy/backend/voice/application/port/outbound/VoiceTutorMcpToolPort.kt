package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot

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
    /** Trusted call-local metadata; never serialized into provider function output. */
    val candidateDiscovery: VoiceTutorCandidateDiscovery? = null,
    /**
     * Exact persisted root returned by create_root_study, whether newly created
     * or already present. This is server-owned scheduling metadata and is never
     * inferred from the provider-visible JSON output.
     */
    val rootStudyReadbackId: Long? = null,
    /**
     * Exact server-owned snapshot captured after a confirmed update result and a
     * successful lesson-context revision. Never derive this from provider JSON.
     */
    val updatedStudySnapshot: VoiceTutorStudySnapshot? = null,
    /** Exact owned node returned by a verified mutation, not extracted from provider JSON. */
    val savedMutationTarget: VoiceTutorStudyTargetCandidate? = null,
)

enum class VoiceTutorCandidateReadKind { LIST_STUDIES, GET_STUDY }

/** Exact server-validated read scope. Only complete pages can reach the realtime candidate registry. */
sealed interface VoiceTutorCandidateDiscoveryScope {
    data class ExactStudy(val requestedStudyId: Long) : VoiceTutorCandidateDiscoveryScope

    data class CompleteQueryPage(
        val query: String,
        val offset: Long,
        val limit: Long,
        val totalCount: Long,
    ) : VoiceTutorCandidateDiscoveryScope

    data class CompleteDirectChildrenPage(
        val parentStudyId: Long,
        val offset: Long,
        val limit: Long,
        val totalCount: Long,
    ) : VoiceTutorCandidateDiscoveryScope

    /** Compatibility only for non-production fixtures; the MCP adapter never emits this scope. */
    data object Unscoped : VoiceTutorCandidateDiscoveryScope
}

data class VoiceTutorCandidateDiscovery(
    val source: VoiceTutorCandidateReadKind,
    val lessonRevision: Long,
    val currentFocusStudyId: Long?,
    val candidates: List<VoiceTutorStudyTargetCandidate>,
    val scope: VoiceTutorCandidateDiscoveryScope = VoiceTutorCandidateDiscoveryScope.Unscoped,
)

enum class VoiceTutorStudyChangeKind { CREATED, UPDATED, DELETED }

data class VoiceTutorLearnerTurnAuthorization(
    val turnId: Long,
    /** Exact persisted question, answer, feedback, navigation offer and continuation form one ordered exchange. */
    val completedExchange: Boolean,
)

/** Only persisted, accepted learner speech can advance a destructive-action confirmation. */
interface VoiceTutorMutationConfirmationPort {
    suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long?
    suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long?
    suspend fun learnerTurnAuthorization(
        userId: Long,
        sessionId: String,
        providerItemId: String,
        lessonRevision: Long,
        expectedQuestionProviderItemId: String?,
        expectedAnswerProviderItemId: String?,
        expectedTutorFeedbackProviderItemId: String?,
        /** Same item as feedback for a combined response; the next TUTOR item for a separate offer. */
        expectedTutorNavigationOfferProviderItemId: String? = expectedTutorFeedbackProviderItemId,
    ): VoiceTutorLearnerTurnAuthorization? = null
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
