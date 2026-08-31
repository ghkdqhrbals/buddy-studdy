package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/voice-tutor/sessions")
class VoiceTutorWebRtcController(
    private val voiceTutor: VoiceTutorWebRtcWebPort,
) {
    @Operation(summary = "Negotiate the owner-scoped direct WebRTC Voice Tutor media connection")
    @PostMapping(
        "/{sessionId}/webrtc",
        consumes = ["application/sdp"],
        produces = ["application/sdp"],
    )
    suspend fun negotiate(
        @PathVariable sessionId: String,
        @RequestBody offerSdp: String,
        authentication: Authentication,
        @RequestHeader(name = VoiceTutorRealtimeContract.TURN_PROTOCOL_HEADER, required = false)
        turnProtocol: String?,
    ): ResponseEntity<String> {
        // Never create a paid manual-turn call for a client that cannot report
        // utterance boundaries. Upgrading only the server must fail before SDP.
        if (turnProtocol != VoiceTutorRealtimeContract.LOCAL_VAD_TURN_PROTOCOL) {
            return ResponseEntity.status(HttpStatus.UPGRADE_REQUIRED)
                .header("Cache-Control", "no-store")
                .header(VoiceTutorRealtimeContract.TURN_PROTOCOL_HEADER, VoiceTutorRealtimeContract.LOCAL_VAD_TURN_PROTOCOL)
                .build()
        }
        val answer = voiceTutor.negotiate(sessionId, offerSdp, authentication)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/sdp"))
            .header("Cache-Control", "no-store")
            .body(answer.answerSdp)
    }
}
