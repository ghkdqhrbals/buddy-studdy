package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenFeedbackAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest

fun interface VoiceTutorInputAssessmentPort {
    /** The use case supplies immutable bounded input and owns admission/total timeout. */
    suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult

    /** Separate semantic contract; implementations must fail rather than infer true. */
    suspend fun assessSpokenQuestion(request: VoiceTutorSpokenQuestionAssessmentRequest): Boolean =
        throw UnsupportedOperationException("Tutor question assessment is unavailable.")

    /** Separate semantic contract; implementations must fail rather than infer true. */
    suspend fun assessSpokenFeedback(request: VoiceTutorSpokenFeedbackAssessmentRequest): Boolean =
        throw UnsupportedOperationException("Tutor feedback assessment is unavailable.")
}
