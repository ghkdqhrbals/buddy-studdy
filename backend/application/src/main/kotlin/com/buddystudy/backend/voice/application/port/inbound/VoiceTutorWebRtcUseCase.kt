package com.buddystudy.backend.voice.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import kotlinx.coroutines.flow.Flow

interface VoiceTutorWebRtcUseCase {
    suspend fun negotiate(
        principal: Principal,
        sessionId: String,
        offerSdp: String,
    ): VoiceTutorWebRtcAnswer

    suspend fun claimControl(
        principal: Principal,
        sessionId: String,
        connectionId: String,
    ): VoiceTutorWebRtcControlContext

    suspend fun relaySideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Unit,
    )

    suspend fun hangup(callId: String)
}
