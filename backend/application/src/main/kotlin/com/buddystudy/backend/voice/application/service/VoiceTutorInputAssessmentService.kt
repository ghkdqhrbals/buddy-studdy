package com.buddystudy.backend.voice.application.service

import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.correlatedTo
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorInputAssessmentPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service

@Service
class VoiceTutorInputAssessmentService(
    private val provider: VoiceTutorInputAssessmentPort,
    properties: VoiceTutorInputAssessmentProperties,
) : VoiceTutorInputAssessmentUseCase {
    private val limits = properties.copy()
    private val permits: Semaphore

    init {
        require(limits.timeoutMilliseconds in 1..15_000) { "Invalid voice input assessment timeout." }
        require(limits.maxConcurrentAssessments in 1..16) { "Invalid voice input assessment concurrency." }
        require(limits.maxUtterances in 1..8) { "Invalid voice input assessment batch bound." }
        require(limits.maxTranscriptCharacters in 1..4_000) { "Invalid voice input assessment text bound." }
        require(limits.maxBatchTranscriptCharacters in 1..16_000) { "Invalid voice input assessment total text bound." }
        require(limits.maxTeacherContextCharacters in 0..4_000) { "Invalid voice input assessment context bound." }
        // One singleton service shares admission across all users/calls in this
        // process. There is deliberately no unbounded queue or implicit retry.
        permits = Semaphore(limits.maxConcurrentAssessments)
    }

    override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
        currentCoroutineContext().ensureActive()
        val snapshot = request.copy(utterances = request.utterances.take(limits.maxUtterances + 1))
        validate(snapshot)
        if (!permits.tryAcquire()) throw failure(VoiceTutorInputAssessmentFailure.BUSY)
        try {
            val result = withTimeoutOrNull(limits.timeoutMilliseconds) {
                provider.assess(snapshot).correlatedTo(snapshot.utterances)
            } ?: throw failure(VoiceTutorInputAssessmentFailure.TIMEOUT)
            currentCoroutineContext().ensureActive()
            return result
        } catch (error: CancellationException) {
            // Includes parent/session cancellation; never revive a closed call
            // or reinterpret cancellation as an assessment of its transcript.
            throw error
        } catch (error: VoiceTutorInputAssessmentException) {
            throw error
        } catch (_: Exception) {
            throw failure(VoiceTutorInputAssessmentFailure.UNAVAILABLE)
        } finally {
            permits.release()
        }
    }

    private fun validate(request: VoiceTutorInputAssessmentRequest) {
        if (request.userId <= 0 || request.language.isBlank() || request.language.length > 35 ||
            request.teacherContext.length > limits.maxTeacherContextCharacters ||
            request.utterances.size !in 1..limits.maxUtterances
        ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)

        val ids = HashSet<String>()
        var characters = 0
        for (utterance in request.utterances) {
            if (utterance.itemId.isBlank() || utterance.itemId.length > 256 ||
                utterance.itemId.any(Char::isISOControl) || !ids.add(utterance.itemId) ||
                utterance.transcript.length > limits.maxTranscriptCharacters
            ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
            characters += utterance.transcript.length
        }
        if (characters > limits.maxBatchTranscriptCharacters) {
            throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        }
        // Blank/short/disfluent text is still data for GPT, not a local filler
        // blacklist, minimum-length heuristic, or normalization step.
    }

    private fun failure(reason: VoiceTutorInputAssessmentFailure) = VoiceTutorInputAssessmentException(reason)
}
