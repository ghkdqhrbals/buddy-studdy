package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract as Contract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata as Metadata
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class VoiceTutorNativeConversationControllerTest {
    private val mapper = JsonMapperProvider.mapper
    private var time = 0L
    private val controller = VoiceTutorNativeConversationController(Duration.ofSeconds(15), nanoTime = { time })
    private val outbound = mutableListOf<JsonNode>()
    private val ui = mutableListOf<JsonNode>()
    private val stored = mutableListOf<VoiceTutorNativeConversationController.NativeTranscript>()
    private val calls = mutableListOf<VoiceTutorMcpCall>()

    init {
        controller.providerEvents().subscribe { outbound.add(mapper.readTree(it)) }
        controller.clientEvents().subscribe { ui.add(mapper.readTree(it)) }
        controller.persistenceEvents().subscribe { stored += it }
        controller.toolActions().subscribe { calls += it }
    }

    @Test
    fun `ordinary response is created on audio commit without ASR classifier or persistence`() {
        opening()
        speech(1)
        committed("u1")
        assertThat(responses()).hasSize(2)
        assertThat(stored.map { it.itemId }).containsExactly("t0")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `learner barge in cancels generation then clears queued audio and answers only the newest turn`() {
        controller.start(); created("r0"); audio("r0", "t0")
        speech(1); committed("u1")
        assertThat(outbound.map { it.path("type").asText() })
            .containsSubsequence("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.commit")
        assertThat(ui.last().path("type").asText()).isEqualTo(Contract.RESPONSE_INTERRUPTED_EVENT)
        assertThat(ui.last().path("responseId").asText()).isEqualTo("r0")
        done("r0", "t0")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
        assertThat(stored).isEmpty()
        assertThat(ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
        created("r1"); toolDone("r1", "latest", "list_studies")
        assertThat(controller.toolBoundary("latest")?.latestAcceptedLearnerProviderItemId).isEqualTo("u1")
    }

    @Test
    fun `barge in clears fully generated speech that still waits in the output buffer`() {
        controller.start(); created("r0"); audio("r0", "t0"); done("r0", "t0")
        speech(1); committed("u1")
        assertThat(outbound.map { it.path("type").asText() }).contains("output_audio_buffer.clear").doesNotContain("response.cancel")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
        assertThat(stored).isEmpty()
    }

    @Test
    fun `speech before creation acknowledgement cancels the correlated response and ignores late output`() {
        controller.start()
        speech(1); committed("u1")
        assertThat(responses()).hasSize(1)
        created("r0")
        assertThat(outbound.last().path("type").asText()).isEqualTo("response.cancel")
        assertThat(outbound.last { it.path("type").asText() == "response.cancel" }.path("response_id").asText()).isEqualTo("r0")
        assertThat(ui.map { it.path("type").asText() }).containsExactly(Contract.RESPONSE_INTERRUPTED_EVENT)
        assertThat(event("response.output_audio_transcript.delta", "response_id" to "r0", "item_id" to "t0", "delta" to "stale")).isFalse()
        assertThat(event("response.output_audio.delta", "response_id" to "r0", "delta" to "stale")).isFalse()
        assertThat(outbound.last().path("type").asText()).isEqualTo("output_audio_buffer.clear")
        assertThat(event("output_audio_buffer.started", "response_id" to "r0")).isFalse()
        event("output_audio_buffer.cleared", "response_id" to "unrelated")
        done("r0", "t0")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
        created("r1")
        assertThat(event("output_audio_buffer.started", "response_id" to "r0")).isFalse()
        assertThat(event("response.output_audio_transcript.done", "response_id" to "r0", "item_id" to "t0", "transcript" to "stale")).isFalse()
        done("r0", "t0")
        assertThat(responses()).hasSize(2)
        assertThat(stored).isEmpty()
    }

    @Test
    fun `clear acknowledgement before cancellation completion never releases a duplicate response`() {
        controller.start(); created("r0"); audio("r0", "t0")
        speech(1); committed("u1")
        client(Contract.SPEECH_STARTED_EVENT, 1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(1)
        event("response.done", "response" to mapOf("id" to "r0", "status" to "cancelled", "output" to emptyList<Any>()))
        assertThat(responses()).hasSize(2)
        assertThat(outbound.count { it.path("type").asText() == "response.cancel" }).isEqualTo(1)
        assertThat(outbound.count { it.path("type").asText() == "output_audio_buffer.clear" }).isEqualTo(1)
        assertThat(ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
    }

    @Test
    fun `interrupted tool response cannot execute tools and retires without clearing earlier tutor audio`() {
        opening(); speech(1); committed("u1"); created("r1")
        client(Contract.SPEECH_STARTED_EVENT, 2)
        toolDone("r1", "stale", "prepare_voice_study_mutation")
        assertThat(calls).isEmpty()
        assertThat(responses()).hasSize(2)
        assertThat(outbound.map { it.path("type").asText() }).doesNotContain("output_audio_buffer.clear")
        assertThat(ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
        client(Contract.SPEECH_STOPPED_EVENT, 2); committed("u2")
        assertThat(responses()).hasSize(3)
    }

    @Test
    fun `audio in cancellation done still clears even when start and delta events have not arrived`() {
        controller.start(); created("r0")
        speech(1); committed("u1")
        assertThat(outbound.map { it.path("type").asText() }).doesNotContain("output_audio_buffer.clear")
        done("r0", "t0")
        assertThat(outbound.last().path("type").asText()).isEqualTo("output_audio_buffer.clear")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.stopped", "response_id" to "r0")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `cancel race errors and output stop cannot bypass the exact clear acknowledgement`() {
        controller.start(); created("r0"); audio("r0", "t0")
        event("output_audio_buffer.stopped", "response_id" to "r0")
        speech(1); committed("u1")
        val cancelId = outbound.last { it.path("type").asText() == "response.cancel" }.path("event_id").asText()
        assertThat(event("error", "error" to mapOf("type" to "invalid_request_error", "code" to "response_cancel_not_active",
            "event_id" to cancelId))).isFalse()
        done("r0", "t0")
        assertThat(responses()).hasSize(1)
        event("output_audio_buffer.cleared", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `missing interruption acknowledgement is bounded and never replays buffered speech`() {
        controller.start(); created("r0"); audio("r0", "t0")
        speech(1); committed("u1"); done("r0", "t0")
        var failure: Throwable? = null
        controller.failure().subscribe({}, { failure = it })
        time += Duration.ofSeconds(5).toNanos(); controller.tick()
        assertThat(failure).isInstanceOf(VoiceTutorProviderResponseTimeoutException::class.java)
        assertThat(responses()).hasSize(1)
        assertThat(ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
    }

    @Test
    fun `barge in suppresses pending tool continuation until latest learner commit`() {
        opening(); speech(1); committed("u1"); created("r1"); toolDone("r1", "call1", "list_studies")
        controller.beginTool("call1")
        client(Contract.SPEECH_STARTED_EVENT, 2)
        controller.completeTool("call1", VoiceTutorMcpToolResult("{\"studies\":[]}", false))
        ackToolOutput()
        assertThat(responses()).hasSize(2)
        client(Contract.SPEECH_STOPPED_EVENT, 2)
        val commitId = outbound.last().path("event_id").asText()
        event("error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to commitId))
        controller.tick()
        assertThat(responses()).hasSize(2)
        speech(3); committed("u3")
        assertThat(responses()).hasSize(3)
    }

    @Test
    fun `old commit acknowledgement during correction cannot queue a stale reply after empty final input`() {
        opening(); speech(1)
        client(Contract.SPEECH_STARTED_EVENT, 2)
        committed("u1"); transcript("u1", "old question")
        client(Contract.SPEECH_STOPPED_EVENT, 2)
        val commitId = outbound.last().path("event_id").asText()
        event("error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to commitId))
        controller.tick()
        assertThat(stored.map { it.itemId }).contains("u1")
        assertThat(responses()).hasSize(1)
        speech(3); committed("u3")
        assertThat(responses()).hasSize(2)
        created("r1"); toolDone("r1", "latest", "list_studies")
        assertThat(controller.toolBoundary("latest")?.latestAcceptedLearnerProviderItemId).isEqualTo("u3")
    }

    @Test
    fun `response waits for continuing learner even after previous commit ACK`() {
        opening(); speech(1)
        client(Contract.SPEECH_STARTED_EVENT, 2)
        committed("u1")
        assertThat(responses()).hasSize(1)
        client(Contract.SPEECH_STOPPED_EVENT, 2); committed("u2")
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `duplicate local boundaries and duplicate provider commit never duplicate input or response`() {
        opening(); speech(1); client(Contract.SPEECH_STOPPED_EVENT, 1)
        committed("u1"); committed("u1")
        assertThat(outbound.count { it.path("type").asText() == "input_audio_buffer.commit" }).isEqualTo(1)
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `early ASR is correlated after commit without dropping original transcript`() {
        opening(); speech(1)
        transcript("u1", "준비됐어")
        assertThat(stored.map { it.itemId }).doesNotContain("u1")
        committed("u1")
        assertThat(stored.map { it.itemId }).contains("u1")
        val raw = mapper.readTree(stored.last().raw)
        assertThat(raw.path(Metadata.POST_CALL_EVIDENCE).asBoolean()).isTrue()
        assertThat(raw.path(Metadata.IS_STUDY_QUESTION).asBoolean()).isFalse()
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `failed ASR still permits native audio response and releases transcript drain`() {
        opening(); speech(1)
        event("conversation.item.input_audio_transcription.failed", "item_id" to "u1")
        committed("u1")
        assertThat(responses()).hasSize(2)
        assertThat(stored.map { it.itemId }).doesNotContain("u1")
    }

    @Test
    fun `tutor transcript is eligible only after clean provider completion and playout`() {
        controller.start(); created("r0"); audio("r0", "t0")
        assertThat(stored).isEmpty()
        done("r0", "t0")
        assertThat(stored).isEmpty()
        event("output_audio_buffer.stopped", "response_id" to "r0")
        assertThat(stored.map { it.itemId }).containsExactly("t0")
    }

    @Test
    fun `stop before response done also completes exactly once`() {
        controller.start(); created("r0"); audio("r0", "t0")
        event("output_audio_buffer.stopped", "response_id" to "r0")
        assertThat(stored).isEmpty()
        done("r0", "t0"); done("r0", "t0")
        assertThat(stored).hasSize(1)
    }

    @Test
    fun `cleared audio is not a completed question and response retry does not end call`() {
        controller.start(); created("r0"); audio("r0", "t0")
        event("output_audio_buffer.cleared", "response_id" to "r0")
        done("r0", "t0")
        assertThat(stored).isEmpty()
        assertThat(responses()).hasSize(2)
        assertThat(ui.map { it.path("type").asText() }).contains(Contract.INPUT_RETRY_EVENT)
    }

    @Test
    fun `silent noise response does not wait for an audio stop that will never arrive`() {
        opening(); speech(1); committed("u1"); created("r1")
        event("response.done", "response" to mapOf("id" to "r1", "status" to "completed", "output" to emptyList<Any>()))
        speech(2); committed("u2")
        assertThat(responses()).hasSize(3)
    }

    @Test
    fun `text-only message cannot be counted as played audio or stall following input`() {
        opening(); speech(1); committed("u1"); created("r1")
        event("response.output_item.added", "response_id" to "r1", "item" to mapOf("id" to "text1", "type" to "message"))
        event("response.done", "response" to mapOf("id" to "r1", "status" to "completed", "output" to listOf(
            mapOf("id" to "text1", "type" to "message", "content" to listOf(mapOf("type" to "text", "text" to ""))))))
        speech(2); committed("u2")
        assertThat(responses()).hasSize(3)
        assertThat(stored.map { it.itemId }).doesNotContain("text1")
    }

    @Test
    fun `tool output must be acknowledged before one continuation`() {
        opening(); speech(1); committed("u1"); created("r1"); toolDone("r1", "call1", "list_studies")
        assertThat(calls).hasSize(1)
        assertThat(responses()).hasSize(2)
        assertThat(controller.beginTool("call1")).isTrue()
        controller.completeTool("call1", VoiceTutorMcpToolResult("{\"studies\":[]}", false))
        assertThat(responses()).hasSize(2)
        ackToolOutput()
        assertThat(responses()).hasSize(3)
        ackToolOutput()
        assertThat(responses()).hasSize(3)
    }

    @Test
    fun `confirmation authority freezes at speech start not later commit acknowledgement`() {
        controller.start(); created("r0"); audio("r0", "t0")
        client(Contract.SPEECH_STARTED_EVENT, 1)
        done("r0", "t0"); event("output_audio_buffer.stopped", "response_id" to "r0")
        event("output_audio_buffer.cleared", "response_id" to "r0")
        client(Contract.SPEECH_STOPPED_EVENT, 1); committed("u1"); created("r1")
        toolDone("r1", "call1", "confirm_voice_study_mutation")
        assertThat(controller.toolBoundary("call1")?.precedingTutorProviderItemId).isNull()
    }

    @Test
    fun `new learner speech invalidates an unexecuted mutation without interpreting words`() {
        opening(); speech(1); committed("u1"); created("r1"); toolDone("r1", "call1", "prepare_voice_study_mutation")
        assertThat(controller.toolCanExecute("call1")).isTrue()
        client(Contract.SPEECH_STARTED_EVENT, 2)
        assertThat(controller.toolCanExecute("call1")).isFalse()
    }

    @Test
    fun `empty interrupted input cannot revive an earlier unexecuted mutation`() {
        opening(); speech(1); committed("u1"); created("r1"); toolDone("r1", "call1", "prepare_voice_study_mutation")
        speech(2)
        val commitId = outbound.last().path("event_id").asText()
        event("error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to commitId))
        assertThat(controller.toolCanExecute("call1")).isFalse()
    }

    @Test
    fun `same learner can follow successful focus revision with another tool without reconsent`() {
        opening(); speech(1); committed("u1"); created("r1"); toolDone("r1", "call1", "select_voice_study")
        controller.beginTool("call1")
        controller.completeTool("call1", VoiceTutorMcpToolResult("{\"ok\":true}", false, lessonRevision = 1))
        ackToolOutput(); created("r2"); toolDone("r2", "call2", "get_study")
        assertThat(controller.toolCanExecute("call2")).isTrue()
        assertThat(controller.toolRevision("call2")).isEqualTo(1)
        assertThat(controller.toolBoundary("call2")?.latestAcceptedLearnerLessonRevision).isEqualTo(0)
    }

    @Test
    fun `quota notice waits for the current sentence and exact native device playout receipt`() {
        controller.start(); created("r0"); audio("r0", "t0")
        var finished = false
        controller.quotaCompletion().subscribe({}, { throw it }, { finished = true })
        controller.requestQuotaNotice()
        speech(1)
        assertThat(responses()).hasSize(1)
        assertThat(outbound.map { it.path("type").asText() }).doesNotContain("response.cancel", "output_audio_buffer.clear")
        done("r0", "t0"); event("output_audio_buffer.stopped", "response_id" to "r0")
        assertThat(responses()).hasSize(2)
        assertThat(responses().last().path("response").path("tool_choice").asText()).isEqualTo("none")
        created("r1"); audio("r1", "t1"); done("r1", "t1")
        event("output_audio_buffer.stopped", "response_id" to "r1")
        assertThat(finished).isFalse()
        controller.observeClientEvent(mapper.writeValueAsString(mapOf("type" to Contract.PLAYOUT_DRAINED_EVENT, "responseId" to "stale")))
        assertThat(finished).isFalse()
        controller.observeClientEvent(mapper.writeValueAsString(mapOf("type" to Contract.PLAYOUT_DRAINED_EVENT, "responseId" to "r1")))
        assertThat(finished).isTrue()
    }

    @Test
    fun `pause drains tutor then clears only quiesced input and preserves pending learner response for resume`() {
        opening(); speech(1); committed("u1"); created("r1"); audio("r1", "t1")
        client(Contract.PAUSE_REQUEST_EVENT, 1); client(Contract.PAUSE_INPUT_QUIESCED_EVENT, 1)
        assertThat(outbound.map { it.path("type").asText() }).doesNotContain("input_audio_buffer.clear")
        done("r1", "t1"); event("output_audio_buffer.stopped", "response_id" to "r1")
        assertThat(outbound.last().path("type").asText()).isEqualTo("input_audio_buffer.clear")
        event("input_audio_buffer.cleared", "event_id" to "clear1")
        assertThat(ui.last().path("paused").asBoolean()).isTrue()
        client(Contract.RESUME_REQUEST_EVENT, 2); event("input_audio_buffer.cleared", "event_id" to "clear2")
        assertThat(ui.last().path("paused").asBoolean()).isFalse()
        speech(2); committed("u2")
        assertThat(responses()).hasSize(3)
    }

    @Test
    fun `commit acknowledged after pause quiescence is preserved and answered after resume clear`() {
        opening(); speech(1)
        client(Contract.PAUSE_REQUEST_EVENT, 1)
        client(Contract.PAUSE_INPUT_QUIESCED_EVENT, 1)
        assertThat(outbound.map { it.path("type").asText() }).doesNotContain("input_audio_buffer.clear")

        committed("u1")
        controller.tick()
        assertThat(responses()).hasSize(1)
        assertThat(outbound.last().path("type").asText()).isEqualTo("input_audio_buffer.clear")
        event("input_audio_buffer.cleared", "event_id" to "pause-clear")
        assertThat(ui.last().path("paused").asBoolean()).isTrue()

        client(Contract.RESUME_REQUEST_EVENT, 2)
        assertThat(responses()).hasSize(1)
        event("input_audio_buffer.cleared", "event_id" to "resume-clear")
        assertThat(ui.last().path("paused").asBoolean()).isFalse()
        assertThat(responses()).hasSize(2)

        // No new utterance, ASR or database completion is needed to release it.
        event("input_audio_buffer.cleared", "event_id" to "resume-clear")
        controller.tick()
        assertThat(responses()).hasSize(2)
        created("r1"); toolDone("r1", "read-after-resume", "list_studies")
        assertThat(controller.toolBoundary("read-after-resume")?.latestAcceptedLearnerProviderItemId).isEqualTo("u1")
    }

    @Test
    fun `long learner answer checkpoints context without taking the floor and spaces the short final tail`() {
        opening(); client(Contract.SPEECH_STARTED_EVENT, 1)
        time += Duration.ofSeconds(20).toNanos(); controller.tick(); committed("u1")
        assertThat(responses()).hasSize(1)
        client(Contract.SPEECH_STOPPED_EVENT, 1)
        assertThat(outbound.count { it.path("type").asText() == "input_audio_buffer.commit" }).isEqualTo(1)
        time += Duration.ofMillis(250).toNanos(); controller.tick(); committed("u2")
        assertThat(responses()).hasSize(2)
    }

    @Test
    fun `silent quota response is retried only once`() {
        opening(); controller.requestQuotaNotice(); created("r1")
        event("response.done", "response" to mapOf("id" to "r1", "status" to "completed", "output" to emptyList<Any>()))
        assertThat(responses()).hasSize(3)
        var error: Throwable? = null
        controller.failure().subscribe({}, { error = it })
        created("r2")
        event("response.done", "response" to mapOf("id" to "r2", "status" to "completed", "output" to emptyList<Any>()))
        assertThat(responses()).hasSize(3)
        assertThat(error).isInstanceOf(VoiceTutorQuotaExhaustionNoticeInterruptedException::class.java)
    }

    private fun responses() = outbound.filter { it.path("type").asText() == "response.create" }
    private fun opening() { controller.start(); created("r0"); audio("r0", "t0"); done("r0", "t0"); event("output_audio_buffer.stopped", "response_id" to "r0") }
    private fun client(type: String, seq: Long) {
        if (type == Contract.SPEECH_STARTED_EVENT) time += Duration.ofSeconds(1).toNanos()
        controller.observeClientEvent(mapper.writeValueAsString(mapOf("type" to type, "sequence" to seq)))
    }
    private fun speech(seq: Long) { client(Contract.SPEECH_STARTED_EVENT, seq); client(Contract.SPEECH_STOPPED_EVENT, seq) }
    private fun committed(id: String) = event("input_audio_buffer.committed", "item_id" to id)
    private fun transcript(id: String, text: String) = event("conversation.item.input_audio_transcription.completed", "item_id" to id, "transcript" to text)
    private fun created(id: String) = event("response.created", "response" to mapOf("id" to id, "metadata" to responses().last().path("response").path("metadata")))
    private fun audio(r: String, t: String) {
        event("response.output_item.added", "response_id" to r, "item" to mapOf("id" to t, "type" to "message"))
        event("output_audio_buffer.started", "response_id" to r)
        event("response.output_audio_transcript.done", "response_id" to r, "item_id" to t, "transcript" to "어떤 주제로 이야기할까요?")
    }
    private fun done(r: String, t: String) = event("response.done", "response" to mapOf("id" to r, "status" to "completed", "output" to listOf(
        mapOf("id" to t, "type" to "message", "content" to listOf(mapOf("type" to "audio", "transcript" to "어떤 주제로 이야기할까요?"))))))
    private fun toolDone(r: String, id: String, name: String) = event("response.done", "response" to mapOf("id" to r, "status" to "completed", "output" to listOf(
        mapOf("id" to "item-$id", "type" to "function_call", "status" to "completed", "call_id" to id, "name" to name, "arguments" to "{}"))))
    private fun ackToolOutput() {
        val output = outbound.last { it.path("type").asText() == "conversation.item.create" }
        event("conversation.item.created", "item" to output.path("item"))
    }
    private fun event(type: String, vararg values: Pair<String, Any>): Boolean = controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type) + values))
}
