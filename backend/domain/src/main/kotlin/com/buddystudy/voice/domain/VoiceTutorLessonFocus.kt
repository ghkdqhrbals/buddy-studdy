package com.buddystudy.voice.domain

/** Explicit saved-node selection at a server-owned lesson epoch; identities survive node deletion. */
data class VoiceTutorLessonFocus(
    val studyId: Long,
    val revision: Long,
)

/** A later selection never assigns earlier navigation or questions to its node. */
class VoiceTutorLessonFocusIndex(
    focuses: List<VoiceTutorLessonFocus>,
    acceptedStudyId: Long? = null,
) {
    private val epochs = buildMap<Long, VoiceTutorLessonFocus?> {
        if (acceptedStudyId != null && acceptedStudyId > 0) {
            put(0, VoiceTutorLessonFocus(acceptedStudyId, 0))
        }
        focuses.filter { it.revision >= 0 }.groupBy { it.revision }.forEach { (revision, candidates) ->
            // An ambiguous or invalid newer entry blocks, rather than falling back to an older tree.
            put(revision, candidates.distinct().singleOrNull()?.takeIf { it.studyId > 0 })
        }
    }

    fun at(lessonRevision: Long): VoiceTutorLessonFocus? {
        if (lessonRevision < 0) return null
        val revision = epochs.keys.filter { it <= lessonRevision }.maxOrNull() ?: return null
        return epochs[revision]
    }

    val current: VoiceTutorLessonFocus?
        get() = epochs.keys.maxOrNull()?.let(epochs::get)
}
