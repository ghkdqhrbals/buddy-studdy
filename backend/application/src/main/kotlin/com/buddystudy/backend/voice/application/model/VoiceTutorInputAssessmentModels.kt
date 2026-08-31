package com.buddystudy.backend.voice.application.model

/** Original ASR text. Assessment never normalizes, rewrites or persists this text. */
data class VoiceTutorInputUtterance(val itemId: String, val transcript: String) {
    override fun toString(): String =
        "VoiceTutorInputUtterance(itemId=[redacted], transcriptCharacters=${transcript.length})"
}

/** The caller owns authentication and the session/turn-generation fence. */
data class VoiceTutorInputAssessmentRequest(
    val userId: Long,
    val language: String,
    val teacherContext: String,
    val utterances: List<VoiceTutorInputUtterance>,
) {
    override fun toString(): String =
        "VoiceTutorInputAssessmentRequest(contextCharacters=${teacherContext.length}, utteranceCount=${utterances.size})"
}

enum class VoiceTutorInputDecision { MEANINGFUL, NON_COMMUNICATIVE }

data class VoiceTutorInputItemAssessment(val itemId: String, val decision: VoiceTutorInputDecision) {
    override fun toString(): String = "VoiceTutorInputItemAssessment(itemId=[redacted], decision=$decision)"
}

/** Exactly one decision for each requested item, returned in original request order. */
data class VoiceTutorInputAssessmentResult(val decisions: List<VoiceTutorInputItemAssessment>)

enum class VoiceTutorInputAssessmentFailure {
    INVALID_INPUT, BUSY, TIMEOUT, UNAVAILABLE, REFUSED, INVALID_RESULT,
}

/** No provider body, utterance, account identifier, credential or raw cause may escape here. */
class VoiceTutorInputAssessmentException(val reason: VoiceTutorInputAssessmentFailure) :
    RuntimeException("Voice Tutor input assessment failed: ${reason.name}.")

fun VoiceTutorInputAssessmentResult.correlatedTo(
    utterances: List<VoiceTutorInputUtterance>,
): VoiceTutorInputAssessmentResult {
    val expected = utterances.map { it.itemId }
    if (decisions.size != expected.size || expected.toSet().size != expected.size) {
        throw VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
    }
    val byId = decisions.associateBy { it.itemId }
    if (byId.size != decisions.size || byId.keys != expected.toSet()) {
        throw VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
    }
    return VoiceTutorInputAssessmentResult(expected.map { byId.getValue(it) })
}
