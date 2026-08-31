package com.buddystudy.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Independent bounds; the model and key remain the existing voice summary configuration. */
@Component
@ConfigurationProperties(prefix = "buddystudy.voice-tutor.input-assessment")
data class VoiceTutorInputAssessmentProperties(
    var timeoutMilliseconds: Long = 5_000,
    var maxConcurrentAssessments: Int = 4,
    var maxUtterances: Int = 8,
    var maxTranscriptCharacters: Int = 4_000,
    var maxBatchTranscriptCharacters: Int = 16_000,
    var maxTeacherContextCharacters: Int = 4_000,
)
