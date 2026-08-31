package com.buddystudy.voice.domain

/** Saved study metadata actually supplied to this lesson, not an LLM-generated topic identity. */
data class VoiceTutorStudySnapshot(
    val studyId: Long,
    val parentStudyId: Long?,
    val topic: String,
    val difficulty: Int,
    /** Zero is the immutable initial capture; positive revisions are explicit in-call setting changes. */
    val revision: Long = 0,
)
