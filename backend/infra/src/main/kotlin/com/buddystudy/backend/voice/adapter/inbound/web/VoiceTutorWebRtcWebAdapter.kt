package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorWebRtcUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class VoiceTutorWebRtcWebAdapter(
    private val voiceTutor: VoiceTutorWebRtcUseCase,
) : VoiceTutorWebRtcWebPort {
    override suspend fun negotiate(
        sessionId: String,
        offerSdp: String,
        authentication: Authentication,
    ) = voiceTutor.negotiate(authentication.principalOrThrow(), sessionId, offerSdp)
}
