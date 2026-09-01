package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Synthetic events/virtual clocks only. A long speech checkpoint never hands the tutor the floor. */
class VoiceTutorLongSpeechCheckpointTest {
    @Test
    fun `the recurring twelve second timer transcribes successive chunks without speaking until the learner stops`(): Unit {
        val active = AtomicReference<Fixture>()
        try {
            StepVerifier.withVirtualTime {
                active.set(Fixture(externalClock = { VirtualTimeScheduler.get().now(TimeUnit.NANOSECONDS) }))
                Mono.delay(Duration.ofSeconds(25)).thenReturn(Unit)
            }
                .then { active.get().start(1) }
                .thenAwait(Duration.ofSeconds(12))
                .then {
                    val f = active.get()
                    assertThat(f.commits()).hasSize(1)
                    f.accept("chunk-1", "먼저 캐시 무효화의 기준을 설명하면")
                    assertThat(f.responses()).isEmpty()
                }
                .thenAwait(Duration.ofSeconds(12))
                .then {
                    val f = active.get()
                    assertThat(f.commits()).hasSize(2)
                    f.accept("chunk-2", "데이터가 바뀌는 빈도와 정합성 요구를 함께 보아야 하고")
                    assertThat(f.responses()).isEmpty()
                }
                .thenAwait(Duration.ofSeconds(1))
                .then {
                    val f = active.get()
                    f.stop(1)
                    assertThat(f.commits()).hasSize(3)
                    assertThat(f.responses()).isEmpty()
                    f.accept("tail", "어떤 경우에 TTL만으로 충분한지 궁금해요")
                    assertThat(f.responses()).hasSize(1)
                    assertThat(f.publications().map { it.itemId }).containsExactly("chunk-1", "chunk-2", "tail")
                    f.assertNoInterventionOrAudioDisruption()
                }
                .expectNext(Unit)
                .verifyComplete()
        } finally {
            active.get()?.close()
        }
    }

    @Test
    fun `many meaningful checkpoints remain one learner turn without accumulating pending speech slots`(): Unit = Fixture().use { f ->
        f.start(1)
        repeat(40) { index ->
            f.checkpoint()
            f.accept("part-$index", "캐시 정책을 비교하는 계속된 설명 $index")
            assertThat(f.commits()).hasSize(index + 1)
            assertThat(f.responses()).isEmpty()
            assertThat(f.controller.acceptsInputEvents()).isTrue()
        }
        f.advance(Duration.ofSeconds(1))
        f.stop(1)
        f.accept("tail", "이제 제 설명에 대해 답변해 주세요")
        assertThat(f.responses()).hasSize(1)
        assertThat(f.publications()).hasSize(41)
        f.assertNoInterventionOrAudioDisruption()
    }

    @Test
    fun `slow commit assessment and publication permit only one deferred checkpoint and no response`(): Unit = Fixture().use { f ->
        f.start(1)
        f.checkpoint()
        f.advance(Duration.ofSeconds(12))
        repeat(50) { f.controller.fireContinuousSpeechDeadline() }
        assertThat(f.commits()).hasSize(1)
        f.commit("slow")
        repeat(50) { f.controller.fireContinuousSpeechDeadline() }
        assertThat(f.commits()).hasSize(1)
        f.transcript("slow", "정합성을 유지하는 방법을 아직 설명하는 중이에요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        repeat(50) { f.controller.fireContinuousSpeechDeadline() }
        assertThat(f.commits()).hasSize(1)
        assertThat(f.responses()).isEmpty()
        f.controller.confirmInputPublished("slow")
        assertThat(f.commits()).hasSize(2)
        assertThat(f.responses()).isEmpty()
        repeat(50) { f.controller.fireContinuousSpeechDeadline() }
        assertThat(f.commits()).hasSize(2)
        f.assertNoInterventionOrAudioDisruption()
    }

    @Test
    fun `repeated noncommunicative checkpoints never manufacture a learner turn or spoken warning`(): Unit = Fixture().use { f ->
        f.start(1)
        repeat(4) { index ->
            f.checkpoint()
            f.commit("noise-$index")
            f.transcript("noise-$index", "음… 어…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            assertThat(f.responses()).isEmpty()
            f.deleted("noise-$index")
        }
        f.advance(Duration.ofSeconds(1))
        f.stop(1)
        f.commit("tail")
        f.transcript("tail", "")
        f.deleted("tail")
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).isEmpty()
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).isEmpty()
        f.assertNoInterventionOrAudioDisruption()
    }

    @Test
    fun `tutor completion during a meaningful long reply cannot release a timed intervention`(): Unit = Fixture().use { f ->
        val tutor = f.beginTutor()
        f.start(2)
        f.checkpoint()
        f.commit("overlap")
        f.transcript("overlap", "제 생각을 마저 설명하면 캐시가 일관되지 않는 이유는")
        assertThat(f.assessments()).hasSize(1) // The first, already answered learner turn only.
        f.response("response.done", "tutor", tutor)
        f.provider("output_audio_buffer.stopped", "response_id" to "tutor")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.controller.confirmInputPublished("overlap")
        assertThat(f.responses()).hasSize(1)
        f.checkpoint()
        f.accept("overlap-more", "원본 데이터의 갱신과 삭제 요청의 순서가 달라질 수 있기 때문이에요")
        assertThat(f.responses()).hasSize(1)
        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.accept("overlap-tail", "이 순서를 어떻게 보장해야 하나요?")
        assertThat(f.responses()).hasSize(2)
        f.assertNoInterventionOrAudioDisruption()
    }

    @Test
    fun `ready MCP output waits for the long learner reply and its final publication`(): Unit = Fixture(withTools = true).use { f ->
        val tutor = f.beginTutor()
        val call = mapOf(
            "type" to "function_call", "status" to "completed", "call_id" to "owned-tool-call",
            "name" to "list_studies", "arguments" to "{}",
        )
        f.response("response.done", "tutor", tutor, output = listOf(call))
        assertThat(f.controller.beginToolExecution("owned-tool-call")).isTrue()
        f.start(2)
        f.checkpoint()
        f.accept("during-tool", "주제를 읽는 동안 지금 고민을 더 설명하면")
        assertThat(f.controller.completeToolExecution("owned-tool-call", VoiceTutorMcpToolResult("{}", false))).isTrue()
        val output = f.events("conversation.item.create").single().path("item")
        f.provider("conversation.item.added", "item" to output)
        assertThat(f.responses()).hasSize(1)
        f.checkpoint()
        f.accept("after-tool", "저장된 하위 주제 중에서 이 사례에 맞는 것을 선택하고 싶어요")
        assertThat(f.responses()).hasSize(1)
        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.commit("tail")
        f.transcript("tail", "이제 설명해 주세요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.controller.confirmInputPublished("tail")
        assertThat(f.responses()).hasSize(2)
        f.provider("conversation.item.added", "item" to output)
        assertThat(f.responses()).hasSize(2)
        f.assertNoInterventionOrAudioDisruption()
    }

    @Test
    fun `an accepted checkpoint with no final transcript stays unresponsive`(): Unit = Fixture().use { f ->
        f.start(1)
        f.checkpoint()
        f.accept("accepted", "실제 답변이 포함된 긴 설명입니다")
        f.checkpoint()
        f.emptyCommit(f.commits().last().path("event_id").asText())
        assertThat(f.responses()).isEmpty()
        f.advance(Duration.ofSeconds(1))
        f.stop(1)
        f.emptyCommit(f.commits().last().path("event_id").asText())
        assertThat(f.responses()).isEmpty()
        assertThat(f.publications().map { it.itemId }).containsExactly("accepted")
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).isEmpty()
        f.assertNoInterventionOrAudioDisruption()
    }

    private class Fixture(withTools: Boolean = false, externalClock: (() -> Long)? = null) : AutoCloseable {
        private val now = AtomicLong()
        val controls = CopyOnWriteArrayList<JsonNode>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12), responseTimeout = Duration.ofSeconds(60),
            nanoTime = externalClock ?: now::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(), toolsEnabled = withTools,
        )
        private val subscriptions = listOf(
            controller.providerEvents().subscribe({ controls.add(mapper.readTree(it)) }, errors::add),
            controller.inputActions().subscribe(actions::add, errors::add),
            controller.toolActions().subscribe({}, errors::add),
            controller.clientEvents().subscribe({}, errors::add),
        )

        init {
            controller.startOpeningResponse()
            val opening = responses().single()
            response("response.created", "opening", opening)
            response("response.done", "opening", opening)
            provider("output_audio_buffer.stopped", "response_id" to "opening")
            controls.clear()
        }

        fun advance(duration: Duration) { now.addAndGet(duration.toNanos()) }
        fun start(sequence: Long) = speech(true, sequence)
        fun stop(sequence: Long) = speech(false, sequence)
        fun checkpoint() {
            advance(Duration.ofSeconds(12))
            controller.fireContinuousSpeechDeadline()
        }
        private fun speech(started: Boolean, sequence: Long) {
            controller.observeClientEvent(mapper.writeValueAsString(mapOf(
                "type" to if (started) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
                "sequence" to sequence,
            )))
        }
        fun commit(id: String) = provider("input_audio_buffer.committed", "item_id" to id)
        fun transcript(id: String, text: String) = provider(
            "conversation.item.input_audio_transcription.completed", "item_id" to id, "transcript" to text,
        )
        fun deleted(id: String) = provider("conversation.item.deleted", "item_id" to id)
        fun emptyCommit(eventId: String) = provider(
            "error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to eventId),
        )
        fun assess(decision: VoiceTutorInputDecision) {
            val action = assessments().last()
            controller.completeInputAssessment(action.token, Result.success(VoiceTutorInputAssessmentResult(
                action.utterances.map { VoiceTutorInputItemAssessment(it.itemId, decision) },
            )))
        }
        fun accept(id: String, text: String) {
            commit(id)
            transcript(id, text)
            assess(VoiceTutorInputDecision.MEANINGFUL)
            controller.confirmInputPublished(id)
        }
        fun beginTutor(): JsonNode {
            start(1)
            advance(Duration.ofSeconds(1))
            stop(1)
            accept("first-turn", "저장된 하위 주제들을 알려 주세요")
            val tutor = responses().single()
            response("response.created", "tutor", tutor)
            return tutor
        }
        fun response(type: String, id: String, control: JsonNode, output: List<Any> = emptyList()) = provider(
            type, "response" to mapOf(
                "id" to id, "status" to if (type == "response.created") "in_progress" else "completed",
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to control.path("event_id").asText()),
                "output" to output,
            ),
        )
        fun provider(type: String, vararg fields: Pair<String, Any>) {
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, *fields)))
        }
        fun events(type: String) = controls.filter { it.path("type").asText() == type }
        fun commits() = events("input_audio_buffer.commit")
        fun responses() = events("response.create")
        fun assessments() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()
        fun publications() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Publish>()
        fun assertNoInterventionOrAudioDisruption() {
            assertThat(controls.map { it.path("type").asText() })
                .doesNotContain("response.cancel", "input_audio_buffer.clear", "output_audio_buffer.clear", "conversation.item.truncate")
            assertThat(responses().any {
                it.path("response").path("metadata").path(VoiceTutorRealtimeContract.TURN_METADATA_KEY).asText() ==
                    VoiceTutorRealtimeContract.CONTINUOUS_INTERVENTION_TURN
            }).isFalse()
            assertThat(errors).isEmpty()
        }
        override fun close() {
            controller.close()
            subscriptions.forEach { it.dispose() }
        }
    }

    private companion object { val mapper = JsonMapperProvider.mapper }
}
