package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract as Contract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata as Metadata
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningPhase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionReadback
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.reactive.asFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory WebSocket frames and suspended coroutines only: no provider, audio, database or classifier. */
class VoiceTutorNativeSessionRelayTest {
    @Test
    fun `empty acoustic interruption settles UI waiting after successful preparation without reviving or replaying the proposal`() {
        val release = CompletableDeferred<VoiceTutorMcpToolResult>()
        val tools = FakeTools { release.await() }
        Fixture(tools = tools).use { f ->
            f.opening(); f.learner(1, "learner-1"); f.transcript("learner-1")
            f.toolResponse("prepare-response", "prepare-1", "prepare_voice_study_mutation")
            f.await("preparation began") { tools.invocations.size == 1 }
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(json(mapOf("type" to type, "sequence" to 2))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("new acoustic input committed") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            val emptyCommit = f.outgoing.last { it.path("type").asText() == "input_audio_buffer.commit" }
            release.complete(VoiceTutorMcpToolResult("{\"prepared\":true}", false,
                mutationConfirmationQuestion = "MSA 주제를 레벨 8로 만들까요?"))
            f.await("accepted preparation finishes once") { f.outputs().size == 1 }
            f.ack(f.outputs().single())
            f.provider("error", "error" to mapOf("code" to "input_audio_buffer_commit_empty",
                "event_id" to emptyCommit.path("event_id").asText()))
            f.await("latest empty input stops indefinite response waiting") {
                f.ui.any { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT && it.path("sequence").asLong() == 2L }
            }
            assertThat(f.responses()).hasSize(2)
            assertThat(tools.invocations.map { it.name }).containsExactly("prepare_voice_study_mutation")
            assertThat(f.ui.any { it.path("type").asText() == Contract.INPUT_RETRY_EVENT }).isFalse()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
            f.learner(3, "learner-3")
            assertThat(f.responses()).hasSize(3)
            assertThat(f.responses().last().path("response").has("instructions")).isFalse()
            assertThat(tools.invocations).hasSize(1)
        }
    }

    @Test
    fun `barge in clears live provider output before replying to the correction and never relays stale speech`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1"); f.created("response-1")
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.await("old response began playing") { f.ui.any { it.path("response_id").asText() == "response-1" } }
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(JsonMapperProvider.mapper.writeValueAsString(mapOf("type" to type, "sequence" to 2))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("barge in cancellation and new commit reached the provider") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            assertThat(f.outgoing.map { it.path("type").asText() })
                .containsSubsequence("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.commit")
            f.provider("input_audio_buffer.committed", "item_id" to "learner-2")
            f.provider("response.done", "response" to mapOf("id" to "response-1", "status" to "cancelled", "output" to emptyList<Any>()))
            f.await("intentional interruption relayed") { f.ui.any { it.path("type").asText() == Contract.RESPONSE_INTERRUPTED_EVENT } }
            assertThat(f.responses()).hasSize(2)
            f.provider("output_audio_buffer.cleared", "response_id" to "response-1")
            f.await("latest learner reply begins after clear") { f.responses().size == 3 }
            f.provider("response.output_audio_transcript.done", "response_id" to "response-1", "item_id" to "stale-tutor", "transcript" to "stale")
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.provider("conversation.item.created", "item" to mapOf("id" to "after-stale", "type" to "message"))
            f.await("late events processed") { f.ui.any { it.path("item").path("id").asText() == "after-stale" } }
            assertThat(f.ui.any { it.path("item_id").asText() == "stale-tutor" }).isFalse()
            assertThat(f.stored.any { it.path("item_id").asText() == "stale-tutor" }).isFalse()
            assertThat(f.ui.count { it.path("type").asText() == "output_audio_buffer.started" && it.path("response_id").asText() == "response-1" }).isEqualTo(1)
            assertThat(f.ui.any { it.path("type").asText() == Contract.INPUT_RETRY_EVENT }).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `server observer reports saved grading completion while learner speaks without another model poll`() {
        val release = CompletableDeferred<Unit>()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }.apply {
            reviewedProgress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.GRADING, 7, "42", "grade-42")
            pollResponse = { progress -> release.await(); VoiceTutorMcpToolResult("{}", false,
                learningProgress = progress.copy(phase = VoiceTutorLearningPhase.GRADED)) }
        }
        Fixture(tools = tools).use { f ->
            val answer = f.manualQuestion()
            f.answerControl(Contract.ANSWER_FINISH_EVENT, answer)
            f.await("empty audio capture is reviewable") { f.answerStates().last().path("phase").asText() == "review" }
            f.answerControl(Contract.ANSWER_SUBMIT_EVENT, answer, "완성된 수정 답변")
            f.await("explicit submit creates its server call") { f.serverCalls().size == 1 }
            f.ack(f.serverCalls().single())
            f.await("server observation starts independently") { tools.polled.size == 1 }
            f.await("actual grading lookup is visible while suspended") {
                f.ui.any { it.path("type").asText() == Contract.OPERATION_EVENT &&
                    it.path("name").asText() == "get_record" && it.path("phase").asText() == "started" }
            }
            assertThat(f.ui.any { it.path("type").asText() == Contract.OPERATION_EVENT &&
                it.path("name").asText() == "get_record" && it.path("phase").asText() == "completed" }).isFalse()
            f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STARTED_EVENT, "sequence" to 2)))
            val responseCount = f.responses().size
            release.complete(Unit)
            f.await("saved grade is displayed during newer speech") {
                f.ui.lastOrNull { it.path("type").asText() == Contract.SESSION_STATE_EVENT }?.path("phase")?.asText() == "graded"
            }
            val operationEvents = f.ui.filter { it.path("type").asText() == Contract.OPERATION_EVENT }
            val lookup = operationEvents.filter { it.path("name").asText() == "get_record" }
            assertThat(lookup.map { it.path("phase").asText() }).containsExactly("started", "completed")
            assertThat(lookup.map { it.path("operationId").asText() }.distinct()).hasSize(1)
            assertThat(operationEvents.map { it.path("sequence").asLong() }).isSorted().doesNotHaveDuplicates()
            assertThat(operationEvents.joinToString()).doesNotContain("완성된 수정 답변", "grade-42", "recordId")
            assertThat(f.responses()).hasSize(responseCount)
            assertThat(tools.invocations.map { it.name }).containsExactly("list_studies")
            assertThat(tools.polled).hasSize(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `server observation is cancelled with the control session and cannot publish a late completion`() {
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            learningProgress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.QUESTION_GENERATING, 7, correlationId = "generation-1")) }.apply {
            pollResponse = { progress ->
                entered.set(true)
                try { CompletableDeferred<Unit>().await(); VoiceTutorMcpToolResult("{}", false, learningProgress = progress) }
                finally { cancelled.set(true) }
            }
        }
        val f = Fixture(tools = tools)
        try {
            f.opening(); f.learner(1, "learner-1"); f.transcript("learner-1")
            f.toolResponse("generate", "generate-call", "request_question")
            f.await("independent generation poll started") { entered.get() }
            f.close()
            f.await("session disposal cancelled the suspended read") { cancelled.get() }
            assertThat(f.ui.none { it.path("phase").asText() == "question_ready" }).isTrue()
        } finally { f.close() }
    }

    @Test
    fun `manual finish waits for its audit write then explicit edited submission uses only the reviewed port`() {
        val writeEntered = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }
        Fixture(tools = tools, store = { row ->
            if (row.path("item_id").asText() == "answer-1") { writeEntered.set(true); release.await() }
        }).use { f ->
            val answer = f.manualQuestion()
            f.capturedLearner(2, "answer-1")
            f.provider("conversation.item.input_audio_transcription.completed", "item_id" to "answer-1", "transcript" to "인식된 초안")
            f.answerControl(Contract.ANSWER_FINISH_EVENT, answer)
            f.await("answer write remains suspended in finalizing") {
                writeEntered.get() && f.answerStates().last().path("phase").asText() == "finalizing"
            }
            assertThat(f.responses()).hasSize(3)
            assertThat(tools.reviewed).isEmpty()
            release.complete(Unit)
            f.await("durable source releases review") { f.answerStates().last().path("phase").asText() == "review" }
            f.answerControl(Contract.ANSWER_SUBMIT_EVENT, answer, "사용자가 직접 수정한 답변")
            f.await("explicit submit emits a synthetic call") { f.serverCalls().size == 1 }
            assertThat(tools.reviewed).isEmpty()
            val request = f.serverCalls().single()
            assertThat(request.toString()).doesNotContain("사용자가 직접 수정한 답변")
            f.ack(request)
            f.await("reviewed port returned canonical output") { f.outputs().size == 2 }
            assertThat(tools.reviewed.single().second.text).isEqualTo("사용자가 직접 수정한 답변")
            assertThat(tools.reviewed.single().second.learnerProviderItemIds).containsExactly("answer-1")
            assertThat(tools.invocations.map { it.name }).containsExactly("list_studies")
            assertThat(f.responses()).hasSize(3)
            f.ack(f.outputs().last())
            f.await("canonical output acknowledgement releases feedback continuation") { f.responses().size == 4 }
            assertThat(f.answerStates().last().path("phase").asText()).isEqualTo("submitted")
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `model authored submit is rejected without invoking any canonical answer writer`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1")
            f.toolResponse("model-submit", "model-submit-call", "submit_answer")
            f.await("model submit receives explicit confirmation requirement") { f.outputs().size == 1 }
            val output = mapper.readTree(f.outputs().single().path("item").path("output").asText())
            assertThat(output.path("error").path("code").asText()).isEqualTo("USER_CONFIRMATION_REQUIRED")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.tools.reviewed).isEmpty()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `explicit capture skip uses the reviewed skip port and reopens ordinary dialogue only after result acknowledgement`() {
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }
        Fixture(tools = tools).use { f ->
            val answer = f.manualQuestion()
            f.answerControl(Contract.ANSWER_SKIP_EVENT, answer)
            f.await("skip is scheduled only by explicit control") { f.serverCalls().size == 1 }
            f.ack(f.serverCalls().single())
            f.await("canonical skip returned") { f.outputs().size == 2 }
            assertThat(tools.skipped.single().second.recordId).isEqualTo("42")
            assertThat(tools.skipped.single().second.text).isEmpty()
            assertThat(tools.reviewed).isEmpty()
            f.await("canonical skip state reaches the independent client output stream") {
                f.answerStates().last().path("phase").asText() == "cancelled"
            }
            assertThat(f.responses()).hasSize(3)
            f.ack(f.outputs().last())
            f.await("skip continuation released") { f.responses().size == 4 }
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `renewed speech cancels an unstarted reply and the relay tick answers only the latest committed input`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1"); f.created("superseded")
            assertThat(f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STARTED_EVENT, "sequence" to 2))))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("exact unstarted provider response cancelled") {
                f.outgoing.any { it.path("type").asText() == "response.cancel" &&
                    it.path("response_id").asText() == "superseded" }
            }
            assertThat(f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STOPPED_EVENT, "sequence" to 2))))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("newest learner buffer committed") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            f.provider("input_audio_buffer.committed", "item_id" to "learner-2")
            f.provider("response.output_audio_transcript.done", "response_id" to "superseded",
                "item_id" to "stale-tutor", "transcript" to "Synthetic discarded response")
            f.provider("response.done", "response" to mapOf("id" to "superseded", "status" to "cancelled",
                "output" to listOf(mapOf("id" to "stale-tutor", "type" to "message", "content" to listOf(
                    mapOf("type" to "audio", "transcript" to ""))))))
            // No more inbound events: the relay clock must release the quiet deadline itself.
            f.await("quiet deadline releases newest native response without ASR") { f.responses().size == 3 }
            f.created("latest")
            f.provider("response.done", "response" to mapOf("id" to "latest", "status" to "completed",
                "output" to emptyList<Any>()))
            f.await("only newest acoustic input is settled") {
                f.ui.any { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT && it.path("sequence").asLong() == 2L }
            }
            assertThat(f.ui.filter { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT }
                .map { it.path("sequence").asLong() }).containsExactly(2L)
            assertThat(f.ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
            assertThat(f.ui.map { it.path("item_id").asText() }).doesNotContain("stale-tutor")
            assertThat(f.stored.map { it.path("item_id").asText() }).doesNotContain("stale-tutor")
            assertThat(f.outgoing.count { it.path("type").asText() == "response.cancel" }).isEqualTo(1)
            assertThat(f.outgoing.map { it.path("type").asText() }).doesNotContain("output_audio_buffer.clear")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `a provider rejected opening recovers on the live relay and subsequent learner speech gets a reply`() {
        Fixture().use { f ->
            f.connect()
            val first = f.responses().single()
            f.provider("error", "error" to mapOf("type" to "invalid_request_error", "code" to "invalid_type",
                "event_id" to first.path("event_id").asText(), "param" to "response.metadata.buddystudy_quota_notice"))
            f.await("rejected create releases a bounded opening retry") { f.responses().size == 2 }
            f.completeAudioResponse("recovered-opening", "tutor-0")
            f.learner(1, "learner-1")
            f.completeAudioResponse("learner-reply", "tutor-1")
            f.await("audible learner reply is forwarded") {
                f.ui.any { it.path("type").asText() == "output_audio_buffer.stopped" &&
                    it.path("response_id").asText() == "learner-reply" }
            }

            assertThat(f.responses()).hasSize(3)
            assertThat(f.ui.map { it.path("type").asText() }).doesNotContain("error", Contract.INPUT_RETRY_EVENT)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `ordinary native reply proceeds while tutor audit persistence is suspended and learner ASR is absent`() {
        val release = CompletableDeferred<Unit>()
        val entered = AtomicBoolean()
        Fixture(store = {
            entered.set(true)
            release.await()
        }).use { f ->
            f.opening()
            f.await("opening audit started") { entered.get() }

            f.learner(1, "learner-1")

            assertThat(f.responses()).hasSize(2)
            assertThat(release.isCompleted).isFalse()
            assertThat(f.stored.map { it.path("item_id").asText() }).containsExactly("tutor-0")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.ui.any { it.path("type").asText() == Contract.SIDEBAND_READY_EVENT }).isTrue()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `read tools bypass transcript IO but a continuation requires the exact tool output acknowledgement`() {
        val auditRelease = CompletableDeferred<Unit>()
        Fixture(store = { auditRelease.await() }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.toolResponse("response-1", "read-1", "list_studies")
            f.await("read tool executed without persisted transcript") { f.outputs().size == 1 }

            val invocation = f.tools.invocations.single()
            assertThat(invocation.name).isEqualTo("list_studies")
            assertThat(invocation.context.realtimeModelTools).isTrue()
            assertThat(invocation.context.dialogueBoundary?.latestAcceptedLearnerProviderItemId)
                .isEqualTo("learner-1")
            assertThat(auditRelease.isCompleted).isFalse()
            assertThat(f.responses()).hasSize(2)

            val output = f.outputs().single()
            val wrongItem = output.path("item").deepCopy<ObjectNode>().put("output", "{\"wrong\":true}")
            f.provider("conversation.item.created", "item" to wrongItem)
            f.await("unmatched output acknowledgement processed") {
                f.ui.any { it.path("item").path("output").asText() == "{\"wrong\":true}" }
            }
            assertThat(f.responses()).hasSize(2)

            f.ack(output)
            f.await("exact tool result released one continuation") { f.responses().size == 3 }
            f.ack(output)
            f.await("duplicate acknowledgement processed") {
                f.ui.any { it.path("item") == output.path("item") }
            }
            assertThat(f.responses()).hasSize(3)
            assertThat(f.tools.invocations).hasSize(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `mutation tools wait for both tutor and learner transcript IO before executing`() {
        val tutorRelease = CompletableDeferred<Unit>()
        val learnerRelease = CompletableDeferred<Unit>()
        val tutorStored = AtomicBoolean()
        val learnerStored = AtomicBoolean()
        val storedAtExecution = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val tools = FakeTools {
            storedAtExecution.add(tutorStored.get() to learnerStored.get())
            success()
        }
        Fixture(tools = tools, store = { row ->
            when (row.path("item_id").asText()) {
                "tutor-0" -> { tutorRelease.await(); tutorStored.set(true) }
                "learner-1" -> { learnerRelease.await(); learnerStored.set(true) }
            }
        }).use { f ->
            f.opening()
            f.await("tutor audit started") { f.stored.size == 1 }
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.toolResponse("response-1", "prepare-1", "prepare_voice_study_mutation")
            // Tool-only response events are deliberately hidden from the UI.
            // A later provider frame is a FIFO processing barrier, not an audible response.
            f.provider("conversation.item.created", "item" to mapOf("id" to "mutation-io-barrier", "type" to "message"))
            f.await("provider processed the mutation response before the barrier") {
                f.ui.any { it.path("item").path("id").asText() == "mutation-io-barrier" }
            }
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.ui.none { it.path("type").asText() == "response.done" && it.path("response").path("id").asText() == "response-1" }).isTrue()

            tutorRelease.complete(Unit)
            f.await("learner audit started after tutor write") { f.stored.size == 2 }
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.outputs()).isEmpty()

            learnerRelease.complete(Unit)
            f.await("mutation runs only after its two audit writes settle") { f.outputs().size == 1 }
            assertThat(f.tools.invocations.map { it.name }).containsExactly("prepare_voice_study_mutation")
            assertThat(storedAtExecution).containsExactly(true to true)
            assertThat(f.responses()).hasSize(2)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `normal completion of the control worker does not cancel an in flight tool or close provider IO`() {
        val result = CompletableDeferred<VoiceTutorMcpToolResult>()
        val cancelled = AtomicBoolean()
        val tools = FakeTools {
            try { result.await() }
            catch (error: CancellationException) { cancelled.set(true); throw error }
        }
        Fixture(tools = tools).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.toolResponse("response-1", "read-1", "list_studies")
            f.await("tool began") { tools.invocations.size == 1 }
            assertThat(f.controls.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK)

            // A real provider item event proves the receive/client workers are
            // still processing after the unrelated control source completed.
            f.provider("conversation.item.created", "item" to mapOf("id" to "worker-barrier", "type" to "message"))
            f.await("provider remained connected after control completion") {
                f.ui.any { it.path("item").path("id").asText() == "worker-barrier" }
            }
            assertThat(cancelled.get()).isFalse()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.receiveCancelled.get()).isFalse()

            result.complete(success())
            f.await("in flight tool output survived control completion") { f.outputs().size == 1 }
            f.ack(f.outputs().single())
            f.await("tool continuation survived control completion") { f.responses().size == 3 }
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `provider failure cancels suspended persistence but forces a durable integrity fence before finalization`() {
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        Fixture(store = {
            entered.set(true)
            try { CompletableDeferred<Unit>().await() }
            catch (error: CancellationException) { cancelled.set(true); throw error }
        }).use { f ->
            f.opening()
            f.await("audit write is suspended") { entered.get() }
            val failure = IllegalStateException("synthetic provider failure")
            assertThat(f.incoming.tryEmitError(failure)).isEqualTo(Sinks.EmitResult.OK)

            f.await("provider failure escaped while persistence was blocked") { f.errors.isNotEmpty() }
            f.await("blocked persistence was cancelled") { cancelled.get() }
            assertThat(f.errors).hasSize(1)
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `provider error after a committed learner turn with absent ASR cannot grade an earlier saved prefix`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            assertThat(f.incoming.tryEmitError(IllegalStateException("synthetic receive failure")))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("missing learner source is fenced on receive failure") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `client output worker failure also fences unresolved learner source without waiting for ASR`() {
        Fixture(onUi = { event ->
            if (event.path("item").path("id").asText() == "fatal-ui-event") {
                throw IllegalStateException("synthetic client output failure")
            }
        }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.provider("conversation.item.created", "item" to mapOf("id" to "fatal-ui-event", "type" to "message"))
            f.await("client output failure fences source") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `unacknowledged learner commit is incomplete evidence even before provider assigns an item id`() {
        Fixture().use { f ->
            f.opening()
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(json(mapOf("type" to type, "sequence" to 1))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("learner commit sent without acknowledgement") {
                f.outgoing.any { it.path("type").asText() == "input_audio_buffer.commit" }
            }
            assertThat(f.incoming.tryEmitError(IllegalStateException("synthetic commit failure")))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("unacknowledged commit is fenced") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
        }
    }

    @Test
    fun `a provider error with only fully stored source preserves the original failure`() {
        val stored = AtomicBoolean()
        Fixture(store = { stored.set(true) }).use { f ->
            f.opening()
            f.await("opening source stored") { stored.get() }
            // FIFO persistence completion is synchronous after this non-suspending store callback.
            f.provider("conversation.item.created", "item" to mapOf("id" to "clean-source-barrier", "type" to "message"))
            f.await("provider remained idle with clean source") {
                f.ui.any { it.path("item").path("id").asText() == "clean-source-barrier" }
            }
            val failure = IllegalStateException("synthetic idle receive failure")
            assertThat(f.incoming.tryEmitError(failure)).isEqualTo(Sinks.EmitResult.OK)
            f.await("original error preserved") { f.errors.isNotEmpty() }
            assertThat(f.errors).containsExactly(failure)
        }
    }

    @Test
    fun `completed audible tutor output missing its transcript is fenced rather than treated as a silent turn`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.created("response-1")
            f.provider("response.output_item.added", "response_id" to "response-1",
                "item" to mapOf("id" to "missing-tutor-source", "type" to "message"))
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.provider("response.done", "response" to mapOf("id" to "response-1", "status" to "completed",
                "output" to listOf(mapOf("id" to "missing-tutor-source", "type" to "message",
                    "content" to listOf(mapOf("type" to "audio"))))))
            f.provider("output_audio_buffer.stopped", "response_id" to "response-1")
            f.await("missing completed tutor source fenced") { f.integrityMarkers.size == 1 }
            assertThat(f.errors).isEmpty()
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `audit write failures do not terminate ordinary voice or cause automatic tool execution`() {
        Fixture(store = { throw IllegalStateException("synthetic audit failure") }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.await("both audit writes were attempted") { f.stored.size == 2 }
            f.created("response-1")
            f.provider("response.done", "response" to mapOf(
                "id" to "response-1", "status" to "completed", "output" to emptyList<Any>(),
            ))
            f.learner(2, "learner-2")
            f.await("failed audit writes permanently mark incomplete evidence") { f.integrityMarkers.size == 1 }

            assertThat(f.responses()).hasSize(3)
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `a rejected transcript write marks incomplete evidence without blocking ordinary voice`() {
        Fixture(persistAccepted = false).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.await("rejected write records incomplete evidence") { f.integrityMarkers.size == 1 }
            assertThat(f.stored).hasSize(1)
            assertThat(f.responses()).hasSize(2)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `failed ASR marks incomplete evidence without blocking the native audio response`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.provider("conversation.item.input_audio_transcription.failed", "item_id" to "learner-1")
            f.await("failed ASR records incomplete evidence") { f.integrityMarkers.size == 1 }
            assertThat(f.responses()).hasSize(2)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `unconfirmed integrity marker fails the relay instead of allowing partial learning evidence`() {
        val markerResult = CompletableDeferred<Boolean>()
        Fixture(store = { throw IllegalStateException("synthetic audit failure") }, markIncomplete = { markerResult.await() }).use { f ->
            f.opening()
            f.await("incomplete marker was attempted") { f.integrityMarkers.size == 1 }
            markerResult.complete(false)
            f.await("unconfirmed marker fails the call") { f.errors.isNotEmpty() }
            assertThat(f.completed.get()).isFalse()
            assertThat(f.tools.invocations).isEmpty()
        }
    }

    private class Fixture(
        val tools: FakeTools = FakeTools(),
        store: suspend (JsonNode) -> Unit = {},
        persistAccepted: Boolean = true,
        markIncomplete: suspend () -> Boolean = { true },
        onUi: suspend (JsonNode) -> Unit = {},
    ) : AutoCloseable {
        val incoming = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
        val controls = Sinks.many().unicast().onBackpressureBuffer<String>()
        private val terminal = Sinks.many().unicast().onBackpressureBuffer<VoiceTutorRelayTermination>()
        val outgoing = CopyOnWriteArrayList<JsonNode>()
        val stored = CopyOnWriteArrayList<JsonNode>()
        val ui = CopyOnWriteArrayList<JsonNode>()
        val integrityMarkers = CopyOnWriteArrayList<JsonNode>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val completed = AtomicBoolean()
        val receiveCancelled = AtomicBoolean()
        private val changed = Semaphore(0)
        private val buffers = DefaultDataBufferFactory.sharedInstance
        private val subscription: Disposable

        init {
            val socket = Proxy.newProxyInstance(WebSocketSession::class.java.classLoader,
                arrayOf(WebSocketSession::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getId" -> "native-relay-fixture"
                    "receive" -> incoming.asFlux().doOnCancel { receiveCancelled.set(true) }
                    "send" -> Flux.from(arguments!![0] as Publisher<*>)
                        .cast(WebSocketMessage::class.java)
                        .doOnNext {
                            val event = mapper.readTree(it.payloadAsText)
                            if (event.path("type").asText() == "response.create") {
                                // Emulate the provider's metadata schema, not an unchecked echo fixture.
                                assertThat(event.path("response").path("metadata").all { value -> value.isTextual })
                                    .withFailMessage("Realtime response metadata values must be strings").isTrue()
                            }
                            outgoing.add(event); changed.release()
                        }.then()
                    "textMessage" -> message(arguments!![0] as String)
                    "bufferFactory" -> buffers
                    "isOpen" -> true
                    "close", "closeStatus" -> Mono.empty<Void>()
                    "toString" -> "NativeVoiceRelayFixture"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> error("Unexpected WebSocketSession call: ${method.name}")
                }
            } as WebSocketSession
            subscription = relayVoiceTutorNativeSession(
                socket, context(), controls.asFlux().asFlow(), terminal.asFlux().asFlow(), tools,
                connectTimeout = Duration.ofSeconds(3), responseTimeout = Duration.ofSeconds(15),
            ) { raw, persist, forward ->
                val event = mapper.readTree(raw)
                if (event.path("type").asText() == Metadata.INCOMPLETE_EVENT) {
                    assertThat(persist).isFalse()
                    assertThat(forward).isFalse()
                    integrityMarkers.add(event)
                    changed.release()
                    return@relayVoiceTutorNativeSession markIncomplete()
                }
                if (persist) {
                    stored.add(event)
                    changed.release()
                    store(event)
                }
                if (forward) { ui.add(event); changed.release(); onUi(event) }
                persist && persistAccepted
            }.subscribe({}, { errors += it; changed.release() }, { completed.set(true); changed.release() })
        }

        fun opening() {
            connect()
            completeAudioResponse("response-0", "tutor-0")
        }

        fun connect() {
            await("server-owned session update") { outgoing.any { it.path("type").asText() == "session.update" } }
            val update = outgoing.first { it.path("type").asText() == "session.update" }
            assertThat(update.path("session").path("audio").path("input").path("turn_detection").isNull).isTrue()
            provider("session.updated", "session" to update.path("session"))
            await("opening native response") { responses().size == 1 }
        }

        fun completeAudioResponse(responseId: String, itemId: String) {
            created(responseId)
            provider("response.output_item.added", "response_id" to responseId,
                "item" to mapOf("id" to itemId, "type" to "message"))
            provider("output_audio_buffer.started", "response_id" to responseId)
            provider("response.output_audio_transcript.done", "response_id" to responseId,
                "item_id" to itemId, "transcript" to "어떤 주제로 이야기할까요?")
            provider("response.done", "response" to mapOf("id" to responseId, "status" to "completed",
                "output" to listOf(mapOf("id" to itemId, "type" to "message", "content" to listOf(
                    mapOf("type" to "audio", "transcript" to "어떤 주제로 이야기할까요?"))))))
            provider("output_audio_buffer.stopped", "response_id" to responseId)
        }

        fun learner(sequence: Long, id: String) {
            val previousResponses = responses().size
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(controls.tryEmitNext(json(mapOf("type" to type, "sequence" to sequence))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            await("acoustic stop committed by the server") {
                outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == sequence.toInt()
            }
            provider("input_audio_buffer.committed", "item_id" to id)
            await("native reply released without a text assessor") { responses().size == previousResponses + 1 }
        }

        fun transcript(id: String) = provider("conversation.item.input_audio_transcription.completed",
            "item_id" to id, "transcript" to "Redis를 공부하고 싶어요.")

        fun manualQuestion(): JsonNode {
            opening(); learner(1, "learner-1"); transcript("learner-1")
            toolResponse("question-tool", "question-call", "list_studies")
            await("saved question tool result") { outputs().size == 1 }
            ack(outputs().single())
            await("saved question readback response") { responses().size == 3 }
            completeAudioResponse("readback", "saved-question")
            await("readback drained and manual capture open") { answerStates().lastOrNull()?.path("phase")?.asText() == "listening" }
            return answerStates().last()
        }

        fun capturedLearner(sequence: Long, id: String) {
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(controls.tryEmitNext(json(mapOf("type" to type, "sequence" to sequence)))).isEqualTo(Sinks.EmitResult.OK)
            }
            await("captured speech committed without response") {
                outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == sequence.toInt()
            }
            provider("input_audio_buffer.committed", "item_id" to id)
        }

        fun answerControl(type: String, answer: JsonNode, text: String? = null) {
            val event = linkedMapOf<String, Any>("type" to type, "answerId" to answer.path("answerId").asText(),
                "recordId" to answer.path("recordId").asText())
            text?.let { event["text"] = it }
            assertThat(controls.tryEmitNext(json(event))).isEqualTo(Sinks.EmitResult.OK)
        }

        fun answerStates() = ui.filter { it.path("type").asText() == Contract.ANSWER_STATE_EVENT }
        fun serverCalls() = outgoing.filter { it.path("item").path("type").asText() == "function_call" }

        fun created(id: String) = provider("response.created", "response" to mapOf("id" to id,
            "metadata" to responses().last().path("response").path("metadata")))

        fun toolResponse(responseId: String, callId: String, name: String) {
            created(responseId)
            provider("response.done", "response" to mapOf("id" to responseId, "status" to "completed",
                "output" to listOf(mapOf("id" to "item-$callId", "type" to "function_call",
                    "status" to "completed", "call_id" to callId, "name" to name, "arguments" to "{}"))))
        }

        fun provider(type: String, vararg fields: Pair<String, Any>) {
            assertThat(incoming.tryEmitNext(message(json(mapOf("type" to type) + fields))))
                .isEqualTo(Sinks.EmitResult.OK)
        }

        fun ack(output: JsonNode) = provider("conversation.item.created", "item" to output.path("item"))
        fun responses() = outgoing.filter { it.path("type").asText() == "response.create" }
        fun outputs() = outgoing.filter { it.path("item").path("type").asText() == "function_call_output" }

        fun await(reason: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while (!condition() && System.nanoTime() < deadline) changed.tryAcquire(20, TimeUnit.MILLISECONDS)
            assertThat(condition()).withFailMessage("Timed out: %s; completed=%s, errors=%s", reason,
                completed.get(), errors.map { it.javaClass.simpleName }).isTrue()
        }

        private fun message(raw: String) = WebSocketMessage(WebSocketMessage.Type.TEXT, buffers.wrap(raw.toByteArray()))
        override fun close() { subscription.dispose() }
    }

    private data class Invocation(val context: VoiceTutorWebRtcControlContext, val name: String)
    private class FakeTools(private val result: suspend () -> VoiceTutorMcpToolResult = { success() }) : VoiceTutorMcpToolPort {
        val invocations = CopyOnWriteArrayList<Invocation>()
        val polled = CopyOnWriteArrayList<VoiceTutorLearningProgress>()
        var reviewedProgress: VoiceTutorLearningProgress? = null
        var pollResponse: suspend (VoiceTutorLearningProgress) -> VoiceTutorMcpToolResult = { progress -> VoiceTutorMcpToolResult("{}", false, learningProgress = progress) }
        override suspend fun pollLearningProgress(context: VoiceTutorWebRtcControlContext, progress: VoiceTutorLearningProgress): VoiceTutorMcpToolResult {
            polled += progress
            return pollResponse(progress)
        }
        val reviewed = CopyOnWriteArrayList<Pair<VoiceTutorWebRtcControlContext, VoiceTutorReviewedAnswer>>()
        val skipped = CopyOnWriteArrayList<Pair<VoiceTutorWebRtcControlContext, VoiceTutorReviewedAnswer>>()
        override fun definitions(): List<VoiceTutorMcpToolDefinition> = error("The legacy classified tool catalog must not be used")
        override fun realtimeDefinitions() = listOf("list_studies", "prepare_voice_study_mutation", "request_question").map { name ->
            VoiceTutorMcpToolDefinition(name, "Synthetic native tool", mapOf("type" to "object",
                "properties" to emptyMap<String, Any>(), "additionalProperties" to false))
        }
        override suspend fun execute(context: VoiceTutorWebRtcControlContext, toolName: String, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
            invocations += Invocation(context, toolName)
            return result()
        }
        override suspend fun submitReviewedAnswer(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
            reviewed += context to answer
            return VoiceTutorMcpToolResult("""{"queued":true,"gradingRequestId":"grade-42"}""", false, learningProgress = reviewedProgress)
        }
        override suspend fun skipReviewedQuestion(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
            skipped += context to answer
            return VoiceTutorMcpToolResult("""{"skipped":true,"recordId":"42"}""", false)
        }
    }

    private companion object {
        val mapper = JsonMapperProvider.mapper
        fun json(value: Any): String = mapper.writeValueAsString(value)
        fun success() = VoiceTutorMcpToolResult("{\"studies\":[]}", false)
        fun context(): VoiceTutorWebRtcControlContext {
            val now = Instant.now()
            return VoiceTutorWebRtcControlContext(VoiceTutorSession(
                id = "native-test-session", userId = 7, studyId = null, idempotencyKey = "native-test-call",
                providerSessionId = "rtc_native_test", status = VoiceTutorSessionStatus.ACTIVE,
                resultStatus = VoiceTutorResultStatus.PENDING, language = "ko", model = "gpt-realtime",
                voice = "marin", topic = "", difficulty = 5, periodStartedAt = now,
                periodEndsAt = now.plusSeconds(86_400), reservedSeconds = 3_600, chargedSeconds = 0,
                maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600), connectedAt = now,
                relayHeartbeatAt = now, acceptedAudioBytes = 0, endedAt = null, finalizedAt = null,
                endReason = null, failureCode = null, failureMessage = null, createdAt = now, updatedAt = now,
            ), "rtc_native_test")
        }
    }
}
