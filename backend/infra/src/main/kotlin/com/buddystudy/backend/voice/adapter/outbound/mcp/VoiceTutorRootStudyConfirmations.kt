package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** One expiring create-only preview per authenticated call; restart invalidates it safely. */
internal class VoiceTutorRootStudyConfirmations(private val clock: Clock) {
    private val pending = linkedMapOf<String, Ticket>()

    @Synchronized
    fun prepare(
        context: VoiceTutorWebRtcControlContext,
        topic: String,
        difficulty: Int,
        learnerTurnId: Long,
        lessonRevision: Long,
    ): Ticket? {
        pending.entries.removeIf { !clock.instant().isBefore(it.value.expiresAt) }
        if (context.session.id !in pending && pending.size >= MAX_PENDING_CALLS) return null
        val principal = context.principal ?: return null
        val dialogue = context.dialogueBoundary ?: return null
        if (!validTopic(topic) || difficulty !in 1..10 || learnerTurnId <= 0 || lessonRevision < 0 ||
            dialogue.latestAcceptedLearnerIntent != VoiceTutorInputIntent.CREATE_ROOT_STUDY ||
            dialogue.latestAcceptedLearnerProviderItemId.isNullOrBlank() ||
            dialogue.responseGeneration <= 0 || dialogue.latestAcceptedLearnerSpeechStartedOrder <= 0
        ) return null
        pending[context.session.id]?.let { existing ->
            if (
                existing.userId == principal.userId && existing.deviceId == principal.deviceId &&
                    existing.authSessionId == principal.sessionId && existing.callId == context.callId &&
                    existing.topic == topic && existing.difficulty == difficulty &&
                    existing.learnerTurnId == learnerTurnId && existing.lessonRevision == lessonRevision &&
                    existing.previewResponseGeneration == dialogue.responseGeneration
            ) return existing
            // A new direct learner request deliberately supersedes the old preview. This is
            // what makes "not A; create B" safe: A's token is removed before B is offered.
            if (existing.userId != principal.userId || existing.deviceId != principal.deviceId ||
                existing.authSessionId != principal.sessionId || existing.callId != context.callId ||
                existing.lessonRevision != lessonRevision || learnerTurnId <= existing.learnerTurnId ||
                dialogue.responseGeneration <= existing.previewResponseGeneration
            ) return null
            pending.remove(context.session.id)
        }
        return Ticket(
            token = UUID.randomUUID().toString(),
            userId = principal.userId,
            deviceId = principal.deviceId,
            authSessionId = principal.sessionId,
            callId = context.callId,
            topic = topic,
            difficulty = difficulty,
            learnerTurnId = learnerTurnId,
            lessonRevision = lessonRevision,
            previewResponseGeneration = dialogue.responseGeneration,
            expiresAt = clock.instant().plusSeconds(EXPIRY_SECONDS),
        ).also { pending[context.session.id] = it }
    }

    @Synchronized
    fun consume(
        context: VoiceTutorWebRtcControlContext,
        topic: String,
        difficulty: Int,
        token: String,
        learnerTurnId: Long?,
        lessonRevision: Long,
    ): Ticket? {
        val ticket = pending[context.session.id] ?: return null
        val principal = context.principal ?: return null
        val dialogue = context.dialogueBoundary ?: return null
        if (!clock.instant().isBefore(ticket.expiresAt)) {
            pending.remove(context.session.id)
            return null
        }
        if (ticket.token != token || token.length > MAX_TOKEN_CHARACTERS ||
            ticket.userId != principal.userId || ticket.deviceId != principal.deviceId ||
            ticket.authSessionId != principal.sessionId || ticket.callId != context.callId ||
            ticket.topic != topic || ticket.difficulty != difficulty ||
            ticket.lessonRevision != lessonRevision ||
            dialogue.latestAcceptedLearnerIntent != VoiceTutorInputIntent.CONFIRM_ROOT_STUDY ||
            dialogue.latestAcceptedLearnerLessonRevision != lessonRevision ||
            dialogue.latestAcceptedLearnerProviderItemId.isNullOrBlank() ||
            learnerTurnId == null || learnerTurnId <= ticket.learnerTurnId ||
            !matchesExactSpokenOffer(dialogue.latestAcceptedLearnerRootStudyCreationOffer, ticket, dialogue) ||
            dialogue.latestAcceptedLearnerSpeechStartedOrder <= dialogue.precedingTutorSpeechStoppedOrder
        ) return null
        // Consume before the suspended create-only write. A timeout or duplicate tool
        // call must never replay a root whose commit outcome may be uncertain.
        pending.remove(context.session.id)
        return ticket
    }

    private fun matchesExactSpokenOffer(
        offer: VoiceTutorRootStudyCreationOffer?,
        ticket: Ticket,
        dialogue: com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary,
    ): Boolean = offer != null &&
        offer.topic == ticket.topic && offer.difficulty == ticket.difficulty &&
        offer.lessonRevision == ticket.lessonRevision &&
        offer.previewResponseGeneration == ticket.previewResponseGeneration &&
        offer.tutorResponseGeneration > ticket.previewResponseGeneration &&
        offer.tutorResponseGeneration == dialogue.precedingSpokenResponseGeneration &&
        offer.tutorSpeechStoppedOrder > 0 &&
        offer.tutorSpeechStoppedOrder == dialogue.precedingTutorSpeechStoppedOrder &&
        offer.tutorProviderItemId == dialogue.precedingTutorProviderItemId &&
        offer.tutorProviderItemId.isNotBlank() && offer.tutorAudioTranscript.isNotBlank() &&
        dialogue.responseGeneration > offer.tutorResponseGeneration

    private fun validTopic(topic: String): Boolean =
        topic.isNotBlank() && topic.length <= MAX_TOPIC_CHARACTERS && topic == topic.trim()

    internal data class Ticket(
        val token: String,
        val userId: Long,
        val deviceId: String,
        val authSessionId: Long,
        val callId: String,
        val topic: String,
        val difficulty: Int,
        val learnerTurnId: Long,
        val lessonRevision: Long,
        val previewResponseGeneration: Long,
        val expiresAt: Instant,
    )

    private companion object {
        const val EXPIRY_SECONDS = 120L
        const val MAX_PENDING_CALLS = 256
        const val MAX_TOPIC_CHARACTERS = 255
        const val MAX_TOKEN_CHARACTERS = 100
    }
}
