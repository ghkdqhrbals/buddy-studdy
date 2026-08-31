package com.buddystudy.backend.voice.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio

interface VoiceTutorVoicePreviewUseCase {
    suspend fun preview(
        principal: Principal,
        voice: String,
        language: String,
    ): VoiceTutorVoicePreviewAudio
}
