package com.buddystudy.backend.voice.adapter.inbound.web

import io.swagger.v3.oas.annotations.Operation
import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
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

    @Operation(summary = "Create a checksum-bound private Voice Tutor recording upload")
    @PostMapping("/sessions/{sessionId}/recording/uploads")
    suspend fun createRecordingUpload(
        @PathVariable sessionId: String,
        @Valid @RequestBody body: CreateVoiceTutorRecordingUploadRequest,
        authentication: Authentication,
    ) = voiceTutor.createRecordingUpload(sessionId, body, authentication)

    @Operation(summary = "Validate and finalize a private Voice Tutor recording upload")
    @PostMapping("/sessions/{sessionId}/recording/uploads/{recordingId}/complete")
    suspend fun completeRecordingUpload(
        @PathVariable sessionId: String,
        @PathVariable recordingId: String,
        authentication: Authentication,
    ) = voiceTutor.completeRecordingUpload(sessionId, recordingId, authentication)

    @Operation(summary = "Get short-lived access to my private Voice Tutor recording")
    @GetMapping("/sessions/{sessionId}/recording/access")
    suspend fun recordingAccess(
        @PathVariable sessionId: String,
        authentication: Authentication,
    ) = voiceTutor.recordingAccess(sessionId, authentication)

    @Operation(summary = "Delete my private Voice Tutor recording")
    @DeleteMapping("/sessions/{sessionId}/recording")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteRecording(
        @PathVariable sessionId: String,
        authentication: Authentication,
    ) = voiceTutor.deleteRecording(sessionId, authentication)
}

data class CreateVoiceTutorSessionRequest(
    @field:Positive
    var studyId: Long? = null,
    @field:NotBlank
    var language: String = "ko",
    var voice: String? = null,
    var recordingConsent: Boolean = false,
    var recordingConsentVersion: String? = null,
)

data class CreateVoiceTutorRecordingUploadRequest(
    @field:NotBlank
    var contentType: String = "audio/mp4",
    @field:Positive
    @field:JsonAlias("byteSize")
    var contentLength: Long = 0,
    @field:NotBlank
    @field:JsonAlias("sha256Hex")
    var sha256: String = "",
    @field:Positive
    var durationMilliseconds: Long? = null,
    @field:Positive
    var durationSeconds: Long? = null,
)
