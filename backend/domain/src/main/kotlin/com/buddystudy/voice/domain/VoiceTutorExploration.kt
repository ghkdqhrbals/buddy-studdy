package com.buddystudy.voice.domain

/** Voice-only learning evidence. These are not generated questions, graded records, or quota usage. */
enum class VoiceTutorExchangeKind {
    TUTOR_QUESTION,
    LEARNER_QUESTION,
}

data class VoiceTutorExploration(
    val topic: String,
    val studyId: Long?,
    val difficulty: Int?,
    val depthSummary: String,
    val exchanges: List<VoiceTutorLearningExchange>,
)

data class VoiceTutorLearningExchange(
    val kind: VoiceTutorExchangeKind,
    val question: String,
    val answer: String,
    /** An explicitly spoken assessment, never a grade computed by the summary model. */
    val score: Int?,
    val strengths: List<String>,
    val improvements: List<String>,
    val questionTurnId: Long,
    val answerTurnIds: List<Long>,
    val feedbackTurnIds: List<Long>,
)

object VoiceTutorExplorationLimits {
    const val MAX_EXPLORATIONS = 12
    const val MAX_EXCHANGES_PER_EXPLORATION = 12
    const val MAX_TOTAL_EXCHANGES = 48
    // Verification can split one provider topic into one group per question epoch without losing evidence.
    const val MAX_STORED_EXPLORATIONS = MAX_TOTAL_EXCHANGES
    const val MAX_TOPIC_CHARACTERS = 500
    const val MAX_DEPTH_CHARACTERS = 1_500
    const val MAX_EXCHANGE_TEXT_CHARACTERS = 4_000
    const val MAX_FEEDBACK_ITEMS = 5
    const val MAX_FEEDBACK_CHARACTERS = 500
    const val MAX_EVIDENCE_TURNS = 16
    const val MAX_JSON_BYTES = 256 * 1024
}
