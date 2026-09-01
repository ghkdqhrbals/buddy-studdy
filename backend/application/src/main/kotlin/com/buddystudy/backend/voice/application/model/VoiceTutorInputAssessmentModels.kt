package com.buddystudy.backend.voice.application.model

data class VoiceTutorStudyTargetCandidate(
    val studyId: Long,
    val parentStudyId: Long?,
    val topic: String,
)

/** One server-verified edge that had exactly one live direct child when the offer was built. */
data class VoiceTutorStudyTargetSingleChildEdge(
    val parentStudyId: Long,
    val childStudyId: Long,
)

/**
 * Server-private topology evidence for one offered target. It is never sent to the assessment
 * model. Empty edges are valid for an explicit search/branch choice; a terminal leaf is present
 * only when a complete zero-child read was what made the target an endpoint.
 */
data class VoiceTutorStudyTargetTraversal(
    val singleChildEdges: List<VoiceTutorStudyTargetSingleChildEdge> = emptyList(),
    val terminalLeafStudyId: Long? = null,
) {
    fun isValidFor(candidate: VoiceTutorStudyTargetCandidate, maxEdges: Int = 32): Boolean {
        if (maxEdges !in 0..32 || singleChildEdges.size > maxEdges ||
            terminalLeafStudyId?.let { it <= 0 } == true || candidate.studyId <= 0 ||
            candidate.parentStudyId == candidate.studyId
        ) return false
        val pathIds = linkedSetOf<Long>()
        singleChildEdges.forEachIndexed { index, edge ->
            if (edge.parentStudyId <= 0 || edge.childStudyId <= 0 ||
                edge.parentStudyId == edge.childStudyId || !pathIds.add(edge.parentStudyId) ||
                (index > 0 && singleChildEdges[index - 1].childStudyId != edge.parentStudyId)
            ) return false
        }
        singleChildEdges.lastOrNull()?.childStudyId?.let { if (!pathIds.add(it)) return false }
        return when {
            terminalLeafStudyId != null ->
                terminalLeafStudyId == candidate.studyId &&
                    (singleChildEdges.lastOrNull()?.childStudyId ?: candidate.studyId) == candidate.studyId &&
                    (singleChildEdges.lastOrNull()?.parentStudyId ?: candidate.parentStudyId) ==
                    candidate.parentStudyId
            singleChildEdges.isNotEmpty() ->
                candidate.studyId !in pathIds &&
                    candidate.parentStudyId == singleChildEdges.last().childStudyId
            else -> true
        }
    }
}

/** One server-owned spoken offer. Tool arguments and provider JSON cannot create it. */
data class VoiceTutorStudyTargetOffer(
    val offerId: Long,
    val lessonRevision: Long,
    val tutorResponseGeneration: Long,
    val tutorSpeechStoppedOrder: Long,
    val currentFocusStudyId: Long?,
    val candidates: List<VoiceTutorStudyTargetCandidate>,
    /** Final provider audio transcript for this exact completed response. */
    val tutorAudioTranscript: String,
    /** Exact call-local topology proof by candidate identity; never serialized to the provider. */
    val candidateTraversals: Map<Long, VoiceTutorStudyTargetTraversal> = emptyMap(),
)

/** Exact create-only preview returned by the server-side voice MCP bridge. */
data class VoiceTutorRootStudyCreationPreview(
    val topic: String,
    val difficulty: Int,
    val lessonRevision: Long,
    /** Function-call response generation that prepared the one-shot confirmation ticket. */
    val previewResponseGeneration: Long,
)

/**
 * One server-owned root-creation offer, bound to the exact completed tutor audio immediately
 * before a learner turn. Provider text and tool arguments cannot create or alter these fields.
 */
data class VoiceTutorRootStudyCreationOffer(
    val topic: String,
    val difficulty: Int,
    val lessonRevision: Long,
    val previewResponseGeneration: Long,
    val tutorResponseGeneration: Long,
    val tutorSpeechStoppedOrder: Long,
    val tutorProviderItemId: String,
    /** Final provider audio transcript for this exact completed response. */
    val tutorAudioTranscript: String,
)

/** Original ASR text. Assessment never normalizes, rewrites or persists this text. */
data class VoiceTutorInputUtterance(
    val itemId: String,
    val transcript: String,
    val checkpoint: Boolean = false,
    val targetOffer: VoiceTutorStudyTargetOffer? = null,
    val rootStudyCreationOffer: VoiceTutorRootStudyCreationOffer? = null,
    /**
     * Assessment-only, bounded original ASR from earlier checkpoints in this same continuous
     * speech sequence, followed by [transcript]. Persistence still receives the exact raw event
     * for [transcript]; this context must never be used to rewrite either provider item.
     */
    val sameSpeechContext: String? = null,
) {
    override fun toString(): String =
        "VoiceTutorInputUtterance(itemId=[redacted], transcriptCharacters=${transcript.length}, " +
            "sameSpeechContextCharacters=${sameSpeechContext?.length ?: 0})"
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

enum class VoiceTutorInputIntent {
    NONE,
    END_CURRENT_VOICE_LESSON,
    /** The learner explicitly asks to create one new top-level saved study. */
    CREATE_ROOT_STUDY,
    /** The learner contextually affirms the exact immediately preceding root-creation offer. */
    CONFIRM_ROOT_STUDY,
    /** The learner explicitly names a saved topic they want to enter or switch to. */
    SELECT_SAVED_TOPIC,
    /** The learner explicitly asks to continue deeper from the current saved-tree node. */
    CONTINUE_TREE,
    /** The learner names a saved area to browse before any exact server-owned candidate was offered. */
    DISCOVER_SAVED_TOPIC,
    /** The learner is answering the tutor's latest substantive study question. */
    ANSWER_TO_STUDY_QUESTION,
}

data class VoiceTutorInputItemAssessment(
    val itemId: String,
    val decision: VoiceTutorInputDecision,
    val intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
    val targetStudyId: Long? = null,
    /** Candidates semantically attested as actually proposed in tutorAudioTranscript. */
    val spokenCandidateStudyIds: List<Long> = emptyList(),
) {
    override fun toString(): String =
        "VoiceTutorInputItemAssessment(itemId=[redacted], decision=$decision, intent=$intent, hasTarget=${targetStudyId != null}, spokenCandidateCount=${spokenCandidateStudyIds.size})"
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
    val ordered = expected.map { byId.getValue(it) }
    if (ordered.zip(utterances).any { (decision, utterance) ->
            val spoken = decision.spokenCandidateStudyIds
            val offer = utterance.targetOffer
            val spokenValid = spoken.size <= 3 && spoken.distinct().size == spoken.size &&
                spoken.all { spokenId -> offer?.candidates?.any { it.studyId == spokenId } == true }
            if (decision.decision == VoiceTutorInputDecision.NON_COMMUNICATIVE) {
                decision.intent != VoiceTutorInputIntent.NONE || decision.targetStudyId != null || spoken.isNotEmpty()
            } else {
                val targetIntent = decision.intent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                    decision.intent == VoiceTutorInputIntent.CONTINUE_TREE
                val target = decision.targetStudyId
                when {
                    !spokenValid -> true
                    utterance.checkpoint -> target != null || targetIntent || spoken.isNotEmpty() ||
                        decision.intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY ||
                        decision.intent == VoiceTutorInputIntent.CONFIRM_ROOT_STUDY
                    decision.intent == VoiceTutorInputIntent.CONFIRM_ROOT_STUDY &&
                        utterance.rootStudyCreationOffer == null -> true
                    targetIntent && target == null -> true
                    targetIntent && (spoken.isEmpty() || target !in spoken) -> true
                    !targetIntent && target != null -> true
                    !targetIntent && spoken.isNotEmpty() -> true
                    target != null && offer?.candidates?.none { it.studyId == target } != false -> true
                    decision.intent == VoiceTutorInputIntent.CONTINUE_TREE && target != null &&
                        offer?.currentFocusStudyId?.let { focus ->
                            offer.candidates.singleOrNull { it.studyId == target }?.parentStudyId != focus
                        } != false -> true
                    else -> false
                }
            }
        }) {
        throw VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
    }
    return VoiceTutorInputAssessmentResult(ordered)
}
