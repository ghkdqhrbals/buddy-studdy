package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** One expiring preview per authenticated call; a restart invalidates, never replays, a write. */
internal class VoiceTutorDeletionConfirmations(private val clock: Clock) {
    private val pending = linkedMapOf<String, Ticket>()

    @Synchronized
    fun prepare(context: VoiceTutorWebRtcControlContext, studyId: Long, ids: List<Long>, learnerTurnId: Long): Ticket? {
        pending.entries.removeIf { !clock.instant().isBefore(it.value.expiresAt) }
        if (context.session.id !in pending && pending.size >= 256) return null
        if (ids.isEmpty() || ids.size > 128 || studyId !in ids || ids.any { it <= 0 } || ids.distinct().size != ids.size || learnerTurnId <= 0) return null
        val principal = context.principal ?: return null
        val dialogue = context.dialogueBoundary ?: return null
        if (dialogue.responseGeneration <= 0 || dialogue.latestAcceptedLearnerSpeechStartedOrder <= 0) return null
        return Ticket(
            UUID.randomUUID().toString(), principal.userId, principal.deviceId, principal.sessionId,
            context.callId, studyId, ids.sorted(), learnerTurnId, dialogue.responseGeneration, clock.instant().plusSeconds(120),
        ).also { pending[context.session.id] = it }
    }

    @Synchronized
    fun consume(context: VoiceTutorWebRtcControlContext, studyId: Long, token: String, learnerTurnId: Long?): Ticket? {
        val ticket = pending[context.session.id] ?: return null
        val principal = context.principal ?: return null
        val dialogue = context.dialogueBoundary ?: return null
        if (!clock.instant().isBefore(ticket.expiresAt)) {
            pending.remove(context.session.id)
            return null
        }
        if (ticket.token != token || ticket.studyId != studyId || ticket.callId != context.callId ||
            ticket.userId != principal.userId || ticket.deviceId != principal.deviceId || ticket.authSessionId != principal.sessionId ||
            learnerTurnId == null || learnerTurnId <= ticket.learnerTurnId ||
            dialogue.precedingSpokenResponseGeneration <= ticket.previewResponseGeneration ||
            dialogue.precedingTutorSpeechStoppedOrder <= 0 ||
            dialogue.latestAcceptedLearnerSpeechStartedOrder <= dialogue.precedingTutorSpeechStoppedOrder
        ) return null
        // Consume before any suspended mutation. Timeout, duplicate tool calls and reconnects
        // must not replay a possibly committed destructive operation.
        pending.remove(context.session.id)
        return ticket
    }

    internal data class Ticket(
        val token: String,
        val userId: Long,
        val deviceId: String,
        val authSessionId: Long,
        val callId: String,
        val studyId: Long,
        val studyIds: List<Long>,
        val learnerTurnId: Long,
        val previewResponseGeneration: Long,
        val expiresAt: Instant,
    )
}
