package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.Disposable
import reactor.core.publisher.BaseSubscriber
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Synthetic events only: no provider, credential, microphone or network use. */
class VoiceTutorMeaningfulInputRelayTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `acoustic commit and partial ASR never create a response or publish learner input`() = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.commit("learner-1")
        assertThat(f.responses()).hasSize(1)
        assertThat(f.provider("conversation.item.input_audio_transcription.delta", "item_id" to "learner-1", "delta" to "어"))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.actions).isEmpty()
        f.transcript("learner-1", "어, 준비됐어.")
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        assertThat(f.publications()).isEmpty()
    }

    @Test
    fun `accepted short reply preserves exact original and responds only after persistence`() = fixture().use { f ->
        f.utterance(1, "yes", "응")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.publications()).hasSize(1)
        val published = f.publications().single()
        assertThat(mapper.readTree(published.rawEvent).path("transcript").asText()).isEqualTo("응")
        assertThat(f.responses()).hasSize(1)
        f.controller.confirmInputPublished(published.itemId)
        assertThat(f.responses()).hasSize(2)
        f.controller.confirmInputPublished(published.itemId)
        f.transcript("yes", "응")
        f.commit("yes")
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `spoken lesson end emits one server lifecycle request only after exact durable learner publication`() {
        fixture().use { f ->
            f.utterance(1, "not-persisted", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = false)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
        }

        fixture().use { f ->
            f.utterance(1, "persisted-end", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).containsExactly(VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)
            assertThat(f.responses()).hasSize(1)
            assertThat(f.controller.acceptsInputEvents()).isFalse()
            f.assertNoAudioDisruption()
        }

        fixture().use { f ->
            f.start(1)
            f.controller.fireContinuousSpeechDeadline()
            f.commit("incomplete-checkpoint")
            f.transcript("incomplete-checkpoint", "학습 끝낼게라고 말하면")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            assertThat(publication.checkpoint).isTrue()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(1)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
        }
    }

    @Test
    fun `spoken lesson end waits for the active tutor sentence to drain completely`() =
        fixture(finishOpening = false).use { f ->
            f.utterance(1, "persisted-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(1)
            f.start(1) // Duplicate/stale speech generation cannot retract a valid END.
            f.provider("output_audio_buffer.stopped", "response_id" to "wrong-response")
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.openingStopped()
            assertThat(f.serverLifecycleTypes()).containsExactly(VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)
            assertThat(f.responses()).hasSize(1)
            f.assertNoAudioDisruption()
        }

    @Test
    fun `a newer learner generation retracts an in flight or awaiting persistence spoken end`() {
        fixture().use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.start(2)
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val oldPublication = f.publications().single()
            f.controller.confirmInputPublished(oldPublication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.stop(2)
            f.commit("continue")
            f.transcript("continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

        fixture().use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val oldPublication = f.publications().single()
            f.start(2)
            f.controller.confirmInputPublished(oldPublication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.stop(2)
            f.commit("continue")
            f.transcript("continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            f.controller.confirmInputPublished("old-end", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            // The END is already persisted, but the active tutor sentence has
            // not drained. A newer learner generation must still retract it.
            f.utterance(2, "continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }

        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            f.controller.confirmInputPublished("old-end", persisted = true)
            f.utterance(2, "noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("noise")
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }
    }

    @Test
    fun `a mixed batch cannot revive an older end when the newer publication is acknowledged first`() =
        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.utterance(2, "continue", "아니, 계속할게")
            f.openingDone()
            val batch = f.assessments().single()
            f.controller.completeInputAssessment(
                batch.token,
                Result.success(VoiceTutorInputAssessmentResult(listOf(
                    VoiceTutorInputItemAssessment(
                        "old-end", VoiceTutorInputDecision.MEANINGFUL,
                        VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
                    ),
                    VoiceTutorInputItemAssessment("continue", VoiceTutorInputDecision.MEANINGFUL),
                ))),
            )
            assertThat(f.publications().map { it.itemId }).containsExactly("old-end", "continue")
            f.controller.confirmInputPublished("continue", persisted = true)
            f.controller.confirmInputPublished("old-end", persisted = true)
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

    @Test
    fun `filler is removed from provider context without transcript or tutor reply`() = fixture().use { f ->
        f.utterance(1, "hesitation", "어… 음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        assertThat(f.publications()).isEmpty()
        assertThat(f.deletions().map { it.path("item_id").asText() }).containsExactly("hesitation")
        assertThat(f.responses()).hasSize(1)
        f.deleted("not-this-item")
        assertThat(f.responses()).hasSize(1)
        f.deleted("hesitation")
        assertThat(f.responses()).hasSize(1)
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `later real reply cannot bypass an unacknowledged filler deletion`() = fixture().use { f ->
        f.utterance(1, "filler", "음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.utterance(2, "question", "주제 알려 줘")
        assertThat(f.assessments()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.deleted("filler")
        assertThat(f.assessments()).hasSize(2)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `ASR arriving before its commit ACK waits for exact authenticated commit correlation`(): Unit = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.transcript("early", "아니")
        assertThat(f.assessments()).isEmpty()
        f.commit("early")
        assertThat(f.assessments().single().utterances.single().transcript).isEqualTo("아니")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
    }

    @Test
    fun `overlap waits for teacher sentence context and existing provider playback gates`() = fixture(finishOpening = false).use { f ->
        f.utterance(1, "learner", "어 준비됐지")
        assertThat(f.assessments()).isEmpty()
        f.openingDone()
        assertThat(f.assessments().single().teacherContext).isEqualTo("학습을 시작할 준비가 됐나요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.openingStopped()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `provider stop before done still needs contextual assessment and persistence`() = fixture(finishOpening = false).use { f ->
        f.utterance(1, "learner", "준비됐어")
        f.openingStopped()
        assertThat(f.assessments()).isEmpty()
        f.openingDone()
        assertThat(f.responses()).hasSize(1)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `next acoustic turn is not consumed by previous semantic completion`() = fixture().use { f ->
        f.utterance(1, "first", "준비됐어")
        f.start(2)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.stop(2)
        f.commit("second")
        f.transcript("second", "Redis를 배우고 싶어")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `long acoustic activity alone checkpoints and cannot interrupt or trigger teaching`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        assertThat(f.inputCommits()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.commit("long-filler")
        f.transcript("long-filler", "어… 음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.deleted("long-filler")
        assertThat(f.responses()).hasSize(1)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `meaningful long speech stays listening after persisted checkpoints until its natural stop`(): Unit = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.commit("long-idea")
        f.transcript("long-idea", "Redis 캐시를 사용할 때 데이터가 바뀌면 무효화를 어떻게 할지 생각 중인데")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.now.addAndGet(Duration.ofSeconds(12).toNanos())
        f.controller.fireContinuousSpeechDeadline()
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("more-of-the-same-idea")
        f.transcript("more-of-the-same-idea", "변경이 잦으면 무효화 방식의 비용도 비교해 보고 싶어요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.stop(1)
        f.commit("final-tail")
        f.transcript("final-tail", "어느 쪽이 더 적절한가요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.responses().last().path("response").path("metadata").has(VoiceTutorRealtimeContract.TURN_METADATA_KEY)).isFalse()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `stop racing a checkpoint flushes one tail rather than two empty commits`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.controller.observeClientEvent(f.speech(false, 1))
        assertThat(f.inputCommits()).hasSize(1)
        f.now.addAndGet(Duration.ofMillis(250).toNanos())
        f.controller.flushDelayedStopCommit(1)
        f.controller.flushDelayedStopCommit(1)
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("checkpoint")
        f.commit("tail")
        f.transcript("checkpoint", "질문이 있어요")
        f.transcript("tail", "")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.deleted("tail")
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `only exact owned empty commit error releases its slot without ending the call`() = fixture().use { f ->
        f.start(1)
        f.stop(1)
        val commitId = f.inputCommits().single().path("event_id").asText()
        val unknown = f.emptyCommit("not-owned")
        assertThat(f.controller.observeProviderEvent(unknown)).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
        assertThat(f.controller.observeProviderEvent(f.emptyCommit(commitId))).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.utterance(2, "retry", "질문 있어")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `rapid mute unmute stops within a checkpoint tail settle every coalesced speech slot`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.commit("checkpoint")
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.controller.observeClientEvent(f.speech(false, 1))
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.start(2)
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.controller.observeClientEvent(f.speech(false, 2))
        f.now.set(Duration.ofMillis(250).toNanos())
        f.controller.flushDelayedStopCommit(1)
        f.controller.flushDelayedStopCommit(2)
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("combined-tail")
        f.transcript("checkpoint", "Redis에 질문이 있어요")
        f.transcript("combined-tail", "캐시 무효화에 관해서요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `expired assessment and terminal cleanup failure cannot start late paid work`(): Unit = fixture().use { f ->
        f.utterance(1, "learner", "저는 준비됐어요")
        val batch = f.assessments().single()
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        assertThat(f.controller.canAssessInput(batch.token)).isFalse()
        assertThat(f.deletions()).hasSize(1)
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.controller.acceptsInputEvents()).isFalse()
        assertThat(f.controller.canAssessInput(batch.token)).isFalse()
        f.controller.completeInputAssessment(batch.token, f.result(batch, VoiceTutorInputDecision.MEANINGFUL))
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
        assertThat(f.errors).hasSize(1)
    }

    @Test
    fun `assessment failure requests repeat and deletes unapproved audio instead of treating it as filler`() = fixture().use { f ->
        f.utterance(1, "learner", "저는 준비됐어요")
        val batch = f.assessments().single()
        f.controller.completeInputAssessment(
            batch.token,
            Result.failure(VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.TIMEOUT)),
        )
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.deletions()).hasSize(1)
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
        f.deleted("learner")
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `missing ASR has finite retry and cannot approve a late transcript`(): Unit = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.commit("missing-asr")
        f.now.addAndGet(Duration.ofSeconds(8).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.deletions()).hasSize(1)
        f.transcript("missing-asr", "늦게 왔어요")
        assertThat(f.assessments()).isEmpty()
        f.deleted("missing-asr")
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `missing provider delete ACK fails finitely rather than hanging a silent call`(): Unit = fixture().use { f ->
        f.utterance(1, "filler", "음")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.errors).singleElement().isInstanceOf(VoiceTutorInputTurnCoordinatorException::class.java)
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `cleanup failure reaches lifecycle even when outbound controls have no demand`(): Unit = fixture(controlDemand = 2).use { f ->
        val observedFailure = AtomicReference<Throwable>()
        val failureArrived = CountDownLatch(1)
        val failureSubscription = f.controller.inputFailure().subscribe({}, {
            observedFailure.set(it)
            failureArrived.countDown()
        })
        try {
            // Two requested controls = opening response + this input commit.
            // The later delete remains queued behind downstream backpressure.
            f.utterance(1, "filler", "음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            assertThat(f.deletions()).isEmpty()
            f.now.addAndGet(Duration.ofSeconds(5).toNanos())
            f.controller.expirePendingInput()
            assertThat(failureArrived.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(observedFailure.get()).isInstanceOf(VoiceTutorInputTurnCoordinatorException::class.java)
            assertThat(f.controller.acceptsInputEvents()).isFalse()
            assertThat(f.controller.canPublishInput("filler")).isFalse()
            assertThat(f.responses()).hasSize(1)
        } finally {
            failureSubscription.dispose()
        }
    }

    @Test
    fun `terminal fencing drops unassessed late transcript and assessment callback`(): Unit = fixture().use { f ->
        f.utterance(1, "learner", "준비됐어")
        val batch = f.assessments().single()
        f.controller.close()
        f.controller.completeInputAssessment(batch.token, f.result(batch, VoiceTutorInputDecision.MEANINGFUL))
        assertThat(f.transcript("learner", "준비됐어")).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `stale incomplete response does not fail the current teacher response`() = fixture(finishOpening = false).use { f ->
        assertThat(f.controller.observeProviderEvent(f.response("response.done", "stale", "wrong-token", "failed")))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.openingDone()
        f.openingStopped()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `async assessment leaves provider receive free and uses server context with persistence acknowledgement`() {
        val classifierEntered = CountDownLatch(1)
        val publishEntered = CountDownLatch(1)
        val classifierRelease = CompletableDeferred<Unit>()
        val persistenceRelease = CompletableDeferred<Unit>()
        val request = AtomicReference<VoiceTutorInputAssessmentRequest>()
        val workerErrors = CopyOnWriteArrayList<Throwable>()
        fixture(finishOpening = false, captureInputActions = false).use { f ->
            val worker = voiceTutorInputAssessmentRelay(
                f.controller,
                userId = 42,
                language = "ko",
                assessment = object : VoiceTutorInputAssessmentUseCase {
                    override suspend fun assess(input: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
                        request.set(input)
                        classifierEntered.countDown()
                        classifierRelease.await()
                        return VoiceTutorInputAssessmentResult(input.utterances.map {
                            VoiceTutorInputItemAssessment(it.itemId, VoiceTutorInputDecision.MEANINGFUL)
                        })
                    }
                },
            ) { raw, persist, forward ->
                assertThat(mapper.readTree(raw).path("transcript").asText()).isEqualTo("응")
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                publishEntered.countDown()
                persistenceRelease.await()
                true
            }.subscribe({}, workerErrors::add)
            try {
                f.utterance(1, "learner", "응")
                f.openingDone()
                assertThat(classifierEntered.await(2, TimeUnit.SECONDS)).isTrue()
                // This provider boundary must be processed while GPT is waiting.
                f.openingStopped()
                assertThat(f.responses()).hasSize(1)
                assertThat(request.get().userId).isEqualTo(42)
                assertThat(request.get().language).isEqualTo("ko")
                assertThat(request.get().teacherContext).isEqualTo("학습을 시작할 준비가 됐나요?")
                classifierRelease.complete(Unit)
                assertThat(publishEntered.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(1)
                persistenceRelease.complete(Unit)
                assertThat(f.nextResponse.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(2)
                assertThat(workerErrors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
                classifierRelease.cancel()
                persistenceRelease.cancel()
            }
        }
    }

    @Test
    fun `the production input worker consumes a false persistence receipt without promoting consent or replaying input`(): Unit {
        val publishedItems = CopyOnWriteArrayList<String>()
        val workerErrors = CopyOnWriteArrayList<Throwable>()
        fixture(captureInputActions = false).use { f ->
            val worker = voiceTutorInputAssessmentRelay(
                f.controller, userId = 42, language = "ko",
                assessment = object : VoiceTutorInputAssessmentUseCase {
                    override suspend fun assess(input: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
                        VoiceTutorInputAssessmentResult(input.utterances.map {
                            VoiceTutorInputItemAssessment(it.itemId, VoiceTutorInputDecision.MEANINGFUL)
                        })
                },
            ) { raw, persist, forward ->
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                publishedItems += mapper.readTree(raw).path("item_id").asText()
                false // Full or already stored: handled, but no new durable USER evidence.
            }.subscribe({}, workerErrors::add)
            try {
                f.utterance(1, "handled-once", "학습에 관한 의미 있는 설명이에요.")
                assertThat(f.nextResponse.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(2)
                val boundary = f.controller.mutationDialogueBoundary()
                assertThat(boundary.latestAcceptedLearnerSpeechStartedOrder).isZero()
                assertThat(boundary.precedingTutorSpeechStoppedOrder).isZero()
                assertThat(boundary.precedingSpokenResponseGeneration).isZero()

                f.transcript("handled-once", "중복 전사")
                f.commit("handled-once")
                f.controller.confirmInputPublished("handled-once", persisted = true)
                assertThat(publishedItems).containsExactly("handled-once")
                assertThat(f.responses()).hasSize(2)
                assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isZero()
                assertThat(f.controller.acceptsInputEvents()).isTrue()
                assertThat(workerErrors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }
    }

    private fun fixture(finishOpening: Boolean = true, captureInputActions: Boolean = true, controlDemand: Long = Long.MAX_VALUE) =
        Fixture(finishOpening, captureInputActions, controlDemand)

    private inner class Fixture(finishOpening: Boolean, captureInputActions: Boolean, controlDemand: Long) : AutoCloseable {
        val now = AtomicLong()
        val controls = CopyOnWriteArrayList<String>()
        val serverLifecycle = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val nextResponse = CountDownLatch(1)
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
        )
        private val controlSubscription: Disposable
        private val serverLifecycleSubscription: Disposable
        private val workSubscription: Disposable?
        private val openingToken: String

        init {
            controlSubscription = controller.providerEvents().subscribeWith(object : BaseSubscriber<String>() {
                override fun hookOnSubscribe(subscription: Subscription) { request(controlDemand) }
                override fun hookOnNext(raw: String) {
                    controls += raw
                    if (responses().size > 1) nextResponse.countDown()
                }
                override fun hookOnError(throwable: Throwable) { errors += throwable }
            })
            serverLifecycleSubscription = controller.serverLifecycleEvents().subscribe(serverLifecycle::add, errors::add)
            workSubscription = if (captureInputActions) controller.inputActions().subscribe(actions::add, errors::add) else null
            controller.startOpeningResponse()
            openingToken = responses().single().path("event_id").asText()
            controller.observeProviderEvent(response("response.created", "opening", openingToken))
            if (finishOpening) {
                openingDone()
                openingStopped()
            }
        }

        fun openingDone() { controller.observeProviderEvent(response("response.done", "opening", openingToken)) }
        fun openingStopped() { provider("output_audio_buffer.stopped", "response_id" to "opening") }
        fun start(sequence: Long) { controller.observeClientEvent(speech(true, sequence)) }
        fun stop(sequence: Long) {
            now.addAndGet(Duration.ofSeconds(1).toNanos())
            controller.observeClientEvent(speech(false, sequence))
        }
        fun commit(id: String) { provider("input_audio_buffer.committed", "item_id" to id) }
        fun deleted(id: String) { provider("conversation.item.deleted", "item_id" to id) }
        fun utterance(sequence: Long, id: String, text: String) {
            start(sequence)
            stop(sequence)
            commit(id)
            transcript(id, text)
        }
        fun transcript(id: String, text: String) = provider(
            "conversation.item.input_audio_transcription.completed", "item_id" to id, "transcript" to text,
        )
        fun assessments() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()
        fun publications() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Publish>()
        fun assess(
            decision: VoiceTutorInputDecision,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        ) {
            val batch = assessments().last()
            controller.completeInputAssessment(batch.token, result(batch, decision, intent))
        }
        fun result(
            batch: VoiceTutorInputTurnCoordinator.Action.Assess,
            decision: VoiceTutorInputDecision,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        ) = Result.success(
            VoiceTutorInputAssessmentResult(batch.utterances.map {
                VoiceTutorInputItemAssessment(it.itemId, decision, intent)
            }),
        )
        fun publishAll() { publications().forEach { controller.confirmInputPublished(it.itemId) } }
        fun responses() = controls.map(mapper::readTree).filter { it.path("type").asText() == "response.create" }
        fun serverLifecycleTypes() = serverLifecycle.map { mapper.readTree(it).path("type").asText() }
        fun deletions() = controls.map(mapper::readTree).filter { it.path("type").asText() == "conversation.item.delete" }
        fun inputCommits() = controls.map(mapper::readTree).filter { it.path("type").asText() == "input_audio_buffer.commit" }
        fun assertNoAudioDisruption() {
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .doesNotContain("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.clear", "conversation.item.truncate")
        }
        fun provider(type: String, vararg fields: Pair<String, Any>): VoiceTutorProviderRelayDisposition =
            controller.observeProviderEvent(mapper.writeValueAsString(linkedMapOf("type" to type, *fields)))
        fun speech(start: Boolean, sequence: Long): String = mapper.writeValueAsString(
            mapOf("type" to if (start) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
                "sequence" to sequence),
        )
        fun response(type: String, id: String, token: String, status: String = "completed"): String = mapper.writeValueAsString(
            mapOf("type" to type, "response" to mapOf(
                "id" to id, "status" to status,
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
                "output" to listOf(mapOf("role" to "assistant", "content" to listOf(mapOf("transcript" to "학습을 시작할 준비가 됐나요?")))),
            )),
        )
        fun emptyCommit(eventId: String): String = mapper.writeValueAsString(mapOf(
            "type" to "error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to eventId),
        ))
        override fun close() {
            controller.close()
            workSubscription?.dispose()
            controlSubscription.dispose()
            serverLifecycleSubscription.dispose()
        }
    }
}
