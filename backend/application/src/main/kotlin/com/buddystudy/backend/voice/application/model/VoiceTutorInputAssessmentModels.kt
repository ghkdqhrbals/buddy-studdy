package com.buddystudy.backend.voice.application.model

data class VoiceTutorStudyTargetCandidate(
    val studyId: Long,
    val parentStudyId: Long?,
    val topic: String,
    /** Server-private focus evidence. Assessment/provider payloads intentionally omit this field. */
    val difficulty: Int? = null,
)

enum class VoiceTutorStudyMutationContextSource {
    /** A confirmed focus and/or a candidate spoken in the immediately preceding tutor response. */
    FOCUS_OR_SPOKEN_OFFER,

    /** Complete, bounded owner read made before the first eligible learner turn of a fresh call. */
    INITIAL_OWNER_SNAPSHOT,
}

/**
 * Complete only when the owner had at most [MAX_CANDIDATES] saved nodes. An overflow, malformed
 * row, or resumed call is represented by no snapshot rather than a partial candidate window.
 */
data class VoiceTutorInitialStudyMutationSnapshot(
    val candidates: List<VoiceTutorStudyTargetCandidate>,
) {
    fun isValid(maxCandidates: Int = MAX_CANDIDATES): Boolean =
        candidates.size in 1..maxCandidates &&
            candidates.map(VoiceTutorStudyTargetCandidate::studyId).distinct().size == candidates.size &&
            candidates.map { canonicalTopicIdentity(it.topic) }.distinct().size == candidates.size &&
            candidates.all {
                    it.studyId > 0 && it.parentStudyId?.let { parent -> parent > 0 && parent != it.studyId } != false &&
                    it.topic.isNotBlank() && it.topic == it.topic.trim() && it.topic.length <= 255 &&
                    it.difficulty != null && it.difficulty in 1..10
            }

    companion object {
        const val MAX_CANDIDATES = 17

        /** Identity only; semantic intent is always decided by the independent model assessments. */
        internal fun canonicalTopicIdentity(value: String): String = buildString(value.length) {
            var pendingSpace = false
            for (character in value.trim().lowercase()) {
                if (character.isWhitespace()) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    append(character)
                    pendingSpace = false
                }
            }
        }
    }
}

/** Bounded server-read targets that one learner turn may mutate; provider text cannot add an ID. */
data class VoiceTutorStudyMutationContext(
    val lessonRevision: Long,
    val currentFocusStudyId: Long?,
    val candidates: List<VoiceTutorStudyTargetCandidate>,
    val source: VoiceTutorStudyMutationContextSource = VoiceTutorStudyMutationContextSource.FOCUS_OR_SPOKEN_OFFER,
) {
    fun isValid(maxCandidates: Int = 17): Boolean =
        lessonRevision >= 0 && currentFocusStudyId?.let { it > 0 } != false &&
            candidates.size in 1..maxCandidates &&
            candidates.map { it.studyId }.distinct().size == candidates.size &&
            currentFocusStudyId?.let { focus ->
                candidates.singleOrNull { it.studyId == focus } != null
            } != false &&
            candidates.all {
                it.studyId > 0 && it.parentStudyId?.let { parent -> parent > 0 && parent != it.studyId } != false &&
                    it.topic.isNotBlank() && it.topic == it.topic.trim() && it.topic.length <= 255 &&
                    it.difficulty?.let { difficulty -> difficulty in 1..10 } != false
            } && when (source) {
                VoiceTutorStudyMutationContextSource.FOCUS_OR_SPOKEN_OFFER -> true
                VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT ->
                    currentFocusStudyId == null &&
                        VoiceTutorInitialStudyMutationSnapshot(candidates).isValid()
            }
}

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

/**
 * Exact learner text whose durable USER-row write already completed in this call.
 * It may supply bounded referential context, but it never grants mutation authority
 * by itself; the newest [VoiceTutorInputUtterance.transcript] must still carry the
 * presently operative action.
 */
data class VoiceTutorPersistedLearnerUtterance(
    val itemId: String,
    val transcript: String,
) {
    fun isValid(maxTranscriptCharacters: Int = 4_000): Boolean =
        itemId.isNotBlank() && itemId.length <= 256 && itemId.none(Char::isISOControl) &&
            transcript.isNotBlank() && transcript.length <= maxTranscriptCharacters
}

/** Original ASR text. Assessment never normalizes, rewrites or persists this text. */
data class VoiceTutorInputUtterance(
    val itemId: String,
    val transcript: String,
    val checkpoint: Boolean = false,
    val targetOffer: VoiceTutorStudyTargetOffer? = null,
    /**
     * Assessment-only, bounded original ASR from earlier checkpoints in this same continuous
     * speech sequence, followed by [transcript]. Persistence still receives the exact raw event
     * for [transcript]; this context must never be used to rewrite either provider item.
     */
    val sameSpeechContext: String? = null,
    /**
     * Bounded, call-local USER items retained only after their persistence ACK.
     * Tutor/tool text, failed writes and unacknowledged items never enter this list.
     */
    val priorPersistedLearnerUtterances: List<VoiceTutorPersistedLearnerUtterance> = emptyList(),
    /** Frozen at this speech boundary from the current focus and verified call-local tree reads. */
    val studyMutationContext: VoiceTutorStudyMutationContext? = null,
) {
    fun persistedLearnerSource(): String? = priorPersistedLearnerUtterances
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "\n", postfix = "\n$transcript") { it.transcript }

    override fun toString(): String =
        "VoiceTutorInputUtterance(itemId=[redacted], transcriptCharacters=${transcript.length}, " +
            "sameSpeechContextCharacters=${sameSpeechContext?.length ?: 0}, " +
            "priorPersistedLearnerUtteranceCount=${priorPersistedLearnerUtterances.size})"
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

/** Independent post-playout attestation for one server-purposed tutor question. */
data class VoiceTutorSpokenQuestionAssessmentRequest(
    val userId: Long,
    val language: String,
    val focusTopic: String,
    val focusDifficulty: Int,
    val transcript: String,
) {
    override fun toString(): String =
        "VoiceTutorSpokenQuestionAssessmentRequest(transcriptCharacters=${transcript.length})"
}

/** Independent post-playout attestation for feedback about one exact learner study answer. */
data class VoiceTutorSpokenFeedbackAssessmentRequest(
    val userId: Long,
    val language: String,
    val focusTopic: String,
    val focusDifficulty: Int,
    val questionTranscript: String,
    val answerTranscript: String,
    val feedbackTranscript: String,
    /** Server proof that this same completed item also owns one verified saved-tree offer. */
    val allowsNavigationOffer: Boolean = false,
) {
    override fun toString(): String =
        "VoiceTutorSpokenFeedbackAssessmentRequest(questionCharacters=${questionTranscript.length}, " +
            "answerCharacters=${answerTranscript.length}, feedbackCharacters=${feedbackTranscript.length})"
}

enum class VoiceTutorInputDecision { MEANINGFUL, NON_COMMUNICATIVE }

enum class VoiceTutorInputIntent {
    NONE,
    END_CURRENT_VOICE_LESSON,
    /** The learner directly chooses to begin one new top-level saved study, including natural first-person intent. */
    CREATE_ROOT_STUDY,
    /** The learner directly chooses one exact child under a server-verified saved node. */
    CREATE_STUDY_TOPIC,
    /** The learner directly chooses an exact name and/or level patch for a server-verified saved node. */
    UPDATE_STUDY,
    /** The learner explicitly names a saved topic they want to enter or switch to. */
    SELECT_SAVED_TOPIC,
    /** The learner explicitly asks to continue deeper from the current saved-tree node. */
    CONTINUE_TREE,
    /** The learner names a saved area to browse before any exact server-owned candidate was offered. */
    DISCOVER_SAVED_TOPIC,
    /** The learner is answering the tutor's latest substantive study question. */
    ANSWER_TO_STUDY_QUESTION,
    /** The learner asks a substantive follow-up/deeper question about the confirmed study focus. */
    ASK_STUDY_QUESTION,
    /** The learner explicitly asks for the next question or to continue studying after a completed explanation. */
    CONTINUE_STUDY,
}

enum class VoiceTutorRootStudyEvidenceSource {
    TRANSCRIPT,
    SAME_SPEECH_CONTEXT,
    /** Prior durably persisted learner items followed by the exact current transcript. */
    PERSISTED_LEARNER_CONTEXT,
}

/**
 * Verbatim learner-owned evidence for one operative root-creation statement. These values are never normalized,
 * translated, or taken from tutor context. The semantic attestor separately proves that [topic]
 * spans the complete requested root name rather than a narrower substring.
 */
data class VoiceTutorRootStudyCreationEvidence(
    val source: VoiceTutorRootStudyEvidenceSource,
    val command: String,
    val topic: String,
    val difficulty: String?,
    val difficultyOmitted: Boolean,
) {
    fun isValidFor(topic: String, difficulty: Int): Boolean {
        if (command.isBlank() || command.length > 4_000 || command.length <= this.topic.length ||
            this.topic.isBlank() || this.topic != this.topic.trim() || this.topic.length > 255 ||
            this.topic != topic || !command.contains(this.topic)
        ) return false
        return if (difficultyOmitted) {
            this.difficulty == null && difficulty == 5
        } else {
            val exactDifficulty = this.difficulty
                ?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 32 }
                ?: return false
            // The two independent semantic assessments normalize spoken/written
            // number expressions (for example "세븐" or "seven") to 1..10.
            // Local code verifies only exact learner provenance and the bounded
            // normalized value; it deliberately has no regex or language table.
            command.contains(exactDifficulty) && difficulty in 1..10
        }
    }

    fun isExactLearnerEvidence(utterance: VoiceTutorInputUtterance): Boolean {
        val learnerSource = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> utterance.transcript
            // Checkpoint context still cannot authorize a write because it lacks
            // the explicit durable USER-item provenance carried by the list below.
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> return false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                utterance.persistedLearnerSource() ?: return false
        }
        val currentActionGrounded = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> true
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                command.substringAfterLast('\n').takeIf { it.isNotBlank() }
                    ?.let(utterance.transcript::contains) == true
        }
        return currentActionGrounded && learnerSource.contains(command) && command.contains(topic) &&
            (difficultyOmitted || difficulty?.let(command::contains) == true)
    }
}

/** Exact root fields extracted from the direct learner command, with the attested omitted-level default applied. */
data class VoiceTutorRootStudyCreationRequest(
    val topic: String,
    val difficulty: Int,
    val evidence: VoiceTutorRootStudyCreationEvidence,
    /** True only when two independent semantic assessments agree this same turn also starts the lesson now. */
    val startLessonAfterCreate: Boolean = false,
) {
    fun isValid(): Boolean =
        topic.isNotBlank() && topic == topic.trim() && topic.length <= 255 && difficulty in 1..10 &&
            evidence.isValidFor(topic, difficulty)
}

data class VoiceTutorChildStudyCreationEvidence(
    val source: VoiceTutorRootStudyEvidenceSource,
    val command: String,
    val parentTopic: String?,
    val topic: String,
    val difficulty: String?,
    val difficultyOmitted: Boolean,
    val parentImplicitCurrentFocus: Boolean,
) {
    fun isExactLearnerEvidence(utterance: VoiceTutorInputUtterance): Boolean {
        if (source != VoiceTutorRootStudyEvidenceSource.TRANSCRIPT) return false
        val learnerSource = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> utterance.transcript
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> utterance.sameSpeechContext ?: return false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                utterance.persistedLearnerSource() ?: return false
        }
        val parentValid = if (parentImplicitCurrentFocus) {
            parentTopic == null
        } else {
            parentTopic?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 255 }
                ?.let(command::contains) == true
        }
        val difficultyValid = if (difficultyOmitted) {
            difficulty == null
        } else {
            difficulty?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 32 }
                ?.let(command::contains) == true
        }
        return command.isNotBlank() && command.length <= 4_000 && command.length > topic.length &&
            parentTopic?.let { command.length > it.length } != false && learnerSource.contains(command) &&
            topic.isNotBlank() && topic == topic.trim() && topic.length <= 255 && command.contains(topic) &&
            parentValid && difficultyValid
    }
}

data class VoiceTutorChildStudyCreationRequest(
    val parentStudyId: Long,
    val topic: String,
    val difficulty: Int,
    val evidence: VoiceTutorChildStudyCreationEvidence,
) {
    fun isValidFor(utterance: VoiceTutorInputUtterance): Boolean {
        val context = utterance.studyMutationContext?.takeIf(VoiceTutorStudyMutationContext::isValid) ?: return false
        if (context.currentFocusStudyId == null) return false
        val parent = context.candidates.singleOrNull { it.studyId == parentStudyId } ?: return false
        if (topic.isBlank() || topic != topic.trim() || topic.length > 255 || difficulty !in 1..10 ||
            evidence.topic != topic || !evidence.isExactLearnerEvidence(utterance)
        ) return false
        if (evidence.parentImplicitCurrentFocus) {
            if (parentStudyId != context.currentFocusStudyId) return false
        } else if (evidence.parentTopic != parent.topic) {
            return false
        }
        return if (evidence.difficultyOmitted) {
            evidence.difficulty == null && difficulty == 5
        } else {
            evidence.difficulty != null && difficulty in 1..10
        }
    }
}

data class VoiceTutorStudyUpdateEvidence(
    val source: VoiceTutorRootStudyEvidenceSource,
    val command: String,
    val targetTopic: String?,
    val topic: String?,
    val difficulty: String?,
    val targetImplicitCurrentFocus: Boolean,
    /** True only for a semantic reference to the one exact candidate just spoken by the tutor. */
    val targetImplicitSpokenOffer: Boolean = false,
) {
    fun isExactLearnerEvidence(utterance: VoiceTutorInputUtterance): Boolean {
        val learnerSource = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> utterance.transcript
            // Checkpoint text is not durable learner evidence and can never
            // authorize a saved-tree write.
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> return false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                utterance.persistedLearnerSource() ?: return false
        }
        val currentActionGrounded = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> true
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                command.substringAfterLast('\n').takeIf { it.isNotBlank() }
                    ?.let(utterance.transcript::contains) == true
        }
        // This checks exact persistence framing only; semantic intent remains
        // exclusively the responsibility of the two independent assessments.
        val sourceStructureValid = when (source) {
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT -> learnerSource.contains(command)
            VoiceTutorRootStudyEvidenceSource.SAME_SPEECH_CONTEXT -> false
            VoiceTutorRootStudyEvidenceSource.PERSISTED_LEARNER_CONTEXT ->
                command.contains('\n') && learnerSource.endsWith(command)
        }
        if (targetImplicitCurrentFocus && targetImplicitSpokenOffer) return false
        val targetValid = when {
            targetImplicitCurrentFocus || targetImplicitSpokenOffer -> targetTopic == null
            else -> targetTopic?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 255 }
                ?.let(command::contains) == true
        }
        return command.isNotBlank() && command.length <= 4_000 &&
            listOfNotNull(targetTopic, topic, difficulty).all { command.length > it.length } &&
            currentActionGrounded && sourceStructureValid && targetValid && topic?.let {
                it.isNotBlank() && it == it.trim() && it.length <= 255 && command.contains(it)
            } != false && difficulty?.let {
                it.isNotBlank() && it == it.trim() && it.length <= 32 && command.contains(it)
            } != false
    }
}

data class VoiceTutorStudyUpdateRequest(
    val studyId: Long,
    val topic: String?,
    val difficulty: Int?,
    val evidence: VoiceTutorStudyUpdateEvidence,
    /** True only when both independent semantic assessments agree the completed update also starts study now. */
    val startLessonAfterUpdate: Boolean = false,
) {
    fun isValidFor(utterance: VoiceTutorInputUtterance): Boolean {
        val context = utterance.studyMutationContext?.takeIf(VoiceTutorStudyMutationContext::isValid) ?: return false
        val target = context.candidates.singleOrNull { it.studyId == studyId } ?: return false
        val difficultyEvidenceMatches = if (difficulty == null) {
            evidence.difficulty == null
        } else {
            evidence.difficulty != null && difficulty in 1..10
        }
        if (topic == null && difficulty == null || topic?.let { it.isBlank() || it != it.trim() || it.length > 255 } == true ||
            difficulty?.let { it !in 1..10 } == true || evidence.topic != topic ||
            !difficultyEvidenceMatches || !evidence.isExactLearnerEvidence(utterance)
        ) return false
        return when {
            context.source == VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT -> {
                utterance.targetOffer == null && context.currentFocusStudyId == null &&
                    evidence.source == VoiceTutorRootStudyEvidenceSource.TRANSCRIPT &&
                    !evidence.targetImplicitCurrentFocus && !evidence.targetImplicitSpokenOffer &&
                    evidence.targetTopic == target.topic &&
                    context.candidates.filter { it.topic == evidence.targetTopic }.singleOrNull() == target
            }
            evidence.targetImplicitCurrentFocus ->
                context.currentFocusStudyId != null && studyId == context.currentFocusStudyId
            evidence.targetImplicitSpokenOffer -> {
                val offer = utterance.targetOffer ?: return false
                offer.lessonRevision == context.lessonRevision &&
                    offer.currentFocusStudyId == context.currentFocusStudyId &&
                    offer.candidates.singleOrNull() == target &&
                    offer.tutorAudioTranscript.contains(target.topic)
            }
            else -> evidence.targetTopic == target.topic && if (studyId != context.currentFocusStudyId) {
                val offer = utterance.targetOffer ?: return false
                offer.lessonRevision == context.lessonRevision &&
                    offer.currentFocusStudyId == context.currentFocusStudyId &&
                    offer.candidates.singleOrNull { it.studyId == studyId } == target &&
                    offer.tutorAudioTranscript.contains(target.topic)
            } else true
        }
    }
}

data class VoiceTutorInputItemAssessment(
    val itemId: String,
    val decision: VoiceTutorInputDecision,
    val intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
    val targetStudyId: Long? = null,
    /** Candidates semantically attested as actually proposed in tutorAudioTranscript. */
    val spokenCandidateStudyIds: List<Long> = emptyList(),
    /** Present only for an operative CREATE_ROOT_STUDY statement; never derived from tool arguments. */
    val rootStudyCreationRequest: VoiceTutorRootStudyCreationRequest? = null,
    /**
     * True only when this item's exact transcript itself contributes content to the
     * learner's answer to the latest substantive tutor question. For a final tail,
     * sameSpeechContext may establish the whole-turn intent while this value remains
     * false (for example, when the durable answer is in an earlier checkpoint).
     */
    val currentTranscriptAnswersStudyQuestion: Boolean = false,
    /** Present only for an operative CREATE_STUDY_TOPIC statement. */
    val childStudyCreationRequest: VoiceTutorChildStudyCreationRequest? = null,
    /** Present only for an operative UPDATE_STUDY statement. */
    val studyUpdateRequest: VoiceTutorStudyUpdateRequest? = null,
) {
    override fun toString(): String =
        "VoiceTutorInputItemAssessment(itemId=[redacted], decision=$decision, intent=$intent, currentTranscriptAnswersStudyQuestion=$currentTranscriptAnswersStudyQuestion, hasTarget=${targetStudyId != null}, spokenCandidateCount=${spokenCandidateStudyIds.size})"
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
            val rootRequest = decision.rootStudyCreationRequest
            val childRequest = decision.childStudyCreationRequest
            val updateRequest = decision.studyUpdateRequest
            if (decision.decision == VoiceTutorInputDecision.NON_COMMUNICATIVE) {
                decision.intent != VoiceTutorInputIntent.NONE || decision.targetStudyId != null ||
                    decision.currentTranscriptAnswersStudyQuestion || spoken.isNotEmpty() || rootRequest != null ||
                    childRequest != null || updateRequest != null
            } else {
                val targetIntent = decision.intent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                    decision.intent == VoiceTutorInputIntent.CONTINUE_TREE
                val target = decision.targetStudyId
                when {
                    decision.currentTranscriptAnswersStudyQuestion &&
                        decision.intent != VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION -> true
                    !spokenValid -> true
                    utterance.checkpoint -> target != null || targetIntent || spoken.isNotEmpty() ||
                        decision.intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY ||
                        decision.intent == VoiceTutorInputIntent.CREATE_STUDY_TOPIC ||
                        decision.intent == VoiceTutorInputIntent.UPDATE_STUDY ||
                        decision.intent == VoiceTutorInputIntent.ASK_STUDY_QUESTION ||
                        decision.intent == VoiceTutorInputIntent.CONTINUE_STUDY
                    decision.intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
                        rootRequest?.isValid() != true -> true
                    decision.intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
                        rootRequest?.let { request ->
                            !request.evidence.isExactLearnerEvidence(utterance)
                        } != false -> true
                    decision.intent != VoiceTutorInputIntent.CREATE_ROOT_STUDY && rootRequest != null -> true
                    decision.intent == VoiceTutorInputIntent.CREATE_STUDY_TOPIC &&
                        childRequest?.isValidFor(utterance) != true -> true
                    decision.intent != VoiceTutorInputIntent.CREATE_STUDY_TOPIC && childRequest != null -> true
                    decision.intent == VoiceTutorInputIntent.UPDATE_STUDY &&
                        updateRequest?.isValidFor(utterance) != true -> true
                    decision.intent != VoiceTutorInputIntent.UPDATE_STUDY && updateRequest != null -> true
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
