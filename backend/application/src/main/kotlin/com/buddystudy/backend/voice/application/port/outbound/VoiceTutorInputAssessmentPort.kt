package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult

fun interface VoiceTutorInputAssessmentPort {
    /** The use case supplies immutable bounded input and owns admission/total timeout. */
    suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult
}
