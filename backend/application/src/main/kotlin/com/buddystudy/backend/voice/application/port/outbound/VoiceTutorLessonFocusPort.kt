package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot

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

interface VoiceTutorLessonFocusPort {
    /** Select an owned saved node in an active call; no topic/question creation or quota mutation. */
    suspend fun focus(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long? = null,
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
    ): VoiceTutorLessonFocusSelection? = null
    override suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> = emptyList()
}
