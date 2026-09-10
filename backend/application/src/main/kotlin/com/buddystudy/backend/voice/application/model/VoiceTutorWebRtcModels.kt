package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.backend.auth.Principal
import java.util.concurrent.atomic.AtomicReference

data class VoiceTutorWebRtcControlContext(
    val session: VoiceTutorSession,
    val callId: String,
    // Server-authenticated identity, never a bearer token or caller-supplied user id.
    val principal: Principal? = null,
    // Server-owned lesson epoch restored when the existing call is reattached.
    val initialLessonRevision: Long = 0,
    // Complete owner-scoped saved-node snapshot, available only before this call's first USER turn.
    val initialStudyMutationSnapshot: VoiceTutorInitialStudyMutationSnapshot? = null,
    // Observed by the controller at tool execution; never client/provider JSON.
    val dialogueBoundary: VoiceTutorDialogueBoundary? = null,
    /** Trusted runtime choice; never accepted from client or provider tool JSON. */
    val realtimeModelTools: Boolean = false,
    /** Explicit authenticated control-handshake capability; old native clients remain voice-only. */
    val userInputEnabled: Boolean = false,
    /** Native execution-only live source fence; never derived from provider fields or serialized. */
    val operationStillCurrent: (() -> Boolean)? = null,
)

/** Tutor boundaries are frozen at the last accepted learner item's speech start, not tool completion. */
data class VoiceTutorDialogueBoundary(
    val responseGeneration: Long,
    val latestAcceptedLearnerSpeechStartedOrder: Long,
    val precedingTutorSpeechStoppedOrder: Long,
    val precedingSpokenResponseGeneration: Long,
    /** Exact completed tutor item immediately preceding this learner speech boundary. */
    val precedingTutorProviderItemId: String? = null,
    /** Exact accepted USER item held only by the server-side controller. */
    val latestAcceptedLearnerProviderItemId: String? = null,
    val latestAcceptedLearnerLessonRevision: Long = -1,
    val latestAcceptedLearnerIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
    /** The tutor audio immediately before this learner turn was a response to a classified study answer. */
    val precedingTutorFeedbackForStudyAnswer: Boolean = false,
    /** Exact persisted question, answer, feedback, and spoken navigation-offer items for this continuation. */
    val precedingQuestionProviderItemId: String? = null,
    val precedingAnswerProviderItemId: String? = null,
    val precedingTutorFeedbackProviderItemId: String? = null,
    val precedingTutorNavigationOfferProviderItemId: String? = null,
    val latestAcceptedLearnerTargetStudyId: Long? = null,
    val latestAcceptedLearnerTargetOfferId: Long? = null,
    /** Frozen server-read metadata for the exact candidate the learner confirmed. */
    val latestAcceptedLearnerTargetCandidate: VoiceTutorStudyTargetCandidate? = null,
    /** Exact server-private topology proof paired with that confirmed candidate. */
    val latestAcceptedLearnerTargetTraversal: VoiceTutorStudyTargetTraversal? = null,
    /** One-shot server-owned lease; a newer speech edge invalidates even an in-flight focus tool. */
    val focusAuthorization: VoiceTutorFocusAuthorization? = null,
    /** One-shot direct-root lease; invalidated by any newer learner speech before write linearization. */
    val rootStudyCreationAuthorization: VoiceTutorRootStudyCreationAuthorization? = null,
    /** One-shot exact child-create lease minted only after the assessed USER item is persisted. */
    val childStudyCreationAuthorization: VoiceTutorChildStudyCreationAuthorization? = null,
    /** One-shot exact saved-node patch lease minted only after the assessed USER item is persisted. */
    val studyUpdateAuthorization: VoiceTutorStudyUpdateAuthorization? = null,
    /** Exact owned subtree deletion requested by a freshly assessed, persisted learner turn. */
    val studyDeletionAuthorization: VoiceTutorStudyDeletionAuthorization? = null,
    /**
     * Exact post-update context revision expected by a server-owned immediate focus.
     * The accepted USER item remains fenced by [latestAcceptedLearnerLessonRevision].
     */
    val focusExpectedCurrentLessonRevision: Long? = null,
)

enum class VoiceTutorFocusAuthorizationPurpose {
    SPOKEN_SAVED_TOPIC_CHOICE,
    CREATED_ROOT_IMMEDIATE_START,
    UPDATED_STUDY_IMMEDIATE_START,
}

private sealed interface VoiceTutorOneShotAuthorizationState {
    data object UnboundActive : VoiceTutorOneShotAuthorizationState
    data class Bound(val callId: String) : VoiceTutorOneShotAuthorizationState
    data class ServerExecutionClaimed(val callId: String) : VoiceTutorOneShotAuthorizationState
    data object Consumed : VoiceTutorOneShotAuthorizationState
    data object Invalidated : VoiceTutorOneShotAuthorizationState
}

/**
 * A server-bound write has two linearization points: the controller first claims
 * the exact call under its dialogue lock, then the adapter consumes that permit
 * immediately before the write. A later learner turn may invalidate only an
 * unclaimed lease; it cannot revoke a write whose controller claim already won.
 */
private class VoiceTutorOneShotAuthorization(
    private val allowUnboundExecution: Boolean,
) {
    private val state = AtomicReference<VoiceTutorOneShotAuthorizationState>(
        VoiceTutorOneShotAuthorizationState.UnboundActive,
    )

    fun bindToServerCall(callId: String): Boolean {
        if (callId.isBlank() || callId.length > 128) return false
        return state.compareAndSet(
            VoiceTutorOneShotAuthorizationState.UnboundActive,
            VoiceTutorOneShotAuthorizationState.Bound(callId),
        )
    }

    fun isBoundToServerCall(callId: String): Boolean = when (val current = state.get()) {
        is VoiceTutorOneShotAuthorizationState.Bound -> current.callId == callId
        is VoiceTutorOneShotAuthorizationState.ServerExecutionClaimed -> current.callId == callId
        else -> false
    }

    fun claimExecution(callId: String): Boolean {
        if (callId.isBlank() || callId.length > 128) return false
        val current = state.get()
        if (current is VoiceTutorOneShotAuthorizationState.Bound && current.callId != callId) return false
        if (current !is VoiceTutorOneShotAuthorizationState.Bound &&
            (current != VoiceTutorOneShotAuthorizationState.UnboundActive || !allowUnboundExecution)
        ) return false
        return state.compareAndSet(current, VoiceTutorOneShotAuthorizationState.ServerExecutionClaimed(callId))
    }

    fun invalidate() {
        while (true) {
            val current = state.get()
            when (current) {
                VoiceTutorOneShotAuthorizationState.UnboundActive,
                is VoiceTutorOneShotAuthorizationState.Bound,
                -> if (state.compareAndSet(current, VoiceTutorOneShotAuthorizationState.Invalidated)) return
                is VoiceTutorOneShotAuthorizationState.ServerExecutionClaimed,
                VoiceTutorOneShotAuthorizationState.Consumed,
                VoiceTutorOneShotAuthorizationState.Invalidated,
                -> return
            }
        }
    }

    fun consume(): Boolean = when (val current = state.get()) {
        VoiceTutorOneShotAuthorizationState.UnboundActive -> allowUnboundExecution && state.compareAndSet(
            current,
            VoiceTutorOneShotAuthorizationState.Consumed,
        )
        is VoiceTutorOneShotAuthorizationState.ServerExecutionClaimed -> state.compareAndSet(
            current,
            VoiceTutorOneShotAuthorizationState.Consumed,
        )
        else -> false
    }

    fun isActive(): Boolean = when (state.get()) {
        VoiceTutorOneShotAuthorizationState.UnboundActive,
        is VoiceTutorOneShotAuthorizationState.Bound,
        is VoiceTutorOneShotAuthorizationState.ServerExecutionClaimed,
        -> true
        VoiceTutorOneShotAuthorizationState.Consumed,
        VoiceTutorOneShotAuthorizationState.Invalidated,
        -> false
    }
}

class VoiceTutorFocusAuthorization(
    val purpose: VoiceTutorFocusAuthorizationPurpose =
        VoiceTutorFocusAuthorizationPurpose.SPOKEN_SAVED_TOPIC_CHOICE,
) {
    private val oneShot = VoiceTutorOneShotAuthorization(
        allowUnboundExecution = purpose == VoiceTutorFocusAuthorizationPurpose.SPOKEN_SAVED_TOPIC_CHOICE,
    )

    fun bindToServerCall(callId: String): Boolean =
        purpose in setOf(
            VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START,
            VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START,
        ) &&
            oneShot.bindToServerCall(callId)

    fun isBoundToServerCall(callId: String): Boolean = oneShot.isBoundToServerCall(callId)

    fun claimExecution(callId: String): Boolean = oneShot.claimExecution(callId)

    fun invalidate() {
        oneShot.invalidate()
    }

    /** Linearization point immediately before the persistent focus write. */
    fun consume(): Boolean = oneShot.consume()

    fun isActive(): Boolean = oneShot.isActive()

    override fun toString(): String =
        "VoiceTutorFocusAuthorization(active=${oneShot.isActive()}, purpose=$purpose)"
}

class VoiceTutorRootStudyCreationAuthorization(
    val topic: String,
    val difficulty: Int,
    /** Server-attested compound intent; never derived from provider tool arguments or local text rules. */
    val startLessonAfterCreate: Boolean = false,
) {
    init {
        require(topic.isNotBlank() && topic == topic.trim() && topic.length <= 255)
        require(difficulty in 1..10)
    }

    private val oneShot = VoiceTutorOneShotAuthorization(allowUnboundExecution = false)

    fun bindToServerCall(callId: String): Boolean = oneShot.bindToServerCall(callId)

    fun isBoundToServerCall(callId: String): Boolean = oneShot.isBoundToServerCall(callId)

    fun claimExecution(callId: String): Boolean = oneShot.claimExecution(callId)

    fun invalidate() {
        oneShot.invalidate()
    }

    /** Linearization point immediately before the create-only root write begins. */
    fun consume(): Boolean = oneShot.consume()

    fun isActive(): Boolean = oneShot.isActive()

    override fun toString(): String =
        "VoiceTutorRootStudyCreationAuthorization(active=${oneShot.isActive()}, topic=[redacted], " +
            "difficulty=$difficulty, startLessonAfterCreate=$startLessonAfterCreate)"
}

class VoiceTutorChildStudyCreationAuthorization(
    val parentStudyId: Long,
    val topic: String,
    val difficulty: Int,
) {
    init {
        require(parentStudyId > 0)
        require(topic.isNotBlank() && topic == topic.trim() && topic.length <= 255)
        require(difficulty in 1..10)
    }

    private val oneShot = VoiceTutorOneShotAuthorization(allowUnboundExecution = false)

    fun bindToServerCall(callId: String): Boolean = oneShot.bindToServerCall(callId)

    fun isBoundToServerCall(callId: String): Boolean = oneShot.isBoundToServerCall(callId)

    fun claimExecution(callId: String): Boolean = oneShot.claimExecution(callId)

    fun invalidate() {
        oneShot.invalidate()
    }

    /** Linearization point immediately before the exact child create begins. */
    fun consume(): Boolean = oneShot.consume()

    fun isActive(): Boolean = oneShot.isActive()

    override fun toString(): String =
        "VoiceTutorChildStudyCreationAuthorization(active=${oneShot.isActive()}, parentStudyId=$parentStudyId, " +
            "topic=[redacted], difficulty=$difficulty)"
}

enum class VoiceTutorStudyUpdateAuthorizationScope {
    /** A bounded owner read, independent of whether the learner has selected a lesson. */
    OWNER_READ,
    /** The exact frozen node must still belong to the currently confirmed lesson tree. */
    CONFIRMED_FOCUS_TREE,

    /** The exact frozen node was server-read and spoken, but has not been selected as lesson focus. */
    OFFERED_CANDIDATE,

    /** The exact node came from a complete first-turn owner snapshot, never from provider text. */
    INITIAL_OWNER_SNAPSHOT,
}

class VoiceTutorStudyDeletionAuthorization(val targetProof: VoiceTutorStudyUpdateTargetProof) {
    val studyId: Long get() = targetProof.studyId
    private val oneShot = VoiceTutorOneShotAuthorization(allowUnboundExecution = false)
    fun bindToServerCall(callId: String): Boolean = oneShot.bindToServerCall(callId)
    fun isBoundToServerCall(callId: String): Boolean = oneShot.isBoundToServerCall(callId)
    fun claimExecution(callId: String): Boolean = oneShot.claimExecution(callId)
    fun invalidate() = oneShot.invalidate()
    fun consume(): Boolean = oneShot.consume()
    fun isActive(): Boolean = oneShot.isActive()
    override fun toString(): String = "VoiceTutorStudyDeletionAuthorization(studyId=$studyId, active=${oneShot.isActive()})"
}

/** Immutable pre-update identity. New patch values are held separately by the authorization. */
data class VoiceTutorStudyUpdateTargetProof(
    val studyId: Long,
    val parentStudyId: Long?,
    val topic: String,
    val difficulty: Int? = null,
) {
    init {
        require(studyId > 0)
        require(parentStudyId?.let { it > 0 && it != studyId } != false)
        require(topic.isNotBlank() && topic == topic.trim() && topic.length <= 255)
        require(difficulty?.let { it in 1..10 } != false)
    }

    companion object {
        fun from(candidate: VoiceTutorStudyTargetCandidate) = VoiceTutorStudyUpdateTargetProof(
            candidate.studyId,
            candidate.parentStudyId,
            candidate.topic,
            candidate.difficulty,
        )
    }
}

class VoiceTutorStudyUpdateAuthorization(
    val studyId: Long,
    val topic: String?,
    val difficulty: Int?,
    val scope: VoiceTutorStudyUpdateAuthorizationScope?,
    val targetProof: VoiceTutorStudyUpdateTargetProof?,
    /** Independently attested compound intent; update success still precedes server-owned focus. */
    val startLessonAfterUpdate: Boolean = false,
) {
    /**
     * Source-compatible fail-closed bridge while callers migrate to the explicit
     * scope/proof constructor. The MCP adapter never executes a proofless lease.
     */
    constructor(studyId: Long, topic: String?, difficulty: Int?) : this(
        studyId,
        topic,
        difficulty,
        scope = null,
        targetProof = null,
        startLessonAfterUpdate = false,
    )

    init {
        require(studyId > 0)
        require(topic != null || difficulty != null)
        require(topic?.let { it.isNotBlank() && it == it.trim() && it.length <= 255 } != false)
        require(difficulty?.let { it in 1..10 } != false)
        require((scope == null) == (targetProof == null))
        require(targetProof?.studyId?.let { it == studyId } != false)
    }

    private val oneShot = VoiceTutorOneShotAuthorization(allowUnboundExecution = false)

    fun bindToServerCall(callId: String): Boolean = oneShot.bindToServerCall(callId)

    fun isBoundToServerCall(callId: String): Boolean = oneShot.isBoundToServerCall(callId)

    fun claimExecution(callId: String): Boolean = oneShot.claimExecution(callId)

    fun invalidate() {
        oneShot.invalidate()
    }

    /** Linearization point immediately before the exact name/level patch begins. */
    fun consume(): Boolean = oneShot.consume()

    fun isActive(): Boolean = oneShot.isActive()

    override fun toString(): String =
        "VoiceTutorStudyUpdateAuthorization(active=${oneShot.isActive()}, studyId=$studyId, " +
            "hasTopic=${topic != null}, difficulty=$difficulty, scope=$scope, hasTargetProof=${targetProof != null}, " +
            "startLessonAfterUpdate=$startLessonAfterUpdate)"
}
