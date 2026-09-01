package com.buddystudy.backend.voice.application.port.inbound

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenFeedbackAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest

interface VoiceTutorInputAssessmentUseCase {
    /** Failure is explicit; it must never be interpreted as non-communicative learner input. */
    suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult

    /** Failure never grants durable study-question authority. */
    suspend fun assessSpokenQuestion(request: VoiceTutorSpokenQuestionAssessmentRequest): Boolean = false

    /** Failure never grants durable answer-feedback authority. */
    suspend fun assessSpokenFeedback(request: VoiceTutorSpokenFeedbackAssessmentRequest): Boolean = false
}
