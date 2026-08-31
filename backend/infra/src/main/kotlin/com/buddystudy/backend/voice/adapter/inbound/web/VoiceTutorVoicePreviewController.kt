package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorVoicePreviewUseCase
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/voice-tutor/voices")
class VoiceTutorVoicePreviewController(
    private val previews: VoiceTutorVoicePreviewWebPort,
) {
    @Operation(summary = "Preview a Voice Tutor voice with fixed server copy")
    @GetMapping("/{voice}/preview", produces = [VoiceTutorVoicePreviewAudio.CONTENT_TYPE])
    suspend fun preview(
        @PathVariable voice: String,
        @RequestParam(defaultValue = "ko") language: String,
        authentication: Authentication,
    ): ResponseEntity<ByteArray> {
        val audio = previews.preview(voice, language, authentication)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(audio.contentType))
            .contentLength(audio.bytes.size.toLong())
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(audio.bytes)
    }
}

interface VoiceTutorVoicePreviewWebPort {
    suspend fun preview(
        voice: String,
        language: String,
        authentication: Authentication,
    ): VoiceTutorVoicePreviewAudio
}

@Component
class VoiceTutorVoicePreviewWebAdapter(
    private val previews: VoiceTutorVoicePreviewUseCase,
) : VoiceTutorVoicePreviewWebPort {
    override suspend fun preview(
        voice: String,
        language: String,
        authentication: Authentication,
    ): VoiceTutorVoicePreviewAudio =
        previews.preview(authentication.principalOrThrow(), voice, language)
}
