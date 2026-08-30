package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorSession

data class VoiceTutorWebRtcControlContext(
    val session: VoiceTutorSession,
    val callId: String,
)
