package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorPostCallEvidence
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One bounded retry scope owns this verified intermediate result. It never
 * escapes into a shared cache, general provider logs, or a later recovery job. */
class VoiceTutorSummaryRetryContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VoiceTutorSummaryRetryContext>

    private var sourceFingerprint: String? = null
    private var evidence: VoiceTutorPostCallEvidence? = null
    var attempt: Int = 1
        private set

    fun beginAttempt(value: Int) { attempt = value.coerceIn(1, 5) }

    fun find(fingerprint: String): VoiceTutorPostCallEvidence? =
        evidence.takeIf { sourceFingerprint == fingerprint }

    fun remember(fingerprint: String, verifiedEvidence: VoiceTutorPostCallEvidence) {
        sourceFingerprint = fingerprint
        evidence = verifiedEvidence
    }
}
