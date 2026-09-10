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
    /** Canonical question changed/read by an authenticated tool; never parsed from provider JSON. */
    val questionChange: VoiceTutorQuestionChange? = null,
    /** Exact saved question for a response-scoped native readback, not a model-authored prompt. */
    val questionReadback: VoiceTutorQuestionReadback? = null,
    /** Verified canonical operation state. Never inferred from model-authored output. */
    val learningProgress: VoiceTutorLearningProgress? = null,
    /** Exact server-prepared question; spoken once after tool acknowledgement, never a write authority. */
    val mutationConfirmationQuestion: String? = null,
    /** Verified created nodes from one explicitly submitted immutable topic selection. */
    val changedStudyIds: List<Long> = emptyList(),
    /** Exact GUI action, never model JSON; its acknowledged output starts a fresh human tool budget. */
    val userInputCompleted: Boolean = false,
    /**
     * Verified refresh of the same saved question. This is not permission to
     * repeat it; the native controller may use it only to recover its own failed
     * readback for this exact question and lesson revision.
     */
    val questionReadbackRecovery: VoiceTutorQuestionReadback? = null,
)

/** A server-prepared immutable write proposal; model-authored form text cannot change it. */
data class VoiceTutorStudyTopicUserInput(
    val proposalId: String,
    val title: String,
    val prompt: String,
    val topics: List<String>,
)

data class VoiceTutorQuestionChange(val studyId: Long, val recordId: String)
data class VoiceTutorQuestionReadback(val studyId: Long, val recordId: String, val question: String)
data class VoiceTutorLearningProgress(
    val phase: VoiceTutorLearningPhase,
    val studyId: Long,
    val recordId: String? = null,
    /** Server-only process binding. Never exposed to the model or public state snapshot. */
    val correlationId: String? = null,
)
enum class VoiceTutorLearningPhase {
    CONVERSATION, QUESTION_GENERATING, QUESTION_READY, QUESTION_FAILED, GRADING, GRADED, GRADING_FAILED,
}

/** An explicit authenticated UI action bound by the native controller to its saved question.
 * Edited text is learner-authored. It never comes from model function arguments or rewrites ASR history.
 */
data class VoiceTutorReviewedAnswer(
    val answerId: String,
    val studyId: Long,
    val recordId: String,
    val lessonRevision: Long,
    val text: String,
    val precedingTutorProviderItemId: String?,
    val learnerProviderItemIds: List<String> = emptyList(),
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

/** Persisted row identities only; no separate model/regex assessment of their meaning. */
data class VoiceTutorPersistedDialogueBoundary(val learnerTurnId: Long, val tutorTurnId: Long)

/** Only persisted, accepted learner speech can advance a destructive-action confirmation. */
interface VoiceTutorMutationConfirmationPort {
    suspend fun latestLearnerTurnId(userId: Long, sessionId: String): Long?
    suspend fun latestTutorTurnId(userId: Long, sessionId: String): Long?
    /** Native tools may follow a tutor preamble; only a newer USER supersedes their source turn. */
    suspend fun persistedLearnerTurnId(
        userId: Long, sessionId: String, providerItemId: String, lessonRevision: Long,
    ): Long? = null
    suspend fun persistedDialogueBoundary(
        userId: Long,
        sessionId: String,
        learnerProviderItemId: String,
        tutorProviderItemId: String,
        lessonRevision: Long,
    ): VoiceTutorPersistedDialogueBoundary? = null
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
    fun realtimeDefinitions(): List<VoiceTutorMcpToolDefinition> = definitions()

    suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult

    suspend fun prepareStudyTopicUserInput(context: VoiceTutorWebRtcControlContext,
        parentStudyId: Long, topics: List<String>, difficultyLevel: Int): VoiceTutorStudyTopicUserInput? = null

    /** Called only for an exact authenticated GUI submission, never a provider tool invocation. */
    suspend fun submitStudyTopicUserInput(context: VoiceTutorWebRtcControlContext,
        proposalId: String, selectedIndices: List<Int>): VoiceTutorMcpToolResult = VoiceTutorMcpToolResult("{}", true)

    /** Read-only progress check for a previously accepted canonical operation. No model tool or speech. */
    suspend fun pollLearningProgress(
        context: VoiceTutorWebRtcControlContext,
        progress: VoiceTutorLearningProgress,
    ): VoiceTutorMcpToolResult = VoiceTutorMcpToolResult("{}", true)

    suspend fun submitReviewedAnswer(
        context: VoiceTutorWebRtcControlContext,
        answer: VoiceTutorReviewedAnswer,
    ): VoiceTutorMcpToolResult = VoiceTutorMcpToolResult(
        """{"error":{"code":"MANUAL_ANSWER_UNAVAILABLE","message":"Reviewed answer submission is unavailable."}}""", true,
    )

    suspend fun skipReviewedQuestion(
        context: VoiceTutorWebRtcControlContext,
        answer: VoiceTutorReviewedAnswer,
    ): VoiceTutorMcpToolResult = VoiceTutorMcpToolResult(
        """{"error":{"code":"MANUAL_ANSWER_UNAVAILABLE","message":"Skipping this question is unavailable."}}""", true,
    )
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
