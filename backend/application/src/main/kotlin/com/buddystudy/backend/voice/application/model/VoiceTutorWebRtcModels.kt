package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.backend.auth.Principal

data class VoiceTutorWebRtcControlContext(
    val session: VoiceTutorSession,
    val callId: String,
    // Server-authenticated identity, never a bearer token or caller-supplied user id.
    val principal: Principal? = null,
    // Server-owned lesson epoch restored when the existing call is reattached.
    val initialLessonRevision: Long = 0,
    // Observed by the controller at tool execution; never client/provider JSON.
    val dialogueBoundary: VoiceTutorDialogueBoundary? = null,
)

/** Tutor boundaries are frozen at the last accepted learner item's speech start, not tool completion. */
data class VoiceTutorDialogueBoundary(
    val responseGeneration: Long,
    val latestAcceptedLearnerSpeechStartedOrder: Long,
    val precedingTutorSpeechStoppedOrder: Long,
    val precedingSpokenResponseGeneration: Long,
)
