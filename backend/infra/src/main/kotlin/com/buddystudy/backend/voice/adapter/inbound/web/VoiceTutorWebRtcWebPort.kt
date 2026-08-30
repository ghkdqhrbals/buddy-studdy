package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import org.springframework.security.core.Authentication

interface VoiceTutorWebRtcWebPort {
    suspend fun negotiate(
        sessionId: String,
        offerSdp: String,
        authentication: Authentication,
    ): VoiceTutorWebRtcAnswer
}
