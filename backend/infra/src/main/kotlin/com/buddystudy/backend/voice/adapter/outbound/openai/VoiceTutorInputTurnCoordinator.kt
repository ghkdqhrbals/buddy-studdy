package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorPersistedLearnerUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.correlatedTo

/**
 * Pure, per-call state. Every method and property belongs to the caller's single
 * duplex lock. Actions are commands, not completed side effects: publishing a
 * transcript and deleting a provider item both need their explicit confirmation.
 *
 * Only the owner may register an acknowledged USER item or supply its original
 * completed-ASR event. Unknown/transient provider events can never grant Ready.
 * No audio, provider controls, callbacks, threads or text heuristics live here.
 */
internal class VoiceTutorInputTurnCoordinator(
    limits: VoiceTutorInputAssessmentProperties = VoiceTutorInputAssessmentProperties(),
) {
    sealed interface Action {
        data class Assess(
            val token: Long,
            val teacherContext: String,
            val utterances: List<VoiceTutorInputUtterance>,
        ) : Action {
            override fun toString(): String =
                "Assess(token=$token, contextCharacters=${teacherContext.length}, utteranceCount=${utterances.size})"
        }

        data class Publish(
            val itemId: String,
            val rawEvent: String,
            val sequence: Long,
            val checkpoint: Boolean = false,
            val intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
            val targetStudyId: Long? = null,
            val targetOfferId: Long? = null,
            val rootStudyCreationRequest: VoiceTutorRootStudyCreationRequest? = null,
            val childStudyCreationRequest: VoiceTutorChildStudyCreationRequest? = null,
            val studyUpdateRequest: VoiceTutorStudyUpdateRequest? = null,
            val currentTranscriptAnswersStudyQuestion: Boolean = false,
        ) : Action {
            override fun toString(): String = "Publish(itemId=[redacted], eventCharacters=${rawEvent.length})"
        }

        data class Delete(val itemId: String) : Action {
            override fun toString(): String = "Delete(itemId=[redacted])"
        }

        data class Retry(val reason: RetryReason) : Action
        data class Ready(val sequence: Long, val checkpoint: Boolean) : Action
    }

    enum class RetryReason {
        TRANSCRIPTION_FAILED,
        TRANSCRIPTION_TIMEOUT,
        INPUT_TOO_LARGE,
        ASSESSMENT_FAILED,
        ASSESSMENT_TIMEOUT,
        ASSESSMENT_UNAVAILABLE,
        ASSESSMENT_BUSY,
        ASSESSMENT_REFUSED,
        INVALID_ASSESSMENT_RESULT,
    }

    private val limits = limits.copy()
    private val assessmentLifetimeNanos: Long
    private val pending = linkedMapOf<String, PendingItem>()
    private val recentItemIds = linkedSetOf<String>()
    private val earlyTranscripts = linkedMapOf<String, EarlyTranscript>()
    private val checkpointContextBySequence = linkedMapOf<Long, String>()
    private val persistedLearnerContext = linkedMapOf<String, VoiceTutorPersistedLearnerUtterance>()
    private var batch: AssessmentBatch? = null
    private var nextBatchToken = 1L
    private var contextGeneration = 0L
    private var teacherContext = ""
    private var teacherContextReady = true

    var isClosed: Boolean = false
        private set

    val hasPending: Boolean get() = pending.isNotEmpty()
    val pendingCount: Int get() = pending.size
    val bufferedTranscriptCount: Int get() = earlyTranscripts.size

    fun isAssessmentCurrent(token: Long): Boolean = !isClosed && batch?.token == token

    fun isPublicationPending(itemId: String): Boolean = !isClosed && pending[itemId]?.stage == Stage.WAITING_PUBLISH

    init {
        require(this.limits.timeoutMilliseconds in 1..15_000) { "Invalid voice input assessment timeout." }
        require(this.limits.admissionTimeoutMilliseconds in 1..5_000) {
            "Invalid voice input assessment admission timeout."
        }
        require(this.limits.maxUtterances in 1..8) { "Invalid voice input assessment batch bound." }
        require(this.limits.maxTranscriptCharacters in 1..4_000) { "Invalid voice input assessment text bound." }
        require(this.limits.maxBatchTranscriptCharacters in 1..16_000) { "Invalid voice input assessment total text bound." }
        require(this.limits.maxTeacherContextCharacters in 0..4_000) { "Invalid voice input assessment context bound." }
        // The use case can first wait for bounded process-wide admission and
        // then spend its independent provider timeout. Start this lifetime at
        // Action.Assess creation so a valid queued result is not expired by a
        // shorter coordinator-only clock.
        assessmentLifetimeNanos =
            (this.limits.admissionTimeoutMilliseconds + this.limits.timeoutMilliseconds) * NANOS_PER_MILLISECOND
    }

    fun observeCommitted(
        itemId: String,
        sequence: Long,
        checkpoint: Boolean,
        nowNanos: Long,
        targetOffer: VoiceTutorStudyTargetOffer? = null,
        studyMutationContext: VoiceTutorStudyMutationContext? = null,
    ): List<Action> {
        if (isClosed || itemId in pending || itemId in recentItemIds) return emptyList()
        if (!validItemId(itemId) || sequence <= 0) fail(VoiceTutorInputTurnCoordinatorFailure.INVALID_COMMITTED_ITEM)
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        if (pending.size >= MAX_PENDING_ITEMS) fail(VoiceTutorInputTurnCoordinatorFailure.PENDING_CAPACITY_EXCEEDED)
        val item = PendingItem(
            itemId, sequence, checkpoint, Stage.WAITING_TRANSCRIPT, nowNanos,
            targetOffer, studyMutationContext,
        )
        pending[itemId] = item
        remember(itemId)
        earlyTranscripts.remove(itemId)?.let { applyTranscript(item, it, nowNanos, actions) }
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    fun observeTranscript(
        itemId: String,
        transcript: String,
        rawEvent: String,
        nowNanos: Long,
    ): List<Action> {
        if (isClosed || !validItemId(itemId)) return emptyList()
        val event = if (
            transcript.length > limits.maxTranscriptCharacters ||
            transcript.length > limits.maxBatchTranscriptCharacters ||
            rawEvent.length > MAX_RAW_EVENT_CHARACTERS
        ) {
            // Do not retain or silently truncate an out-of-bounds user turn.
            EarlyTranscript.Rejected(RetryReason.INPUT_TOO_LARGE, nowNanos)
        } else {
            EarlyTranscript.Completed(transcript, rawEvent, nowNanos)
        }
        return observeTranscriptEvent(itemId, event, nowNanos)
    }

    fun observeTranscriptionFailure(itemId: String, nowNanos: Long): List<Action> {
        if (isClosed || !validItemId(itemId)) return emptyList()
        return observeTranscriptEvent(
            itemId,
            EarlyTranscript.Rejected(RetryReason.TRANSCRIPTION_FAILED, nowNanos),
            nowNanos,
        )
    }

    fun teacherResponseStarted() {
        if (isClosed) return
        if (teacherContextReady) contextGeneration += 1
        teacherContextReady = false
        // Keep the single outstanding batch until its callback or deadline. A
        // result from the previous context cannot approve an utterance below.
    }

    fun teacherResponseCompleted(context: String, nowNanos: Long): List<Action> {
        if (isClosed) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        val completed = context.takeIf { it.isNotBlank() }
            ?.takeLast(limits.maxTeacherContextCharacters)
            ?: teacherContext
        if (teacherContextReady && completed != teacherContext) contextGeneration += 1
        teacherContext = completed
        teacherContextReady = true
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    fun completeAssessment(
        batchToken: Long,
        result: Result<VoiceTutorInputAssessmentResult>,
        nowNanos: Long,
    ): List<Action> {
        if (isClosed || batch?.token != batchToken) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        val current = batch
        if (current == null || current.token != batchToken) {
            startAssessmentIfPossible(nowNanos, actions)
            return actions
        }
        batch = null
        if (!teacherContextReady || current.contextGeneration != contextGeneration) {
            restoreForCurrentContext(current, nowNanos)
            startAssessmentIfPossible(nowNanos, actions)
            return actions
        }
        val assessed = result.getOrNull()
        if (assessed == null) {
            rejectBatch(current, assessmentFailure(result.exceptionOrNull()), nowNanos, actions)
        } else {
            val correlated = try {
                assessed.correlatedTo(current.utterances)
            } catch (_: VoiceTutorInputAssessmentException) {
                null
            }
            if (correlated == null) {
                rejectBatch(current, RetryReason.INVALID_ASSESSMENT_RESULT, nowNanos, actions)
            } else {
                correlated.decisions.forEach { decision ->
                    val item = pending.getValue(decision.itemId)
                    when (decision.decision) {
                        VoiceTutorInputDecision.MEANINGFUL -> {
                            item.stage = Stage.WAITING_PUBLISH
                            item.stageStartedAt = nowNanos
                            actions += Action.Publish(
                                itemId = item.itemId,
                                rawEvent = requireNotNull(item.rawEvent),
                                sequence = item.sequence,
                                checkpoint = item.checkpoint,
                                intent = decision.intent,
                                targetStudyId = decision.targetStudyId,
                                targetOfferId = item.targetOffer?.offerId,
                                rootStudyCreationRequest = decision.rootStudyCreationRequest,
                                childStudyCreationRequest = decision.childStudyCreationRequest,
                                studyUpdateRequest = decision.studyUpdateRequest,
                                currentTranscriptAnswersStudyQuestion =
                                    decision.currentTranscriptAnswersStudyQuestion,
                            )
                        }
                        VoiceTutorInputDecision.NON_COMMUNICATIVE -> delete(item, nowNanos, actions)
                    }
                }
            }
        }
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    fun confirmPublished(itemId: String, nowNanos: Long, persisted: Boolean = true): List<Action> {
        if (isClosed || pending[itemId]?.stage != Stage.WAITING_PUBLISH) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        val item = pending.remove(itemId) ?: return actions
        if (persisted) {
            rememberPersistedLearnerContext(item.itemId, requireNotNull(item.transcript))
        }
        if (item.checkpoint) {
            rememberCheckpointContext(item.sequence, requireNotNull(item.transcript))
        } else {
            checkpointContextBySequence.remove(item.sequence)
        }
        actions += Action.Ready(item.sequence, item.checkpoint)
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    fun confirmDeleted(itemId: String, nowNanos: Long): List<Action> {
        if (isClosed || pending[itemId]?.stage != Stage.WAITING_DELETE) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        pending.remove(itemId)?.takeUnless { it.checkpoint }?.let {
            checkpointContextBySequence.remove(it.sequence)
        }
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    /** Releases assessment-only checkpoint text after an exact final empty-buffer outcome. */
    fun discardSpeechSequence(sequence: Long) {
        if (!isClosed && sequence > 0 && pending.values.none { it.sequence == sequence }) {
            checkpointContextBySequence.remove(sequence)
        }
    }

    /** Consume old referential slots after one attested study mutation owns them. */
    fun clearPersistedLearnerContext() {
        if (!isClosed) persistedLearnerContext.clear()
    }

    fun expire(nowNanos: Long): List<Action> {
        if (isClosed) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    fun close() {
        isClosed = true
        pending.clear()
        earlyTranscripts.clear()
        checkpointContextBySequence.clear()
        persistedLearnerContext.clear()
        recentItemIds.clear()
        batch = null
        teacherContext = ""
        teacherContextReady = false
    }

    private fun observeTranscriptEvent(itemId: String, event: EarlyTranscript, nowNanos: Long): List<Action> {
        if (itemId !in pending && itemId in recentItemIds) return emptyList()
        val actions = mutableListOf<Action>()
        expireStages(nowNanos, actions)
        val item = pending[itemId]
        if (item == null) {
            // ASR may race ahead of its input commit acknowledgement. It has no
            // authority until observeCommitted registers that exact USER ID.
            if (itemId !in earlyTranscripts) {
                if (earlyTranscripts.size == MAX_EARLY_TRANSCRIPTS) {
                    earlyTranscripts.remove(earlyTranscripts.keys.first())
                }
                earlyTranscripts[itemId] = event
            }
        } else if (item.stage == Stage.WAITING_TRANSCRIPT) {
            applyTranscript(item, event, nowNanos, actions)
        }
        startAssessmentIfPossible(nowNanos, actions)
        return actions
    }

    private fun applyTranscript(
        item: PendingItem,
        event: EarlyTranscript,
        nowNanos: Long,
        actions: MutableList<Action>,
    ) {
        when (event) {
            is EarlyTranscript.Rejected -> {
                retryOnce(event.reason, actions)
                delete(item, nowNanos, actions)
            }
            is EarlyTranscript.Completed -> if (event.transcript.isBlank()) {
                // Absence of text is not a semantic guess about a spoken word.
                delete(item, nowNanos, actions)
            } else {
                item.transcript = event.transcript
                item.rawEvent = event.rawEvent
                item.stage = Stage.WAITING_ASSESSMENT
                item.stageStartedAt = nowNanos
            }
        }
    }

    private fun startAssessmentIfPossible(nowNanos: Long, actions: MutableList<Action>) {
        if (isClosed || !teacherContextReady || batch != null) return
        val selected = mutableListOf<PendingItem>()
        val semanticContexts = linkedMapOf<String, String?>()
        val persistedContexts = linkedMapOf<String, List<VoiceTutorPersistedLearnerUtterance>>()
        var characters = 0
        // Preserve acknowledged turn order, including ASR arriving out of order.
        // An older unpersisted/deleting item cannot be overtaken by a new batch.
        for (item in pending.values) {
            if (item.stage != Stage.WAITING_ASSESSMENT) break
            val text = requireNotNull(item.transcript)
            val offerCharacters = item.targetOffer?.tutorAudioTranscript?.length ?: 0
            val mutationCharacters = item.studyMutationContext?.candidates.orEmpty().sumOf { it.topic.length }
            val persistedContext = boundedPersistedLearnerContext(
                limits.maxBatchTranscriptCharacters - characters - text.length - offerCharacters - mutationCharacters,
            )
            val persistedContextCharacters = persistedContext.sumOf { it.transcript.length }
            val remainingAfterTranscript =
                limits.maxBatchTranscriptCharacters - characters - text.length - offerCharacters - mutationCharacters -
                    persistedContextCharacters
            if (selected.size == limits.maxUtterances || remainingAfterTranscript < 0) break
            val sameSpeechContext = if (item.checkpoint) {
                null
            } else {
                completedSpeechContext(item.sequence, text, minOf(limits.maxTranscriptCharacters, remainingAfterTranscript))
            }
            selected += item
            semanticContexts[item.itemId] = sameSpeechContext
            persistedContexts[item.itemId] = persistedContext
            characters += text.length + offerCharacters + mutationCharacters + persistedContextCharacters +
                (sameSpeechContext?.length ?: 0)
            // Every item in this batch receives the same frozen snapshot made
            // only of earlier persistence acknowledgements. Co-batched USER
            // items can still correct one another's conversational intent, but
            // none can become persisted mutation evidence for another.
        }
        if (selected.isEmpty()) return
        if (nextBatchToken <= 0 || nextBatchToken == Long.MAX_VALUE) {
            fail(VoiceTutorInputTurnCoordinatorFailure.BATCH_TOKEN_EXHAUSTED)
        }
        val token = nextBatchToken++
        val utterances = selected.map {
            VoiceTutorInputUtterance(
                it.itemId,
                requireNotNull(it.transcript),
                checkpoint = it.checkpoint,
                targetOffer = it.targetOffer,
                sameSpeechContext = semanticContexts[it.itemId],
                priorPersistedLearnerUtterances = persistedContexts.getValue(it.itemId),
                studyMutationContext = it.studyMutationContext,
            )
        }
        selected.forEach {
            it.stage = Stage.ASSESSING
            it.stageStartedAt = nowNanos
        }
        batch = AssessmentBatch(token, utterances, contextGeneration, nowNanos)
        actions += Action.Assess(token, teacherContext, utterances)
    }

    private fun expireStages(nowNanos: Long, actions: MutableList<Action>) {
        earlyTranscripts.entries.removeAll { expired(it.value.observedAt, nowNanos, ASR_TIMEOUT_NANOS) }
        // Side effects whose success cannot be established are terminal. Never
        // grant Ready, retry response creation, or forget an undeleted item.
        for (item in pending.values) {
            if (!expired(item.stageStartedAt, nowNanos, ACK_TIMEOUT_NANOS)) continue
            when (item.stage) {
                Stage.WAITING_PUBLISH -> fail(VoiceTutorInputTurnCoordinatorFailure.PUBLISH_ACK_TIMEOUT)
                Stage.WAITING_DELETE -> fail(VoiceTutorInputTurnCoordinatorFailure.DELETE_ACK_TIMEOUT)
                else -> Unit
            }
        }
        for (item in pending.values) {
            if (item.stage == Stage.WAITING_TRANSCRIPT && expired(item.stageStartedAt, nowNanos, ASR_TIMEOUT_NANOS)) {
                retryOnce(RetryReason.TRANSCRIPTION_TIMEOUT, actions)
                delete(item, nowNanos, actions)
            }
        }
        val current = batch ?: return
        if (!expired(current.startedAt, nowNanos, assessmentLifetimeNanos)) return
        batch = null
        if (!teacherContextReady || current.contextGeneration != contextGeneration) {
            restoreForCurrentContext(current, nowNanos)
        } else {
            rejectBatch(current, RetryReason.ASSESSMENT_TIMEOUT, nowNanos, actions)
        }
    }

    private fun restoreForCurrentContext(current: AssessmentBatch, nowNanos: Long) {
        current.utterances.forEach { utterance ->
            pending[utterance.itemId]?.takeIf { it.stage == Stage.ASSESSING }?.let {
                it.stage = Stage.WAITING_ASSESSMENT
                it.stageStartedAt = nowNanos
            }
        }
    }

    private fun rejectBatch(
        current: AssessmentBatch,
        reason: RetryReason,
        nowNanos: Long,
        actions: MutableList<Action>,
    ) {
        retryOnce(reason, actions)
        current.utterances.forEach { utterance -> delete(pending.getValue(utterance.itemId), nowNanos, actions) }
    }

    private fun delete(item: PendingItem, nowNanos: Long, actions: MutableList<Action>) {
        item.stage = Stage.WAITING_DELETE
        item.stageStartedAt = nowNanos
        item.transcript = null
        item.rawEvent = null
        actions += Action.Delete(item.itemId)
    }

    private fun retryOnce(reason: RetryReason, actions: MutableList<Action>) {
        if (actions.none { it is Action.Retry && it.reason == reason }) actions += Action.Retry(reason)
    }

    private fun assessmentFailure(error: Throwable?): RetryReason = when ((error as? VoiceTutorInputAssessmentException)?.reason) {
        VoiceTutorInputAssessmentFailure.TIMEOUT -> RetryReason.ASSESSMENT_TIMEOUT
        VoiceTutorInputAssessmentFailure.BUSY -> RetryReason.ASSESSMENT_BUSY
        VoiceTutorInputAssessmentFailure.UNAVAILABLE -> RetryReason.ASSESSMENT_UNAVAILABLE
        VoiceTutorInputAssessmentFailure.REFUSED -> RetryReason.ASSESSMENT_REFUSED
        VoiceTutorInputAssessmentFailure.INVALID_RESULT -> RetryReason.INVALID_ASSESSMENT_RESULT
        else -> RetryReason.ASSESSMENT_FAILED
    }

    private fun remember(itemId: String) {
        recentItemIds += itemId
        if (recentItemIds.size > MAX_RECENT_ITEMS) recentItemIds.remove(recentItemIds.first())
    }

    private fun rememberCheckpointContext(sequence: Long, transcript: String) {
        val previous = checkpointContextBySequence.remove(sequence)
        val combined = if (previous.isNullOrEmpty()) transcript else "$previous\n$transcript"
        checkpointContextBySequence[sequence] = combined.takeLast(limits.maxTranscriptCharacters)
        while (checkpointContextBySequence.size > MAX_CHECKPOINT_CONTEXTS) {
            checkpointContextBySequence.remove(checkpointContextBySequence.keys.first())
        }
    }

    private fun rememberPersistedLearnerContext(itemId: String, transcript: String) {
        persistedLearnerContext.remove(itemId)
        persistedLearnerContext[itemId] = VoiceTutorPersistedLearnerUtterance(itemId, transcript)
        while (persistedLearnerContext.size > MAX_PERSISTED_LEARNER_CONTEXT_ITEMS ||
            persistedLearnerContext.values.sumOf { it.transcript.length } > limits.maxTranscriptCharacters
        ) {
            persistedLearnerContext.remove(persistedLearnerContext.keys.first())
        }
    }

    private fun boundedPersistedLearnerContext(maxCharacters: Int): List<VoiceTutorPersistedLearnerUtterance> {
        if (maxCharacters <= 0) return emptyList()
        var characters = 0
        val selected = ArrayDeque<VoiceTutorPersistedLearnerUtterance>()
        for (item in persistedLearnerContext.values.toList().asReversed()) {
            if (characters + item.transcript.length > maxCharacters) break
            selected.addFirst(item)
            characters += item.transcript.length
        }
        return selected.toList()
    }

    private fun completedSpeechContext(sequence: Long, transcript: String, maxCharacters: Int): String? {
        val checkpoint = checkpointContextBySequence[sequence]?.takeIf { it.isNotBlank() } ?: return null
        if (maxCharacters <= transcript.length + 1) return null
        val prefixBudget = maxCharacters - transcript.length - 1
        return checkpoint.takeLast(prefixBudget) + "\n" + transcript
    }

    private fun fail(reason: VoiceTutorInputTurnCoordinatorFailure): Nothing {
        close()
        throw VoiceTutorInputTurnCoordinatorException(reason)
    }

    private fun validItemId(itemId: String): Boolean =
        itemId.isNotBlank() && itemId.length <= MAX_ITEM_ID_CHARACTERS && itemId.none(Char::isISOControl)

    // Signed subtraction preserves elapsed time across System.nanoTime wrap;
    // a backwards test timestamp never prematurely expires an active stage.
    private fun expired(startedAt: Long, nowNanos: Long, timeout: Long): Boolean = nowNanos - startedAt >= timeout

    private enum class Stage { WAITING_TRANSCRIPT, WAITING_ASSESSMENT, ASSESSING, WAITING_PUBLISH, WAITING_DELETE }

    private class PendingItem(
        val itemId: String,
        val sequence: Long,
        val checkpoint: Boolean,
        var stage: Stage,
        var stageStartedAt: Long,
        val targetOffer: VoiceTutorStudyTargetOffer? = null,
        val studyMutationContext: VoiceTutorStudyMutationContext? = null,
        var transcript: String? = null,
        var rawEvent: String? = null,
    )

    private sealed interface EarlyTranscript {
        val observedAt: Long

        class Completed(val transcript: String, val rawEvent: String, override val observedAt: Long) : EarlyTranscript
        class Rejected(val reason: RetryReason, override val observedAt: Long) : EarlyTranscript
    }

    private class AssessmentBatch(
        val token: Long,
        val utterances: List<VoiceTutorInputUtterance>,
        val contextGeneration: Long,
        val startedAt: Long,
    )

    private companion object {
        const val MAX_PENDING_ITEMS = 16
        const val MAX_EARLY_TRANSCRIPTS = 16
        const val MAX_RECENT_ITEMS = 64
        const val MAX_CHECKPOINT_CONTEXTS = 16
        const val MAX_PERSISTED_LEARNER_CONTEXT_ITEMS = 3
        const val MAX_ITEM_ID_CHARACTERS = 256
        const val MAX_RAW_EVENT_CHARACTERS = 64_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val ASR_TIMEOUT_NANOS = 8_000_000_000L
        const val ACK_TIMEOUT_NANOS = 5_000_000_000L
    }
}

internal enum class VoiceTutorInputTurnCoordinatorFailure {
    INVALID_COMMITTED_ITEM,
    PENDING_CAPACITY_EXCEEDED,
    PUBLISH_ACK_TIMEOUT,
    DELETE_ACK_TIMEOUT,
    BATCH_TOKEN_EXHAUSTED,
}

internal class VoiceTutorInputTurnCoordinatorException(val reason: VoiceTutorInputTurnCoordinatorFailure) :
    IllegalStateException("Voice Tutor input turn failed: ${reason.name}.")
