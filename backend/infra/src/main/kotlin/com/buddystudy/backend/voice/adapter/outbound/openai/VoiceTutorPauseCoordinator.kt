package com.buddystudy.backend.voice.adapter.outbound.openai

import java.time.Duration
import java.util.UUID

/**
 * Explicit call hold, not speech interruption. The duplex controller owns this
 * object's lock and supplies its existing response/commit completion boundary.
 * This never cancels output, edits committed conversation items, or changes time.
 */
internal class VoiceTutorPauseCoordinator(
    responseTimeout: Duration,
) {
    enum class Phase { ACTIVE, PAUSING, PAUSED, RESUMING }

    sealed interface Action {
        data class ClearInput(val eventId: String) : Action
        data class Acknowledge(val sequence: Long, val paused: Boolean) : Action
    }

    private enum class ClearPurpose { PAUSE, RESUME, MAINTENANCE }
    private data class PendingClear(
        val eventId: String,
        val sequence: Long,
        val purpose: ClearPurpose,
        val requestedAtNanos: Long,
    )

    var phase: Phase = Phase.ACTIVE
        private set
    private var sequence = 0L
    private var inputQuiesced = false
    private var transitionStartedAtNanos: Long? = null
    private var pendingClear: PendingClear? = null
    private var lastClearAtNanos: Long? = null
    private val observedClearEventIds = LinkedHashSet<String>()
    private val pauseTimeout = responseTimeout.coerceAtMost(Duration.ofSeconds(120)).plusSeconds(10)
    private var closed = false

    val blocksResponses: Boolean get() = closed || phase != Phase.ACTIVE
    val acceptsSpeechEdges: Boolean get() = !closed &&
        (phase == Phase.ACTIVE || (phase == Phase.PAUSING && !inputQuiesced))
    val needsClock: Boolean get() = !closed && phase != Phase.ACTIVE

    fun requestPause(candidate: Long, now: Long): List<Action> {
        if (closed || candidate <= 0 || candidate < sequence) return emptyList()
        if (candidate == sequence) {
            return if (phase == Phase.PAUSED) listOf(Action.Acknowledge(sequence, true)) else emptyList()
        }
        if (phase != Phase.ACTIVE) return emptyList()
        sequence = candidate
        phase = Phase.PAUSING
        inputQuiesced = false
        transitionStartedAtNanos = now
        return emptyList()
    }

    fun confirmInputQuiesced(candidate: Long) {
        if (!closed && phase == Phase.PAUSING && candidate == sequence) inputQuiesced = true
    }

    fun requestResume(candidate: Long, now: Long): List<Action> {
        if (closed || candidate <= 0 || candidate < sequence) return emptyList()
        if (candidate == sequence) {
            return if (phase == Phase.ACTIVE) listOf(Action.Acknowledge(sequence, false)) else emptyList()
        }
        if (phase != Phase.PAUSED) return emptyList()
        sequence = candidate
        phase = Phase.RESUMING
        transitionStartedAtNanos = now
        // A maintenance clear already in flight remains its own operation. Its
        // acknowledgement cannot stand in for this resume's fresh input fence.
        return emptyList()
    }

    fun advance(boundaryReady: Boolean, now: Long): List<Action> {
        if (closed) return emptyList()
        checkTransitionDeadline(now)
        pendingClear?.let { pending ->
            if (elapsed(now, pending.requestedAtNanos) >= CLEAR_TIMEOUT.toNanos()) {
                throw VoiceTutorPauseAcknowledgementTimeoutException()
            }
            return emptyList()
        }
        return when (phase) {
            Phase.PAUSING -> if (inputQuiesced && boundaryReady) {
                clear(ClearPurpose.PAUSE, now)
            } else {
                emptyList()
            }
            Phase.RESUMING -> clear(ClearPurpose.RESUME, now)
            Phase.PAUSED -> if (lastClearAtNanos?.let { elapsed(now, it) >= PAUSED_CLEAR_INTERVAL.toNanos() } == true) {
                // The native microphone is muted but its WebRTC track stays
                // connected. Do not assume muted RTP carries no digital silence.
                // Only a held, quiesced input may be cleared; one ACK at a time.
                clear(ClearPurpose.MAINTENANCE, now)
            } else {
                emptyList()
            }
            Phase.ACTIVE -> emptyList()
        }
    }

    fun acknowledgeClear(providerEventId: String, now: Long): List<Action> {
        if (closed || providerEventId.isBlank() || providerEventId.length > MAX_EVENT_ID_CHARACTERS) return emptyList()
        // OpenAI's event_id identifies the server ACK, not the initiating clear.
        // Serialize clear requests and deduplicate server IDs, never equate it
        // with the client-generated event_id or accept a stale ACK twice.
        if (!observedClearEventIds.add(providerEventId)) return emptyList()
        while (observedClearEventIds.size > MAX_OBSERVED_ACKS) {
            observedClearEventIds.remove(observedClearEventIds.first())
        }
        val pending = pendingClear ?: return emptyList()
        checkTransitionDeadline(now)
        if (elapsed(now, pending.requestedAtNanos) >= CLEAR_TIMEOUT.toNanos()) {
            throw VoiceTutorPauseAcknowledgementTimeoutException()
        }
        pendingClear = null
        lastClearAtNanos = now
        return when (pending.purpose) {
            ClearPurpose.PAUSE -> if (phase == Phase.PAUSING && pending.sequence == sequence) {
                phase = Phase.PAUSED
                transitionStartedAtNanos = null
                listOf(Action.Acknowledge(sequence, true))
            } else {
                emptyList()
            }
            ClearPurpose.RESUME -> if (phase == Phase.RESUMING && pending.sequence == sequence) {
                phase = Phase.ACTIVE
                transitionStartedAtNanos = null
                inputQuiesced = false
                listOf(Action.Acknowledge(sequence, false))
            } else {
                emptyList()
            }
            ClearPurpose.MAINTENANCE -> emptyList()
        }
    }

    fun close() {
        closed = true
        pendingClear = null
        transitionStartedAtNanos = null
        observedClearEventIds.clear()
    }

    private fun clear(purpose: ClearPurpose, now: Long): List<Action> {
        check(pendingClear == null)
        val eventId = "buddystudy-internal-duplex-pause-input-clear-${UUID.randomUUID()}"
        pendingClear = PendingClear(eventId, sequence, purpose, now)
        return listOf(Action.ClearInput(eventId))
    }

    private fun checkTransitionDeadline(now: Long) {
        transitionStartedAtNanos?.let { started ->
            val timeout = if (phase == Phase.PAUSING) pauseTimeout else CLEAR_TIMEOUT.multipliedBy(2)
            if (elapsed(now, started) >= timeout.toNanos()) throw VoiceTutorPauseAcknowledgementTimeoutException()
        }
    }

    private fun elapsed(now: Long, since: Long): Long = (now - since).coerceAtLeast(0)

    private companion object {
        val CLEAR_TIMEOUT: Duration = Duration.ofSeconds(5)
        val PAUSED_CLEAR_INTERVAL: Duration = Duration.ofSeconds(30)
        const val MAX_OBSERVED_ACKS = 128
        const val MAX_EVENT_ID_CHARACTERS = 191
    }
}

internal class VoiceTutorPauseAcknowledgementTimeoutException :
    IllegalStateException("Voice Tutor pause acknowledgement timed out.")
