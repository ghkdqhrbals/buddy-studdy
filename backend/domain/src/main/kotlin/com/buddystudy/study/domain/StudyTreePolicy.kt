package com.buddystudy.study.domain

/** The root is depth zero. Existing deeper nodes remain readable and editable. */
object StudyTreePolicy {
    const val MAX_DESCENDANT_DEPTH = 4
    const val MAX_TOPIC_SUGGESTIONS = 10
}
