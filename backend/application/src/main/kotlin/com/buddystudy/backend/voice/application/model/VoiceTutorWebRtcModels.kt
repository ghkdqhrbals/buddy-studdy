package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.backend.auth.Principal

data class VoiceTutorWebRtcControlContext(
    val session: VoiceTutorSession,
    val callId: String,
    // Server-authenticated identity, never a bearer token or caller-supplied user id.
    val principal: Principal? = null,
)
