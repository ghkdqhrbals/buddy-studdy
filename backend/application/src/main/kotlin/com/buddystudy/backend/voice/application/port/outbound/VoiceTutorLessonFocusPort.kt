package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal

/** A successful selection and the frozen metadata returned in the SAME transaction. */
data class VoiceTutorLessonFocusSelection(
    val focus: VoiceTutorLessonFocus,
    val snapshot: VoiceTutorStudySnapshot,
    // A same-focus retry must not move a controller backwards past intervening metadata changes.
    val lessonRevision: Long = focus.revision,
) {
    val studyId: Long get() = focus.studyId
    val revision: Long get() = lessonRevision
    val topic: String get() = snapshot.topic
    val difficulty: Int get() = snapshot.difficulty
}

/** Server-owned identities that must still authorize the exact realtime call at focus commit. */
data class VoiceTutorFocusCommitAuthority(
    val deviceId: String,
    val authSessionId: Long,
    val providerCallId: String,
)

interface VoiceTutorLessonFocusPort {
    /** Realtime model selection; retains atomic owner/path/revision/latest USER checks, not classifier leases. */
    suspend fun focusFromRealtimeModel(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long,
        expectedCurrentRevision: Long,
        expectedCandidate: VoiceTutorStudyTargetCandidate,
        commitAuthority: VoiceTutorFocusCommitAuthority,
        expectedParentStudyId: Long? = null,
    ): VoiceTutorLessonFocusSelection? = null
    /** Select an owned saved node in an active call; no topic/question creation or quota mutation. */
    suspend fun focus(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long? = null,
        expectedCurrentRevision: Long? = null,
        authorization: VoiceTutorFocusAuthorization? = null,
        expectedCandidate: VoiceTutorStudyTargetCandidate? = null,
        commitAuthority: VoiceTutorFocusCommitAuthority? = null,
        expectedTraversal: VoiceTutorStudyTargetTraversal? = null,
    ): VoiceTutorLessonFocusSelection?

    /**
     * Move from the persisted current focus to one live direct child in the same transaction.
     * A preceding read is only a hint: the parent edge is checked again while the active call
     * row is locked so a concurrent reparent cannot authorize a tree jump.
     */
    suspend fun advance(
        userId: Long,
        sessionId: String,
        currentStudyId: Long,
        childStudyId: Long,
        learnerTurnId: Long,
        expectedCurrentRevision: Long? = null,
        authorization: VoiceTutorFocusAuthorization? = null,
        expectedCandidate: VoiceTutorStudyTargetCandidate? = null,
        commitAuthority: VoiceTutorFocusCommitAuthority? = null,
        expectedTraversal: VoiceTutorStudyTargetTraversal? = null,
    ): VoiceTutorLessonFocusSelection? = null

    /** Bounded immutable selection history, including the initial legacy selection at revision zero. */
    suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus>
}

object UnavailableVoiceTutorLessonFocusPort : VoiceTutorLessonFocusPort {
    override suspend fun focus(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long?,
        expectedCurrentRevision: Long?,
        authorization: VoiceTutorFocusAuthorization?,
        expectedCandidate: VoiceTutorStudyTargetCandidate?,
        commitAuthority: VoiceTutorFocusCommitAuthority?,
        expectedTraversal: VoiceTutorStudyTargetTraversal?,
    ): VoiceTutorLessonFocusSelection? = null
    override suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> = emptyList()
}
