package com.buddystudy.backend.voice.application.port.inbound

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult

interface VoiceTutorInputAssessmentUseCase {
    /** Failure is explicit; it must never be interpreted as non-communicative learner input. */
    suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult
}
