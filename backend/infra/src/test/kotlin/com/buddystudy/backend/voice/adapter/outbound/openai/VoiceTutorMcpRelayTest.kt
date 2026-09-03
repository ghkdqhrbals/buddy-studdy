package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Synthetic MCP/provider events only. No network, account mutation, audio or provider spend. */
class VoiceTutorMcpRelayTest {
    @Test
    fun `spoken focus selection emits the server confirmed compact header without a tree mutation or audio cancellation`() = Fixture(captureCalls = false).use { f ->
        val arrived = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val selection = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(202, 2), VoiceTutorStudySnapshot(202, 201, "Cache", 3, 2))
        val tools = port { _, _ -> success().copy(lessonFocus = selection, lessonRevision = 2) }
        val worker = voiceTutorMcpToolRelay(f.controller, context(), tools, { raw, persist, forward ->
            assertThat(persist).isFalse()
            assertThat(forward).isTrue()
            events += raw
            arrived.countDown()
        }).subscribe({}, f.errors::add)
        try {
            f.controller.observeProviderEvent(f.done(listOf(call("choose-topic", "select_voice_study"))))
            assertThat(arrived.await(3, TimeUnit.SECONDS)).isTrue()
            val event = mapper.readTree(events.single())
            assertThat(event.path("type").asText()).isEqualTo(VoiceTutorRealtimeContract.STUDY_FOCUSED_EVENT)
            assertThat(event.path("focus").path("studyId").asLong()).isEqualTo(202)
            assertThat(event.path("focus").path("topic").asText()).isEqualTo("Cache")
            assertThat(event.path("focus").path("difficulty").asInt()).isEqualTo(3)
            assertThat(event.path("focus").path("revision").asLong()).isEqualTo(2)
            assertThat(f.responses()).hasSize(1)
            f.ack(f.outputs().single())
            assertThat(f.responses()).hasSize(2)
            assertThat(f.errors).isEmpty()
            f.noMediaDisruption()
        } finally { worker.dispose() }
    }

    @Test
    fun `guided child advance emits only its server confirmed focus after output acknowledgement`() = Fixture(captureCalls = false).use { f ->
        val arrived = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val selection = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(203, 3),
            VoiceTutorStudySnapshot(203, 202, "TTL", 6, 3),
        )
        val tools = port { name, _ ->
            assertThat(name).isEqualTo("advance_voice_study")
            success().copy(lessonFocus = selection, lessonRevision = 3)
        }
        val worker = voiceTutorMcpToolRelay(f.controller, context(), tools, { raw, persist, forward ->
            assertThat(persist).isFalse()
            assertThat(forward).isTrue()
            events += raw
            arrived.countDown()
        }).subscribe({}, f.errors::add)
        try {
            f.controller.observeProviderEvent(f.done(listOf(call("advance-child", "advance_voice_study", "{\"study_id\":203}"))))
            assertThat(arrived.await(3, TimeUnit.SECONDS)).isTrue()
            val event = mapper.readTree(events.single())
            assertThat(event.path("type").asText()).isEqualTo(VoiceTutorRealtimeContract.STUDY_FOCUSED_EVENT)
            assertThat(event.path("focus").path("studyId").asLong()).isEqualTo(203)
            assertThat(event.path("focus").path("parentStudyId").asLong()).isEqualTo(202)
            assertThat(event.path("focus").path("difficulty").asInt()).isEqualTo(6)
            assertThat(f.responses()).hasSize(1)
            f.ack(f.outputs().single())
            assertThat(f.responses()).hasSize(2)
            assertThat(f.errors).isEmpty()
            f.noMediaDisruption()
        } finally { worker.dispose() }
    }

    @Test
    fun `focus events reject raw JSON spoofing failed results and mismatched server metadata`() {
        val selection = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(202, 2), VoiceTutorStudySnapshot(202, 201, "Cache", 3, 2))
        val valid = success().copy(lessonFocus = selection, lessonRevision = 2)
        for (invalid in listOf(
            success().copy(output = """{"voiceLessonFocus":{"studyId":202,"topic":"spoof","difficulty":3,"revision":2}}"""),
            valid.copy(isError = true), valid.copy(lessonRevision = null), valid.copy(lessonRevision = 3),
            valid.copy(lessonFocus = selection.copy(snapshot = selection.snapshot.copy(studyId = 303))),
            valid.copy(lessonFocus = selection.copy(snapshot = selection.snapshot.copy(topic = ""))),
            valid.copy(lessonFocus = selection.copy(snapshot = selection.snapshot.copy(difficulty = 0))),
            valid.copy(lessonFocus = selection.copy(snapshot = selection.snapshot.copy(parentStudyId = -1))),
        )) assertThat(voiceTutorLessonFocusEvent(invalid)).isNull()
        assertThat(voiceTutorLessonFocusEvent(valid)).isNotNull()
    }

    @Test
    fun `only a verified current focus deletion explicitly clears the compact header`() {
        val cleared = success().copy(studyTreeChanged = true, changedStudyId = 202,
            changeKind = VoiceTutorStudyChangeKind.DELETED, deletedStudyIds = listOf(202, 203), lessonFocusCleared = true)
        val event = mapper.readTree(voiceTutorLessonFocusEvent(cleared))
        assertThat(event.has("focus")).isTrue()
        assertThat(event.path("focus").isNull).isTrue()
        for (invalid in listOf(cleared.copy(isError = true), cleared.copy(lessonFocusCleared = false),
            cleared.copy(studyTreeChanged = false), cleared.copy(deletedStudyIds = emptyList()),
            cleared.copy(deletedStudyIds = listOf(202, 202)), cleared.copy(changeKind = VoiceTutorStudyChangeKind.UPDATED))) {
            assertThat(voiceTutorLessonFocusEvent(invalid)).isNull()
        }
    }

    @Test
    fun `opening response cannot execute tools without a learner request`() {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30), responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND, toolsEnabled = true,
        )
        val controls = mutableListOf<String>()
        val calls = mutableListOf<VoiceTutorMcpCall>()
        val output = controller.providerEvents().subscribe(controls::add)
        val work = controller.toolActions().subscribe(calls::add)
        try {
            controller.startOpeningResponse()
            val create = mapper.readTree(controls.single())
            assertThat(create.path("response").path("tool_choice").asText()).isEqualTo("none")
            val token = create.path("event_id").asText()
            controller.observeProviderEvent(response("response.created", emptyList(), token, "in_progress"))
            assertThatThrownBy {
                controller.observeProviderEvent(response("response.done", listOf(call("unsolicited-write", "create_study_topic")), token, "completed"))
            }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
            assertThat(calls).isEmpty()
        } finally { controller.close(); work.dispose(); output.dispose() }
    }

    @Test
    fun `legacy response has a valid tool choice rather than null`() {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30), responseTimeout = Duration.ofSeconds(60),
        )
        val controls = mutableListOf<String>()
        val output = controller.providerEvents().subscribe(controls::add)
        try {
            controller.startOpeningResponse()
            val token = mapper.readTree(controls.single()).path("event_id").asText()
            controller.observeProviderEvent(response("response.created", emptyList(), token, "in_progress"))
            controller.observeProviderEvent(response("response.done", emptyList(), token, "completed"))
            controller.observeProviderEvent("""{"type":"input_audio_buffer.committed","item_id":"learner"}""")
            val choices = controls.map(mapper::readTree).filter { it.path("type").asText() == "response.create" }
            assertThat(choices).hasSize(2)
            assertThat(choices.last().path("response").path("tool_choice").asText()).isEqualTo("none")
        } finally { controller.close(); output.dispose() }
    }

    @Test
    fun `completed function-only response resumes speech after exact output ACK without an audio stop`() = Fixture().use { f ->
        val done = f.done(listOf(call("read-1")))
        f.controller.observeProviderEvent(done)
        assertThat(f.calls).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        assertThat(f.controller.beginToolExecution("read-1")).isTrue()
        assertThat(f.controller.completeToolExecution("read-1", success())).isTrue()
        assertThat(f.outputs().single().path("item").path("id").asText()).hasSizeLessThanOrEqualTo(32)
        assertThat(f.responses()).hasSize(1)
        f.ack(f.outputs().single(), itemId = "not-this-output")
        assertThat(f.responses()).hasSize(1)
        f.ack(f.outputs().single())
        assertThat(f.responses()).hasSize(2)
        f.ack(f.outputs().single())
        f.controller.observeProviderEvent(done)
        assertThat(f.responses()).hasSize(2)
        assertThat(f.calls).hasSize(1)
        f.noMediaDisruption()
    }

    @Test
    fun `argument streaming and stale or incomplete responses cannot execute writes`() = Fixture().use { f ->
        f.event("response.function_call_arguments.done", "call_id" to "write-1", "name" to "create_study_topic", "arguments" to "{}")
        f.controller.observeProviderEvent(f.done(listOf(call("write-1")), token = "old-token"))
        assertThat(f.calls).isEmpty()
        assertThat(f.controller.observeProviderEvent(f.done(listOf(call("write-1")), status = "cancelled")))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.calls).isEmpty()
        assertThat(f.responses()).hasSize(2)
    }

    @Test
    fun `late clear after an acknowledged tool never replays the accepted call`() = Fixture().use { f ->
        f.event("output_audio_buffer.started", "response_id" to "response-1")
        f.controller.observeProviderEvent(f.done(listOf(spokenItem(), call("write-1", "create_study_topic"))))
        assertThat(f.calls.map { it.callId }).containsExactly("write-1")
        f.completeAll()
        f.ack(f.outputs().single())
        assertThat(f.responses()).hasSize(1)

        assertThat(f.event("output_audio_buffer.cleared", "response_id" to "response-1"))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)

        assertThat(f.calls.map { it.callId }).containsExactly("write-1")
        assertThat(f.responses()).hasSize(2)
        assertThat(f.clientControls).isEmpty()
        f.noMediaDisruption()
    }

    @Test
    fun `tool plus speech waits for the actual audio tail even after tool ACK`() = Fixture().use { f ->
        f.event("output_audio_buffer.started", "response_id" to "response-1")
        f.controller.observeProviderEvent(f.done(listOf(spokenItem(), call("read-1"))))
        f.completeAll()
        f.ack(f.outputs().single())
        assertThat(f.responses()).hasSize(1)
        f.event("output_audio_buffer.stopped", "response_id" to "old-response")
        assertThat(f.responses()).hasSize(1)
        f.event("output_audio_buffer.stopped", "response_id" to "response-1")
        assertThat(f.responses()).hasSize(2)
        f.noMediaDisruption()
    }

    @Test
    fun `tool ACK waits for the learner speech commit before continuing`() = Fixture().use { f ->
        f.controller.observeProviderEvent(f.done(listOf(call("read-1"))))
        f.speech(true, 2)
        f.completeAll()
        f.ack(f.outputs().single())
        assertThat(f.responses()).hasSize(1)
        f.speech(false, 2)
        assertThat(f.responses()).hasSize(1)
        f.event("input_audio_buffer.committed", "item_id" to "learner-1")
        assertThat(f.responses()).hasSize(2)
        f.noMediaDisruption()
    }

    @Test
    fun `all tool results must be acknowledged and duplicate IDs are registered before execution`() = Fixture().use { f ->
        val done = f.done(listOf(call("read-1"), call("read-2")))
        f.controller.observeProviderEvent(done)
        f.controller.observeProviderEvent(done)
        assertThat(f.calls).hasSize(2)
        f.completeAll()
        assertThat(f.controller.beginToolExecution("read-1")).isFalse()
        assertThat(f.controller.completeToolExecution("read-1", success())).isFalse()
        f.ack(f.outputs().last(), type = "conversation.item.added")
        assertThat(f.responses()).hasSize(1)
        f.ack(f.outputs().first(), type = "conversation.item.done")
        assertThat(f.responses()).hasSize(2)
        f.noMediaDisruption()
    }

    @Test
    fun `output ACK has a finite timeout without cancelling audio`() = Fixture().use { f ->
        f.controller.observeProviderEvent(f.done(listOf(call("read-1"))))
        f.completeAll()
        f.now.addAndGet(Duration.ofSeconds(16).toNanos())
        f.controller.expireToolAcknowledgements()
        assertThat(f.errors.single()).isInstanceOf(VoiceTutorMcpOutputAcknowledgementException::class.java)
        assertThat(f.controller.acceptsInputEvents()).isFalse()
        f.noMediaDisruption()
    }

    @Test
    fun `close invalidates queued work and ignores late success`() = Fixture().use { f ->
        f.controller.observeProviderEvent(f.done(listOf(call("read-1"), call("write-2"))))
        assertThat(f.controller.beginToolExecution("read-1")).isTrue()
        f.controller.close()
        assertThat(f.controller.completeToolExecution("read-1", success())).isFalse()
        assertThat(f.controller.beginToolExecution("write-2")).isFalse()
        assertThat(f.outputs()).isEmpty()
    }

    @Test
    fun `malformed arguments become structured tool errors without calling MCP`(): Unit = Fixture(captureCalls = false).use { f ->
        val invoked = CopyOnWriteArrayList<String>()
        val tools = port { name, _ -> invoked += name; success() }
        val worker = voiceTutorMcpToolRelay(f.controller, context(), tools, { _, _, _ -> }).subscribe({}, f.errors::add)
        try {
            f.controller.observeProviderEvent(f.done(listOf(call("read-1", arguments = "not-json"))))
            assertThat(f.outputArrived.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(invoked).isEmpty()
            val output = mapper.readTree(f.outputs().single().path("item").path("output").asText())
            assertThat(output.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS")
            f.ack(f.outputs().single())
            assertThat(f.responses()).hasSize(2)
        } finally { worker.dispose() }
    }

    @Test
    fun `slow MCP runs serially outside provider receive and successful child emits metadata only`() = Fixture(captureCalls = false).use { f ->
        val firstStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstResult = CompletableDeferred<VoiceTutorMcpToolResult>()
        val invoked = CopyOnWriteArrayList<String>()
        val clientEvents = CopyOnWriteArrayList<String>()
        val tools = port { name, _ ->
            invoked += name
            if (name == "get_study") {
                firstStarted.countDown()
                firstResult.await()
            } else {
                success().copy(studyTreeChanged = true, createdStudyId = 42)
            }
        }
        val worker = voiceTutorMcpToolRelay(f.controller, context(), tools, { raw, persist, forward ->
            assertThat(persist).isFalse()
            assertThat(forward).isTrue()
            clientEvents += raw
            secondFinished.countDown()
        }).subscribe({}, f.errors::add)
        try {
            f.event("output_audio_buffer.started", "response_id" to "response-1")
            f.controller.observeProviderEvent(f.done(listOf(spokenItem(), call("read-1"), call("write-2", "create_study_topic"))))
            assertThat(firstStarted.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(invoked).containsExactly("get_study")
            // This is immediately observable even while a database/MCP call waits.
            f.event("output_audio_buffer.stopped", "response_id" to "response-1")
            assertThat(f.responses()).hasSize(1)
            firstResult.complete(success())
            assertThat(secondFinished.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(invoked).containsExactly("get_study", "create_study_topic")
            assertThat(mapper.readTree(clientEvents.single()).fieldNames().asSequence().toSet())
                .containsExactlyInAnyOrder("type", "studyId", "change", "deletedStudyIds")
            assertThat(mapper.readTree(clientEvents.single()).path("studyId").asLong()).isEqualTo(42)
            assertThat(mapper.readTree(clientEvents.single()).path("change").asText()).isEqualTo("created")
            assertThat(mapper.readTree(clientEvents.single()).path("deletedStudyIds")).isEmpty()
            f.outputs().forEach { f.ack(it) }
            assertThat(f.responses()).hasSize(2)
            f.noMediaDisruption()
        } finally { worker.dispose() }
    }

    @Test
    fun `MCP timeout returns an uncertain-write error and still resumes after ACK`() = Fixture(captureCalls = false).use { f ->
        val tools = port { _, _ -> CompletableDeferred<VoiceTutorMcpToolResult>().await() }
        val worker = voiceTutorMcpToolRelay(f.controller, context(), tools, { _, _, _ -> }, executionTimeoutMillis = 20)
            .subscribe({}, f.errors::add)
        try {
            f.controller.observeProviderEvent(f.done(listOf(call("write-1", "create_study_topic"))))
            assertThat(f.outputArrived.await(3, TimeUnit.SECONDS)).isTrue()
            val output = mapper.readTree(f.outputs().single().path("item").path("output").asText())
            assertThat(output.path("error").path("code").asText()).isEqualTo("TOOL_TIMEOUT")
            assertThat(output.path("error").path("message").asText()).contains("not confirmed")
            f.ack(f.outputs().single())
            assertThat(f.responses()).hasSize(2)
            assertThat(f.errors).isEmpty()
        } finally { worker.dispose() }
    }

    @Test
    fun `tool rounds are bounded and later continuations disable tools`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        repeat(VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS) { index ->
            val id = "call-$index"
            coordinator.completedResponse(mapper.valueToTree(mapOf("output" to listOf(call(id)))))
            assertThat(coordinator.beginExecution(id)).isTrue()
            val output = coordinator.complete(id, success(), 0)!!
            assertThat(coordinator.acknowledge(mapper.valueToTree(mapOf("type" to "conversation.item.created", "item" to output["item"])), 1)).isTrue()
            coordinator.consumeContinuation()
        }
        assertThat(coordinator.toolChoice).isEqualTo("none")
        assertThatThrownBy { coordinator.completedResponse(mapper.valueToTree(mapOf("output" to listOf(call("extra"))))) }
            .isInstanceOf(VoiceTutorMcpProtocolException::class.java)
        coordinator.beginLearnerTurn()
        assertThat(coordinator.toolChoice).isEqualTo("auto")
        assertThatThrownBy { coordinator.completedResponse(mapper.valueToTree(mapOf("output" to listOf(call("call-0"))))) }
            .isInstanceOf(VoiceTutorMcpProtocolException::class.java)
        coordinator.close()
    }

    @Test
    fun `function output acknowledgement must echo the exact frozen output`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        coordinator.completedResponse(mapper.valueToTree(mapOf("output" to listOf(call("exact-output")))))
        assertThat(coordinator.beginExecution("exact-output")).isTrue()
        val output = coordinator.complete("exact-output", success(), 0)!!
        val acknowledgement = mapper.valueToTree<JsonNode>(mapOf(
            "type" to "conversation.item.created",
            "item" to output.getValue("item"),
        ))
        val forged = acknowledgement.deepCopy<JsonNode>()
        (forged.path("item") as ObjectNode).put("output", "{}")

        assertThat(coordinator.acknowledge(forged, 1)).isFalse()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.acknowledge(acknowledgement, 2)).isTrue()
        assertThat(coordinator.continuationReady).isTrue()
        coordinator.close()
    }

    @Test
    fun `server-owned call id fits provider limit and completes only after both acknowledgements`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val scheduled = coordinator.scheduleServerCall(
            name = "create_root_study",
            arguments = mapOf("topic" to "Spring", "difficulty_level" to 7),
            nowNanos = 0,
        )
        val callItem = mapper.valueToTree<JsonNode>(scheduled.providerEvent).path("item")

        assertThat(scheduled.callId.length)
            .isLessThanOrEqualTo(VoiceTutorMcpTurnCoordinator.MAX_PROVIDER_CALL_ID_LENGTH)
        assertThat(callItem.path("call_id").asText()).isEqualTo(scheduled.callId)

        val released = coordinator.acknowledgeServerCall(mapper.valueToTree(mapOf(
            "type" to "conversation.item.created",
            "item" to callItem,
        )), 1)
        assertThat(released).isEqualTo(VoiceTutorMcpCall(
            scheduled.callId,
            "create_root_study",
            mapOf("topic" to "Spring", "difficulty_level" to 7),
        ))
        assertThat(coordinator.beginExecution(scheduled.callId)).isTrue()

        val output = coordinator.complete(scheduled.callId, success(), 2)!!
        assertThat((output.getValue("item") as Map<*, *>)["call_id"]).isEqualTo(scheduled.callId)
        assertThat(coordinator.acknowledge(mapper.valueToTree(mapOf(
            "type" to "conversation.item.created",
            "item" to output.getValue("item"),
        )), 3)).isTrue()
        assertThat(coordinator.continuationReady).isTrue()
        coordinator.close()
    }

    @Test
    fun `exact rejected server item is tombstoned once and a late acknowledgement never executes it`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val scheduled = coordinator.scheduleServerCall(
            name = "create_root_study",
            arguments = mapOf("topic" to "Spring", "difficulty_level" to 7),
            nowNanos = 0,
        )
        val eventId = scheduled.providerEvent.getValue("event_id") as String
        val callItem = mapper.valueToTree<JsonNode>(scheduled.providerEvent).path("item")

        assertThat(coordinator.tombstoneRejectedServerCall("different-event-id")).isNull()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.beginExecution(scheduled.callId)).isFalse()
        assertThat(coordinator.tombstoneRejectedServerCall(eventId)).isEqualTo(
            VoiceTutorRejectedServerCall(scheduled.callId, "create_root_study", newlyTombstoned = true),
        )
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.continuationReady).isFalse()
        assertThat(coordinator.tombstoneRejectedServerCall(eventId)).isEqualTo(
            VoiceTutorRejectedServerCall(scheduled.callId, "create_root_study", newlyTombstoned = false),
        )

        val lateAcknowledgement = mapper.valueToTree<JsonNode>(mapOf(
            "type" to "conversation.item.created",
            "item" to callItem,
        ))
        assertThat(coordinator.acknowledgeServerCall(lateAcknowledgement, 1)).isNull()
        assertThat(coordinator.acknowledgeServerCall(lateAcknowledgement, 2)).isNull()
        assertThat(coordinator.beginExecution(scheduled.callId)).isFalse()
        coordinator.expire(Long.MAX_VALUE)
        assertThatThrownBy {
            coordinator.completedResponse(mapper.valueToTree(mapOf(
                "output" to listOf(call(scheduled.callId, "create_root_study")),
            )))
        }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
        coordinator.close()
    }

    @Test
    fun `server item rejection cannot tombstone a call after its exact acknowledgement released it`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val scheduled = coordinator.scheduleServerCall(
            name = "get_study",
            arguments = mapOf("study_id" to 42),
            nowNanos = 0,
        )
        val eventId = scheduled.providerEvent.getValue("event_id") as String
        val callItem = mapper.valueToTree<JsonNode>(scheduled.providerEvent).path("item")
        val acknowledgement = mapper.valueToTree<JsonNode>(mapOf(
            "type" to "conversation.item.created",
            "item" to callItem,
        ))

        assertThat(coordinator.acknowledgeServerCall(acknowledgement, 1)?.callId)
            .isEqualTo(scheduled.callId)
        assertThat(coordinator.tombstoneRejectedServerCall(eventId)).isNull()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.beginExecution(scheduled.callId)).isTrue()
        assertThat(coordinator.tombstoneRejectedServerCall(eventId)).isNull()

        val output = coordinator.complete(scheduled.callId, success(), 2)!!
        assertThat(coordinator.acknowledge(mapper.valueToTree(mapOf(
            "type" to "conversation.item.created",
            "item" to output.getValue("item"),
        )), 3)).isTrue()
        coordinator.close()
    }

    @Test
    fun `too many duplicate and malformed calls fail before any work is queued`() {
        for (items in listOf(List(9) { call("call-$it") }, listOf(call("same"), call("same")), listOf(call("bad id")))) {
            Fixture().use { f ->
                assertThatThrownBy { f.controller.observeProviderEvent(f.done(items)) }
                    .isInstanceOf(VoiceTutorMcpProtocolException::class.java)
                assertThat(f.calls).isEmpty()
            }
        }
    }

    private class Fixture(captureCalls: Boolean = true) : AutoCloseable {
        val now = AtomicLong(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val controls = CopyOnWriteArrayList<String>()
        val clientControls = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<VoiceTutorMcpCall>()
        val outputArrived = CountDownLatch(1)
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30), responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND, toolsEnabled = true,
        )
        private val controlSubscription = controller.providerEvents().subscribe({ raw ->
            controls += raw
            if (mapper.readTree(raw).path("type").asText() == "conversation.item.create") outputArrived.countDown()
        }, errors::add)
        private val workSubscription = if (captureCalls) controller.toolActions().subscribe(calls::add, errors::add) else null
        private val clientSubscription = controller.clientEvents().subscribe(clientControls::add, errors::add)
        private val token: String
        init {
            controller.startOpeningResponse()
            val openingToken = responses().single().path("event_id").asText()
            controller.observeProviderEvent(response("response.created", emptyList(), openingToken, "in_progress", id = "opening"))
            controller.observeProviderEvent(response("response.done", listOf(spokenItem()), openingToken, "completed", id = "opening"))
            event("output_audio_buffer.stopped", "response_id" to "opening")
            controls.clear()
            speech(true, 1)
            speech(false, 1)
            event("input_audio_buffer.committed", "item_id" to "initial-request")
            token = responses().single().path("event_id").asText()
            controller.observeProviderEvent(response("response.created", emptyList(), token, "in_progress"))
        }
        fun responses() = controls.map(mapper::readTree).filter { it.path("type").asText() == "response.create" }
        fun outputs() = controls.map(mapper::readTree).filter { it.path("type").asText() == "conversation.item.create" }
        fun done(items: List<Map<String, Any>>, token: String = this.token, status: String = "completed") =
            response("response.done", items, token, status)
        fun event(type: String, vararg fields: Pair<String, Any>) =
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, *fields)))
        fun speech(start: Boolean, sequence: Long) = controller.observeClientEvent(mapper.writeValueAsString(mapOf(
            "type" to if (start) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            "sequence" to sequence,
        )))
        fun completeAll() = calls.forEach { call ->
            assertThat(controller.beginToolExecution(call.callId)).isTrue()
            assertThat(controller.completeToolExecution(call.callId, success())).isTrue()
        }
        fun ack(output: JsonNode, itemId: String = output.path("item").path("id").asText(), type: String = "conversation.item.created") {
            val item = output.path("item").deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            item.put("id", itemId)
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, "item" to item)))
        }
        fun noMediaDisruption() {
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .doesNotContain("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.clear", "conversation.item.truncate")
        }
        override fun close() {
            controller.close()
            clientSubscription.dispose()
            workSubscription?.dispose()
            controlSubscription.dispose()
        }
    }

    private companion object {
        val mapper = JsonMapperProvider.mapper
        fun call(id: String, name: String = "get_study", arguments: String = "{\"study_id\":101}") = mapOf<String, Any>(
            "type" to "function_call", "status" to "completed", "call_id" to id, "name" to name, "arguments" to arguments,
        )
        fun spokenItem() = mapOf<String, Any>("type" to "message", "role" to "assistant", "content" to listOf(mapOf("type" to "output_audio", "transcript" to "확인할게요.")))
        fun success() = VoiceTutorMcpToolResult("{\"id\":42,\"topic\":\"synthetic\"}", isError = false)
        fun response(type: String, items: List<Map<String, Any>>, token: String, status: String, id: String = "response-1") = mapper.writeValueAsString(mapOf(
            "type" to type, "response" to mapOf("id" to id, "status" to status, "output" to items,
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token)),
        ))
        fun port(execute: suspend (String, Map<String, Any>) -> VoiceTutorMcpToolResult) = object : VoiceTutorMcpToolPort {
            override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()
            override suspend fun execute(context: VoiceTutorWebRtcControlContext, toolName: String, arguments: Map<String, Any>) = execute(toolName, arguments)
        }
        fun context(): VoiceTutorWebRtcControlContext {
            val now = Instant.parse("2026-08-31T00:00:00Z")
            return VoiceTutorWebRtcControlContext(VoiceTutorSession(
                id = "synthetic-session", userId = 7, studyId = 101, idempotencyKey = "synthetic-call",
                providerSessionId = "rtc_synthetic", status = VoiceTutorSessionStatus.ACTIVE, resultStatus = VoiceTutorResultStatus.PENDING,
                language = "ko", model = "gpt-realtime", voice = "marin", topic = "Redis", difficulty = 5,
                periodStartedAt = now, periodEndsAt = now.plusSeconds(86_400), reservedSeconds = 3_600,
                chargedSeconds = 0, maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600),
                connectedAt = now, relayHeartbeatAt = now, acceptedAudioBytes = 0, endedAt = null, finalizedAt = null,
                endReason = null, failureCode = null, failureMessage = null, createdAt = now, updatedAt = now,
            ), "rtc_synthetic")
        }
    }
}
