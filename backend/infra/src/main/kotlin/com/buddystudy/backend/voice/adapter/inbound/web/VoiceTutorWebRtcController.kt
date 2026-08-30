package com.buddystudy.backend.voice.adapter.inbound.web

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
    ): ResponseEntity<String> {
        val answer = voiceTutor.negotiate(sessionId, offerSdp, authentication)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/sdp"))
            .header("Cache-Control", "no-store")
            .body(answer.answerSdp)
    }
}
