package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

internal class VoiceTutorMcpTurnCoordinatorTest {
    private val mapper = JsonMapperProvider.mapper

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only acknowledged typed curriculum GUI completion resets exhausted human tool rounds`(guiCompleted: Boolean) {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        repeat(VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS) { index ->
            val id = "curriculum-$index"
            coordinator.completedResponse(response(function(id)))
            assertThat(coordinator.beginExecution(id)).isTrue()
            val output = requireNotNull(coordinator.complete(id,
                VoiceTutorMcpToolResult("{\"userInputCompleted\":true}", false,
                    userInputCompleted = guiCompleted && index == VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS - 1), 0))
            if (index == VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS - 1) assertThat(coordinator.toolChoice).isEqualTo("none")
            assertThat(coordinator.acknowledge(ack(output), 1)).isTrue()
            coordinator.consumeContinuation()
        }
        assertThat(coordinator.toolChoice).isEqualTo(if (guiCompleted) "auto" else "none")
        if (guiCompleted) assertThat(coordinator.completedResponse(response(function("saved-question-lookup")))).hasSize(1)
        else assertThatThrownBy { coordinator.completedResponse(response(function("saved-question-lookup"))) }
            .isInstanceOf(VoiceTutorMcpProtocolException::class.java)
    }

    @ParameterizedTest
    @EnumSource(VoiceTutorDiscardedResponseReason::class)
    fun `discarded function items are closed without execution and exact acknowledgements never create a response`(
        reason: VoiceTutorDiscardedResponseReason,
    ) {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val body = response(function("orphan", arguments = "private user contents"))
        val output = coordinator.closeUnexecutedResponseCalls(body, reason, 0).single()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.continuationReady).isFalse()
        assertThat(coordinator.beginExecution("orphan")).isFalse()
        assertThat(coordinator.complete("orphan", VoiceTutorMcpToolResult("{}", false), 1)).isNull()
        val item = mapper.valueToTree<JsonNode>(output).path("item")
        val error = mapper.readTree(item.path("output").asText()).path("error")
        assertThat(error.path("code").asText()).isEqualTo(reason.name)
        assertThat(error.path("executed").asBoolean()).isFalse()
        assertThat(error.path("message").asText()).contains("not executed", "No operation is running", "latest learner request")
        assertThat(mapper.writeValueAsString(output)).doesNotContain("private user contents")
        assertThat(coordinator.closeUnexecutedResponseCalls(body, reason, 2)).isEmpty()
        assertThat(coordinator.acknowledge(ack(output), 3)).isTrue()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.continuationReady).isFalse()
        assertThat(coordinator.acknowledge(ack(output), 4)).isFalse()
        assertThat(coordinator.closeUnexecutedResponseCalls(body, reason, 5)).isEmpty()
        // A genuine new learner turn can proceed; the discarded ID can never execute later.
        coordinator.beginLearnerTurn()
        assertThatThrownBy { coordinator.completedResponse(body) }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
    }

    @Test
    fun `partial items close by their real ID while invalid IDs and duplicate items cannot create extra outputs`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val body = response(
            mapOf("type" to "function_call", "call_id" to "partial", "status" to "in_progress"),
            function("partial"), function("bad id"), function("x".repeat(257)),
            mapOf("type" to "function_call", "call_id" to 17),
            mapOf("type" to "message", "call_id" to "not-a-function"),
        )
        val output = coordinator.closeUnexecutedResponseCalls(body, VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 0)
        assertThat(output).hasSize(1)
        assertThat(mapper.valueToTree<JsonNode>(output.single()).path("item").path("call_id").asText()).isEqualTo("partial")
        assertThat(coordinator.pendingCount).isEqualTo(1)
    }

    @Test
    fun `discarding a reused ID cannot overwrite an accepted executing or completed call`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        coordinator.completedResponse(response(function("accepted")))
        assertThat(coordinator.beginExecution("accepted")).isTrue()
        assertThat(coordinator.closeUnexecutedResponseCalls(response(function("accepted")),
            VoiceTutorDiscardedResponseReason.TURN_SUPERSEDED, 0)).isEmpty()
        val original = requireNotNull(coordinator.complete("accepted", VoiceTutorMcpToolResult("{\"saved\":true}", false), 1))
        assertThat(coordinator.closeUnexecutedResponseCalls(response(function("accepted")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 2)).isEmpty()
        assertThat(coordinator.acknowledge(ack(original), 3)).isTrue()
        assertThat(coordinator.continuationReady).isTrue()
        assertThat(coordinator.closeUnexecutedResponseCalls(response(function("accepted")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 4)).isEmpty()
        assertThat(coordinator.continuationReady).isTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `orphan acknowledgement order preserves the independently earned accepted tool continuation`(orphanFirst: Boolean) {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        coordinator.completedResponse(response(function("accepted")))
        coordinator.beginExecution("accepted")
        val original = requireNotNull(coordinator.complete("accepted", VoiceTutorMcpToolResult("{}", false), 0))
        val orphan = coordinator.closeUnexecutedResponseCalls(response(function("orphan")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 1).single()
        val order = if (orphanFirst) listOf(orphan, original) else listOf(original, orphan)
        assertThat(coordinator.acknowledge(ack(order.first()), 2)).isTrue()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.continuationReady).isFalse()
        assertThat(coordinator.acknowledge(ack(order.last()), 3)).isTrue()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.continuationReady).isTrue()
        coordinator.consumeContinuation()
        val next = coordinator.closeUnexecutedResponseCalls(response(function("next-orphan")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 4).single()
        assertThat(coordinator.acknowledge(ack(next), 5)).isTrue()
        assertThat(coordinator.continuationReady).isFalse()
    }

    @Test
    fun `a ready accepted continuation waits for orphan closure but superseding speech still invalidates it`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        coordinator.completedResponse(response(function("accepted")))
        coordinator.beginExecution("accepted")
        val original = requireNotNull(coordinator.complete("accepted", VoiceTutorMcpToolResult("{}", false), 0))
        coordinator.acknowledge(ack(original), 1)
        val orphan = coordinator.closeUnexecutedResponseCalls(response(function("orphan")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 2).single()
        assertThat(coordinator.continuationReady).isFalse()
        coordinator.supersedeContinuation()
        coordinator.acknowledge(ack(orphan), 3)
        assertThat(coordinator.continuationReady).isFalse()
        coordinator.beginLearnerTurn()
    }

    @Test
    fun `only the exact orphan output acknowledgement releases its gate and missing ack expires`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        val output = coordinator.closeUnexecutedResponseCalls(response(function("orphan")),
            VoiceTutorDiscardedResponseReason.TURN_SUPERSEDED, 0).single()
        for ((field, value) in listOf("id" to "other", "call_id" to "other", "output" to "{}", "status" to "in_progress")) {
            val forged = ack(output).deepCopy<ObjectNode>()
            (forged.path("item") as ObjectNode).put(field, value)
            assertThat(coordinator.acknowledge(forged, 1)).isFalse()
        }
        assertThat(coordinator.hasPending).isTrue()
        assertThatThrownBy { coordinator.expire(Duration.ofSeconds(15).toNanos()) }
            .isInstanceOf(VoiceTutorMcpOutputAcknowledgementException::class.java)
    }

    @Test
    fun `discarded calls obey response and session bounds without granting execution or tool rounds`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        assertThatThrownBy { coordinator.closeUnexecutedResponseCalls(
            response(*(0..8).map { function("large-$it") }.toTypedArray()), VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 0)
        }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
        assertThat(coordinator.hasPending).isFalse()
        repeat(VoiceTutorMcpTurnCoordinator.MAX_CALLS_PER_SESSION) { index ->
            val output = coordinator.closeUnexecutedResponseCalls(response(function("orphan-$index")),
                VoiceTutorDiscardedResponseReason.TURN_SUPERSEDED, 0).single()
            coordinator.acknowledge(ack(output), 1)
        }
        assertThat(coordinator.toolChoice).isEqualTo("auto")
        assertThat(coordinator.continuationReady).isFalse()
        assertThatThrownBy { coordinator.closeUnexecutedResponseCalls(response(function("overflow")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 2) }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
        assertThat(coordinator.hasPending).isFalse()
        coordinator.close()
        assertThat(coordinator.closeUnexecutedResponseCalls(response(function("closed")),
            VoiceTutorDiscardedResponseReason.RESPONSE_FAILED, 3)).isEmpty()
    }

    @Test
    fun `server lesson continuation cannot reopen an exhausted model tool budget`() {
        val coordinator = VoiceTutorMcpTurnCoordinator()
        repeat(VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS) { index ->
            val id = "call-$index"
            coordinator.completedResponse(response(function(id)))
            coordinator.beginExecution(id)
            val output = requireNotNull(coordinator.complete(id, VoiceTutorMcpToolResult("{}", false), 0))
            coordinator.acknowledge(ack(output), 1); coordinator.consumeContinuation()
        }
        val scheduled = coordinator.scheduleServerCall("request_question", mapOf("study_id" to 7), 0, beginsLearnerTurn = false)
        assertThat(coordinator.toolChoice).isEqualTo("none")
        coordinator.acknowledgeServerCall(ack(scheduled.providerEvent), 1)
        coordinator.beginExecution(scheduled.callId)
        val output = requireNotNull(coordinator.complete(scheduled.callId, VoiceTutorMcpToolResult("{}", false), 1))
        coordinator.acknowledge(ack(output), 2)
        assertThat(coordinator.toolChoice).isEqualTo("none")
    }

    @Test
    fun `native repeat reads remain executed and only exact acknowledged results become shorter receipts`() {
        val coordinator = VoiceTutorMcpTurnCoordinator(compactRepeatedReads = true)
        val full = mapper.writeValueAsString(mapOf("study" to "saved topic".repeat(100)))
        fun complete(id: String, args: String = "{\"study_id\":7,\"limit\":10}", output: String = full): Map<String, Any?> {
            coordinator.completedResponse(response(function(id, args) + ("name" to "get_study")))
            assertThat(coordinator.beginExecution(id)).isTrue() // Fresh authorized adapter execution is still required.
            return requireNotNull(coordinator.complete(id, VoiceTutorMcpToolResult(output, false), 0))
        }
        val first = complete("first")
        assertThat(ack(first).path("item").path("output").asText()).isEqualTo(full)
        val forged = ack(first).deepCopy<ObjectNode>()
        (forged.path("item") as ObjectNode).put("output", "{}")
        assertThat(coordinator.acknowledge(forged, 1)).isFalse()
        assertThat(coordinator.acknowledge(ack(first), 1)).isTrue()
        coordinator.consumeContinuation()
        val second = complete("second", "{\"limit\":10,\"study_id\":7}")
        val receipt = ack(second).path("item").path("output").asText()
        assertThat(mapper.readTree(receipt).path("unchanged").asBoolean()).isTrue()
        assertThat(mapper.readTree(receipt).path("previousCallId").asText()).isEqualTo("first")
        assertThat(receipt.toByteArray().size).isLessThan(full.toByteArray().size)
        coordinator.acknowledge(ack(second), 1); coordinator.consumeContinuation()
        val third = complete("third")
        assertThat(mapper.readTree(ack(third).path("item").path("output").asText()).path("previousCallId").asText()).isEqualTo("first")
        coordinator.acknowledge(ack(third), 1); coordinator.consumeContinuation()
        val changed = complete("changed", output = full.replace("saved", "newer"))
        assertThat(ack(changed).path("item").path("output").asText()).contains("newer").doesNotContain("unchanged")
        coordinator.acknowledge(ack(changed), 1); coordinator.consumeContinuation(); coordinator.beginLearnerTurn()
        assertThat(ack(complete("new-turn")).path("item").path("output").asText()).isEqualTo(full)
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "error", "arguments", "tiny", "write", "progress", "question"])
    fun `read compaction never hides changed authority arguments failures stateful tools or expands short results`(case: String) {
        val coordinator = VoiceTutorMcpTurnCoordinator(compactRepeatedReads = case != "legacy")
        val full = if (case == "tiny") "{}" else mapper.writeValueAsString(mapOf("data" to "saved value".repeat(100)))
        val name = when (case) { "write" -> "select_voice_study"; "progress" -> "get_question_process"; "question" -> "list_pending_questions"; else -> "get_study" }
        repeat(2) { index ->
            val id = "call-$index"
            val arguments = if (case == "arguments" && index == 1) "{\"study_id\":8}" else "{\"study_id\":7}"
            coordinator.completedResponse(response(function(id, arguments) + ("name" to name)))
            coordinator.beginExecution(id)
            val output = requireNotNull(coordinator.complete(id, VoiceTutorMcpToolResult(full, case == "error" && index == 1), 0))
            assertThat(ack(output).path("item").path("output").asText()).isEqualTo(full)
            coordinator.acknowledge(ack(output), 1); coordinator.consumeContinuation()
        }
    }

    @Test
    fun `parallel repeated reads cannot use an unacknowledged sibling result`() {
        val coordinator = VoiceTutorMcpTurnCoordinator(compactRepeatedReads = true)
        val full = mapper.writeValueAsString(mapOf("data" to "saved value".repeat(100)))
        coordinator.completedResponse(response(*listOf("first", "second").map { function(it) + ("name" to "get_study") }.toTypedArray()))
        listOf("first", "second").forEach { id ->
            coordinator.beginExecution(id)
            val output = requireNotNull(coordinator.complete(id, VoiceTutorMcpToolResult(full, false), 0))
            assertThat(ack(output).path("item").path("output").asText()).isEqualTo(full)
        }
    }

    private fun function(id: String, arguments: String = "{}") = mapOf(
        "type" to "function_call", "call_id" to id, "name" to "select_voice_study", "arguments" to arguments,
        "status" to "completed",
    )

    private fun response(vararg items: Map<String, Any>) = mapper.valueToTree<JsonNode>(mapOf("output" to items.toList()))
    private fun ack(output: Map<String, Any?>) = mapper.valueToTree<JsonNode>(
        mapOf("type" to "conversation.item.created", "item" to output["item"]),
    )
}
