package com.buddystudy.backend.voice

import com.fasterxml.jackson.databind.JsonNode

/** Internal relay annotation. Unknown (-1) preserves source but cannot establish a lesson level. */
internal object VoiceTutorTranscriptMetadata {
    const val LESSON_REVISION = "_buddystudy_lesson_revision"

    fun lessonRevision(node: JsonNode): Long = node.path(LESSON_REVISION)
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue()?.takeIf { it >= -1 } ?: -1
}
