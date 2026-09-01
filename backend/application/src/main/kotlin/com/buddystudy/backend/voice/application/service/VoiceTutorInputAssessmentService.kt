package com.buddystudy.backend.voice.application.service

import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenFeedbackAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest
import com.buddystudy.backend.voice.application.model.correlatedTo
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorInputAssessmentPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

@Service
class VoiceTutorInputAssessmentService(
    private val provider: VoiceTutorInputAssessmentPort,
    properties: VoiceTutorInputAssessmentProperties,
) : VoiceTutorInputAssessmentUseCase {
    private val limits = properties.copy()
    private val permits: Semaphore
    private val queuedAssessments = AtomicInteger()

    init {
        require(limits.timeoutMilliseconds in 1..15_000) { "Invalid voice input assessment timeout." }
        require(limits.maxConcurrentAssessments in 1..16) { "Invalid voice input assessment concurrency." }
        require(limits.admissionTimeoutMilliseconds in 1..5_000) { "Invalid voice input assessment admission timeout." }
        require(limits.maxQueuedAssessments in 0..64) { "Invalid voice input assessment queue bound." }
        require(limits.maxUtterances in 1..8) { "Invalid voice input assessment batch bound." }
        require(limits.maxTranscriptCharacters in 1..4_000) { "Invalid voice input assessment text bound." }
        require(limits.maxBatchTranscriptCharacters in 1..16_000) { "Invalid voice input assessment total text bound." }
        require(limits.maxTeacherContextCharacters in 0..4_000) { "Invalid voice input assessment context bound." }
        // One singleton service shares admission across all users/calls in this
        // process. A small bounded wait absorbs ordinary turn-boundary bursts;
        // it is neither an unbounded request queue nor a provider retry.
        permits = Semaphore(limits.maxConcurrentAssessments)
    }

    override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
        currentCoroutineContext().ensureActive()
        val snapshot = request.copy(utterances = request.utterances.take(limits.maxUtterances + 1).map { utterance ->
            utterance.copy(targetOffer = utterance.targetOffer?.let { offer ->
                offer.copy(
                    candidates = offer.candidates.toList(),
                    candidateTraversals = offer.candidateTraversals.mapValues { (_, traversal) ->
                        traversal.copy(singleChildEdges = traversal.singleChildEdges.toList())
                    },
                )
            })
        })
        validate(snapshot)
        if (!acquirePermit()) throw failure(VoiceTutorInputAssessmentFailure.BUSY)
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

    override suspend fun assessSpokenQuestion(request: VoiceTutorSpokenQuestionAssessmentRequest): Boolean {
        currentCoroutineContext().ensureActive()
        if (request.userId <= 0 || request.language.isBlank() || request.language.length > 35 ||
            request.focusTopic.isBlank() || request.focusTopic.length > 255 ||
            request.focusDifficulty !in 1..10 || request.transcript.isBlank() ||
            request.transcript.length > limits.maxTranscriptCharacters
        ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        if (!acquirePermit()) throw failure(VoiceTutorInputAssessmentFailure.BUSY)
        try {
            return withTimeoutOrNull(limits.timeoutMilliseconds) {
                provider.assessSpokenQuestion(request)
            } ?: throw failure(VoiceTutorInputAssessmentFailure.TIMEOUT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: VoiceTutorInputAssessmentException) {
            throw error
        } catch (_: Exception) {
            throw failure(VoiceTutorInputAssessmentFailure.UNAVAILABLE)
        } finally {
            permits.release()
        }
    }

    override suspend fun assessSpokenFeedback(request: VoiceTutorSpokenFeedbackAssessmentRequest): Boolean {
        currentCoroutineContext().ensureActive()
        if (request.userId <= 0 || request.language.isBlank() || request.language.length > 35 ||
            request.focusTopic.isBlank() || request.focusTopic.length > 255 ||
            request.focusDifficulty !in 1..10 || request.questionTranscript.isBlank() ||
            request.answerTranscript.isBlank() || request.feedbackTranscript.isBlank() ||
            request.questionTranscript.length > limits.maxTranscriptCharacters ||
            request.answerTranscript.length > limits.maxTranscriptCharacters ||
            request.feedbackTranscript.length > limits.maxTranscriptCharacters ||
            request.questionTranscript.length + request.answerTranscript.length +
                request.feedbackTranscript.length > limits.maxBatchTranscriptCharacters
        ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        if (!acquirePermit()) throw failure(VoiceTutorInputAssessmentFailure.BUSY)
        try {
            return withTimeoutOrNull(limits.timeoutMilliseconds) {
                provider.assessSpokenFeedback(request)
            } ?: throw failure(VoiceTutorInputAssessmentFailure.TIMEOUT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: VoiceTutorInputAssessmentException) {
            throw error
        } catch (_: Exception) {
            throw failure(VoiceTutorInputAssessmentFailure.UNAVAILABLE)
        } finally {
            permits.release()
        }
    }

    private suspend fun acquirePermit(): Boolean {
        if (permits.tryAcquire()) return true
        if (limits.maxQueuedAssessments == 0) return false
        val queued = queuedAssessments.incrementAndGet()
        if (queued > limits.maxQueuedAssessments) {
            queuedAssessments.decrementAndGet()
            return false
        }
        var acquired = false
        return try {
            val admitted = withTimeoutOrNull(limits.admissionTimeoutMilliseconds) {
                permits.acquire()
                acquired = true
                true
            } ?: false
            // A timeout can race with successful resource acquisition. When
            // withTimeoutOrNull discards the block result, return the permit
            // here because assess() will not enter its provider finally block.
            if (!admitted && acquired) {
                permits.release()
                acquired = false
            }
            admitted
        } catch (error: CancellationException) {
            // The same race is possible when the parent/session is cancelled
            // while a queued acquire resumes. Cancellation must never strand
            // one of the process-wide permits.
            if (acquired) {
                permits.release()
                acquired = false
            }
            throw error
        } finally {
            queuedAssessments.decrementAndGet()
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
                utterance.transcript.length > limits.maxTranscriptCharacters ||
                utterance.sameSpeechContext?.let { context ->
                    utterance.checkpoint || context.isBlank() ||
                        context.length > limits.maxTranscriptCharacters || !context.endsWith(utterance.transcript)
                } == true
            ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
            utterance.targetOffer?.let { offer ->
                val candidates = offer.candidates
                if (offer.offerId <= 0 || offer.lessonRevision < 0 ||
                    offer.tutorResponseGeneration <= 0 || offer.tutorSpeechStoppedOrder <= 0 ||
                    offer.currentFocusStudyId?.let { it <= 0 } == true ||
                    offer.tutorAudioTranscript.isBlank() ||
                    offer.tutorAudioTranscript.length > MAX_TUTOR_OFFER_TRANSCRIPT_CHARACTERS ||
                    candidates.size !in 1..MAX_TARGET_OFFER_CANDIDATES ||
                    candidates.map { it.studyId }.distinct().size != candidates.size ||
                    candidates.any { candidate ->
                        candidate.studyId <= 0 || candidate.parentStudyId?.let { it <= 0 } == true ||
                            candidate.topic.isBlank() || candidate.topic.length > MAX_TARGET_TOPIC_CHARACTERS
                    } ||
                    offer.candidateTraversals.keys != candidates.mapTo(linkedSetOf()) { it.studyId } ||
                    candidates.any { candidate ->
                        offer.candidateTraversals[candidate.studyId]?.isValidFor(candidate) != true
                    }
                ) throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
                characters += offer.tutorAudioTranscript.length
            }
            characters += utterance.transcript.length
            characters += utterance.sameSpeechContext?.length ?: 0
        }
        if (characters > limits.maxBatchTranscriptCharacters) {
            throw failure(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        }
        // Blank/short/disfluent text is still data for GPT, not a local filler
        // blacklist, minimum-length heuristic, or normalization step.
    }

    private fun failure(reason: VoiceTutorInputAssessmentFailure) = VoiceTutorInputAssessmentException(reason)

    private companion object {
        const val MAX_TARGET_OFFER_CANDIDATES = 16
        const val MAX_TARGET_TOPIC_CHARACTERS = 255
        const val MAX_TUTOR_OFFER_TRANSCRIPT_CHARACTERS = 4_000
    }
}
