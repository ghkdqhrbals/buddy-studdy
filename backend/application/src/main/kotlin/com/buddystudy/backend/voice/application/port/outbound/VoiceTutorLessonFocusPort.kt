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
    suspend fun focus(userId: Long, sessionId: String, studyId: Long): VoiceTutorLessonFocusSelection?

    /** Bounded immutable selection history, including the initial legacy selection at revision zero. */
    suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus>
}

object UnavailableVoiceTutorLessonFocusPort : VoiceTutorLessonFocusPort {
    override suspend fun focus(userId: Long, sessionId: String, studyId: Long): VoiceTutorLessonFocusSelection? = null
    override suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> = emptyList()
}
