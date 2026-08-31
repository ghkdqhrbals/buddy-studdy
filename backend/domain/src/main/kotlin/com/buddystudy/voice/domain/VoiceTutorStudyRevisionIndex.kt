package com.buddystudy.voice.domain

/** Resolves only server-captured metadata; later changes never rewrite an earlier question's context. */
class VoiceTutorStudyRevisionIndex(snapshots: List<VoiceTutorStudySnapshot>) {
    private val epochs = snapshots.filter { it.studyId > 0 && it.revision >= 0 }
        .groupBy { it.studyId }
        .mapValues { (_, versions) ->
            versions.groupBy { it.revision }.mapValues { (_, candidates) ->
                // Ambiguity at an epoch is not permission to fall back to an older, apparently valid level.
                candidates.distinct().singleOrNull()?.takeIf {
                    it.topic.isNotBlank() && it.difficulty in 1..10 && (it.parentStudyId == null || it.parentStudyId > 0)
                }
            }
        }

    private val knownRevisions = epochs.values.flatMap { it.keys }.toSet() + 0L
    val currentRevision: Long = knownRevisions.maxOrNull() ?: 0

    fun resolve(studyId: Long, lessonRevision: Long): VoiceTutorStudySnapshot? {
        // Missing provider correlation (-1), a future epoch or a missing ledger entry is not
        // permission to reinterpret a turn as revision zero or the latest known settings.
        if (lessonRevision < 0 || lessonRevision !in knownRevisions) return null
        val versions = epochs[studyId] ?: return null
        val revision = versions.keys.filter { it <= lessonRevision }.maxOrNull() ?: return null
        return versions[revision]
    }

    fun viewAt(lessonRevision: Long): Map<Long, VoiceTutorStudySnapshot> = epochs.keys.mapNotNull { id ->
        resolve(id, lessonRevision)?.let { id to it }
    }.toMap()

    fun currentView(): List<VoiceTutorStudySnapshot> = viewAt(currentRevision).values.toList()

    fun knownVersions(studyId: Long): List<VoiceTutorStudySnapshot> = epochs[studyId]?.values?.filterNotNull().orEmpty()
}

object VoiceTutorStudyRevisionLimits {
    const val MAX_BASE_SNAPSHOTS = 64
    const val MAX_REVISIONS = 32
    const val MAX_HISTORY_SNAPSHOTS = MAX_BASE_SNAPSHOTS + MAX_REVISIONS
}
