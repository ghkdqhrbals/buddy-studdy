package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewRequest

interface VoiceTutorVoicePreviewPort {
    suspend fun synthesize(request: VoiceTutorVoicePreviewRequest): VoiceTutorVoicePreviewAudio
}
