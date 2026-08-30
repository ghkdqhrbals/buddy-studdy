package com.buddystudy.backend.voice.adapter.inbound.web

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/voice-tutor")
class VoiceTutorController(
    private val voiceTutor: VoiceTutorWebPort,
) {
    @Operation(summary = "Get Pro Voice Tutor eligibility, quota, and active session")
    @GetMapping("/status")
    suspend fun status(authentication: Authentication) = voiceTutor.status(authentication)

    @Operation(summary = "Reserve a server-metered Voice Tutor session")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createSession(
        @Valid @RequestBody body: CreateVoiceTutorSessionRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        authentication: Authentication,
    ) = voiceTutor.createSession(body, idempotencyKey, authentication)

    @Operation(summary = "List my Voice Tutor sessions with an opaque cursor")
    @GetMapping("/sessions")
    suspend fun sessions(
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(required = false) cursor: String?,
        authentication: Authentication,
    ) = voiceTutor.sessions(limit.coerceIn(1, 100), cursor, authentication)

    @Operation(summary = "Get one Voice Tutor session, transcript, and learning result")
    @GetMapping("/sessions/{sessionId}")
    suspend fun session(
        @PathVariable sessionId: String,
        authentication: Authentication,
    ) = voiceTutor.session(sessionId, authentication)

    @Operation(summary = "End my Voice Tutor session and finalize its metered usage")
    @PostMapping("/sessions/{sessionId}/end")
    suspend fun endSession(
        @PathVariable sessionId: String,
        authentication: Authentication,
    ) = voiceTutor.endSession(sessionId, authentication)
}

data class CreateVoiceTutorSessionRequest(
    @field:Positive
    var studyId: Long = 0,
    @field:NotBlank
    var language: String = "ko",
    var voice: String? = null,
)
