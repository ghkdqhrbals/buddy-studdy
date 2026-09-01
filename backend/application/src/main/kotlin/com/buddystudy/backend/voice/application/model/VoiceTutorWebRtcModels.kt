package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.backend.auth.Principal
import java.util.concurrent.atomic.AtomicBoolean

data class VoiceTutorWebRtcControlContext(
    val session: VoiceTutorSession,
    val callId: String,
    // Server-authenticated identity, never a bearer token or caller-supplied user id.
    val principal: Principal? = null,
    // Server-owned lesson epoch restored when the existing call is reattached.
    val initialLessonRevision: Long = 0,
    // Observed by the controller at tool execution; never client/provider JSON.
    val dialogueBoundary: VoiceTutorDialogueBoundary? = null,
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
)

class VoiceTutorFocusAuthorization {
    private val active = AtomicBoolean(true)

    fun invalidate() {
        active.set(false)
    }

    /** Linearization point immediately before the persistent focus write. */
    fun consume(): Boolean = active.compareAndSet(true, false)

    fun isActive(): Boolean = active.get()

    override fun toString(): String = "VoiceTutorFocusAuthorization(active=${active.get()})"
}

class VoiceTutorRootStudyCreationAuthorization(
    val topic: String,
    val difficulty: Int,
) {
    init {
        require(topic.isNotBlank() && topic == topic.trim() && topic.length <= 255)
        require(difficulty in 1..10)
    }

    private val active = AtomicBoolean(true)

    fun invalidate() {
        active.set(false)
    }

    /** Linearization point immediately before the create-only root write begins. */
    fun consume(): Boolean = active.compareAndSet(true, false)

    fun isActive(): Boolean = active.get()

    override fun toString(): String =
        "VoiceTutorRootStudyCreationAuthorization(active=${active.get()}, topic=[redacted], difficulty=$difficulty)"
}
