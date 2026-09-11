package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.voice.VoiceTutorRealtimeContract

/** Presentation snapshots only. Input, answer and pause coordinators retain control authority. */
internal class VoiceTutorSessionStatePublisher(
    initialRevision: Long,
    initialStudyId: Long?,
    private val publish: (Map<String, Any>) -> Unit,
) {
    data class Snapshot(
        val phase: String = "conversation",
        val paused: Boolean = false,
        val revision: Long,
        val studyId: Long? = null,
        val recordId: String? = null,
        val answerId: String? = null,
    )

    var current = Snapshot(revision = initialRevision, studyId = initialStudyId?.takeIf { it > 0 })
        private set
    private var sequence = 0L
    private var published: Snapshot? = null

    fun start() = emit()

    /** Explicit read completion needs a fresh receipt even when state is unchanged. */
    fun refresh() = emit(force = true)

    fun update(
        phase: String,
        revision: Long = current.revision,
        studyId: Long? = current.studyId,
        recordId: String? = current.recordId,
        answerId: String? = current.answerId,
    ) {
        if (phase !in VoiceTutorRealtimeContract.SESSION_PHASES || revision < current.revision ||
            (current.phase in TERMINAL_PHASES && phase != current.phase) ||
            (current.phase == "ending" && phase !in TERMINAL_PHASES && phase != "ending")) return
        if (studyId != null && studyId <= 0 || recordId != null &&
            (studyId == null || !RECORD_ID.matches(recordId) || recordId.toLongOrNull() == null) ||
            answerId != null && (recordId == null || !ANSWER_ID.matches(answerId))) return
        if (phase in VoiceTutorRealtimeContract.ANSWER_SESSION_PHASES && answerId == null ||
            phase in VoiceTutorRealtimeContract.RECORD_SESSION_PHASES && recordId == null) return
        current = current.copy(phase = phase, revision = revision, studyId = studyId, recordId = recordId, answerId = answerId)
        emit()
    }

    fun pause(paused: Boolean) {
        if (current.phase in TERMINAL_PHASES) return
        current = current.copy(paused = paused)
        emit()
    }

    private fun emit(force: Boolean = false) {
        if (!force && published == current) return
        published = current
        val event = linkedMapOf<String, Any>(
            "type" to VoiceTutorRealtimeContract.SESSION_STATE_EVENT,
            "sequence" to ++sequence, "phase" to current.phase,
            "paused" to current.paused, "revision" to current.revision,
        )
        current.studyId?.let { event["studyId"] = it }
        current.recordId?.let { event["recordId"] = it }
        current.answerId?.let { event["answerId"] = it }
        publish(event)
    }

    companion object {
        private val TERMINAL_PHASES = setOf("ended", "failed")
        private val RECORD_ID = Regex("[1-9][0-9]{0,18}")
        private val ANSWER_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
