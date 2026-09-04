package com.buddystudy.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Independent semantic assessment bounds and optional model; the existing user-content key is unchanged. */
@Component
@ConfigurationProperties(prefix = "buddystudy.voice-tutor.input-assessment")
data class VoiceTutorInputAssessmentProperties(
    // A positive saved-study mutation performs a primary semantic decision and
    // one independent semantic attestation in sequence. The deadline is a cap,
    // not an artificial delay; ordinary non-mutation turns still return after
    // the first provider response.
    var timeoutMilliseconds: Long = 10_000,
    var maxConcurrentAssessments: Int = 4,
    var admissionTimeoutMilliseconds: Long = 1_500,
    var maxQueuedAssessments: Int = 16,
    var maxUtterances: Int = 8,
    var maxTranscriptCharacters: Int = 4_000,
    var maxBatchTranscriptCharacters: Int = 16_000,
    var maxTeacherContextCharacters: Int = 4_000,
    /** Unset or blank preserves the configured voice summary model without changing summary jobs. */
    var model: String? = null,
)
