package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorUserInputContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** A server-owned, bounded function call; never include arguments in diagnostics. */
internal data class VoiceTutorMcpCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any>?,
) {
    override fun toString(): String = "VoiceTutorMcpCall(argumentsValid=${arguments != null})"
}

/** Correlates a server-owned call with the exact provider item that releases it. */
internal data class VoiceTutorScheduledMcpCall(
    val callId: String,
    val providerEvent: Map<String, Any?>,
)

/** Bounded identity of one exact server-owned function item rejected before it could execute. */
internal data class VoiceTutorRejectedServerCall(
    val callId: String,
    val toolName: String,
    /** False for an idempotent duplicate observation of the same exact provider rejection. */
    val newlyTombstoned: Boolean,
)

internal enum class VoiceTutorDiscardedResponseReason {
    TURN_SUPERSEDED,
    RESPONSE_FAILED,
}

/**
 * All transitions run under VoiceTutorDuplexTurnController's lock. Only a
 * correlated, successfully completed response can enqueue work. Tool execution
 * and output acknowledgement are distinct gates; neither completes audio.
 */
internal class VoiceTutorMcpTurnCoordinator(
    private val mapper: ObjectMapper = JsonMapperProvider.mapper,
    private val acknowledgementTimeout: Duration = Duration.ofSeconds(15),
    private val compactRepeatedReads: Boolean = false,
) {
    private val seenCallIds = linkedSetOf<String>()
    private val acknowledgedReads = linkedMapOf<String, AcknowledgedRead>()
    private val pending = linkedMapOf<String, Pending>()
    private val rejectedServerCallsByEventId = linkedMapOf<String, RejectedServerCall>()
    private var roundCount = 0
    private var closed = false
    private var continuationSuperseded = false
    private var continuationAcknowledged = false
    var continuationReady: Boolean = false
        private set

    val hasPending: Boolean get() = pending.isNotEmpty()
    val pendingCount: Int get() = pending.size
    val toolChoice: String get() = if (roundCount >= MAX_TOOL_ROUNDS) "none" else "auto"

    fun beginLearnerTurn() {
        check(!hasPending && !continuationReady)
        roundCount = 0
        acknowledgedReads.clear()
        continuationSuperseded = false
        continuationAcknowledged = false
    }

    /** Keep accepted tool results, but never speak a stale continuation after barge-in. */
    fun supersedeContinuation() {
        continuationReady = false
        continuationSuperseded = true
        continuationAcknowledged = false
    }

    fun consumeContinuation() {
        check(!hasPending && continuationReady)
        continuationReady = false
        continuationAcknowledged = false
    }

    fun completedResponse(response: JsonNode): List<VoiceTutorMcpCall> {
        if (closed) return emptyList()
        val items = response.path("output").filter { it.path("type").asText() == "function_call" }
        if (items.isEmpty()) return emptyList()
        if (hasPending || continuationReady || items.size > MAX_CALLS_PER_RESPONSE || roundCount >= MAX_TOOL_ROUNDS) {
            throw VoiceTutorMcpProtocolException()
        }
        val calls = items.map { item ->
            val callId = item.path("call_id").takeIf { it.isTextual }?.textValue()
                ?.takeIf { PROVIDER_ID.matches(it) } ?: throw VoiceTutorMcpProtocolException()
            if (item.has("status") && item.path("status").asText() != "completed") {
                throw VoiceTutorMcpProtocolException()
            }
            val name = item.path("name").takeIf { it.isTextual }?.textValue()
                ?.takeIf { TOOL_NAME.matches(it) } ?: throw VoiceTutorMcpProtocolException()
            VoiceTutorMcpCall(callId, name, parseArguments(item.path("arguments"),
                if (name == VoiceTutorUserInputContract.TOOL) MAX_USER_INPUT_BYTES else MAX_ARGUMENT_BYTES))
        }
        // Register every id BEFORE emitting work. A replay cannot create a
        // duplicate study, including while the first invocation is suspended.
        if (calls.map { it.callId }.distinct().size != calls.size ||
            calls.any { it.callId in seenCallIds } || seenCallIds.size + calls.size > MAX_CALLS_PER_SESSION
        ) throw VoiceTutorMcpProtocolException()
        roundCount += 1
        calls.forEach { call ->
            seenCallIds.add(call.callId)
            // Realtime conversation item ids have a 32-character ceiling.
            pending[call.callId] = Pending(
                outputItemId = "vtmcp_${UUID.randomUUID().toString().replace("-", "").take(26)}",
                toolName = call.name,
                readKey = readKey(call),
            )
        }
        return calls
    }

    /**
     * Close provider-visible function items from a discarded response without ever
     * executing them. A cancelled/failed response may contain completed or partial
     * function items even though it never qualified for [completedResponse].
     * Existing IDs belong to their original execution/output and are never changed.
     * These outputs use the normal exact ACK fence but confer no new continuation.
     */
    fun closeUnexecutedResponseCalls(
        response: JsonNode,
        reason: VoiceTutorDiscardedResponseReason,
        nowNanos: Long,
    ): List<Map<String, Any?>> {
        if (closed || !response.path("output").isArray) return emptyList()
        val items = response.path("output").filter { it.path("type").asText() == "function_call" }
        if (items.size > MAX_CALLS_PER_RESPONSE) throw VoiceTutorMcpProtocolException()
        val calls = linkedMapOf<String, String>()
        items.forEach { item ->
            val id = item.path("call_id").takeIf(JsonNode::isTextual)?.textValue()
                ?.takeIf(PROVIDER_ID::matches) ?: return@forEach
            if (id !in seenCallIds && id !in pending) {
                // Partial function items may not yet have a name or arguments. Only
                // the actual provider call ID is needed to close them, never their data.
                calls.putIfAbsent(id, item.path("name").takeIf(JsonNode::isTextual)?.textValue()
                    ?.takeIf(TOOL_NAME::matches) ?: "unexecuted_tool")
            }
        }
        if (seenCallIds.size + calls.size > MAX_CALLS_PER_SESSION) throw VoiceTutorMcpProtocolException()
        if (calls.isEmpty()) return emptyList()
        val result = VoiceTutorMcpToolResult(mapper.writeValueAsString(mapOf("error" to mapOf(
            "code" to reason.name,
            "executed" to false,
            "message" to "This function call belonged to a discarded response and was not executed. No operation is running for this call. Follow the latest learner request; never treat an earlier promise or this discarded call as work in progress.",
        ))), true)
        continuationReady = false
        return calls.map { (callId, name) ->
            seenCallIds.add(callId)
            pending[callId] = Pending(
                outputItemId = "vtmcp_${UUID.randomUUID().toString().replace("-", "").take(26)}",
                toolName = name, started = true, discardedResponse = true,
            )
            requireNotNull(complete(callId, result, nowNanos))
        }
    }

    /**
     * Registers a server-owned call whose arguments came from durable, assessed
     * learner input rather than a model response. The synthetic function item
     * must be acknowledged into provider conversation context before execution,
     * and its output keeps the ordinary continuation gate closed until ACKed.
     */
    fun scheduleServerCall(
        name: String,
        arguments: Map<String, Any>,
        nowNanos: Long,
        beginsLearnerTurn: Boolean = true,
    ): VoiceTutorScheduledMcpCall {
        if (closed || !TOOL_NAME.matches(name) || seenCallIds.size >= MAX_CALLS_PER_SESSION) {
            throw VoiceTutorMcpProtocolException()
        }
        val argumentsJson = runCatching { mapper.writeValueAsString(arguments) }
            .getOrElse { throw VoiceTutorMcpProtocolException() }
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
            throw VoiceTutorMcpProtocolException()
        }
        val frozenArguments = parseArguments(mapper.valueToTree(argumentsJson))
            ?: throw VoiceTutorMcpProtocolException()
        val callId = newServerCallId()
        if (callId.length > MAX_PROVIDER_CALL_ID_LENGTH || !PROVIDER_ID.matches(callId)) {
            throw VoiceTutorMcpProtocolException()
        }
        val callItemId = "vtmcp_c_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val outputItemId = "vtmcp_${UUID.randomUUID().toString().replace("-", "").take(26)}"
        val providerCallEventId = "buddystudy-internal-server-tool-call-${UUID.randomUUID()}"
        val call = VoiceTutorMcpCall(callId, name, frozenArguments)
        if (!seenCallIds.add(callId)) throw VoiceTutorMcpProtocolException()
        // UI answer/choice actions start a new human turn; a selected lesson's
        // automatic continuation stays within its existing model round budget.
        if (beginsLearnerTurn) {
            roundCount = 0
            acknowledgedReads.clear()
        }
        // Fold any already-ACKed prior result into the eventual continuation.
        continuationSuperseded = false
        continuationReady = false
        continuationAcknowledged = false
        pending[callId] = Pending(
            outputItemId = outputItemId,
            toolName = name,
            providerCallItemId = callItemId,
            providerCallEventId = providerCallEventId,
            serverCall = call,
            providerCallAcknowledgementDeadline = nowNanos + acknowledgementTimeout.toNanos(),
        )
        val event = linkedMapOf<String, Any?>(
            "event_id" to providerCallEventId,
            "type" to "conversation.item.create",
            "item" to linkedMapOf(
                "id" to callItemId,
                "type" to "function_call",
                "status" to "completed",
                "call_id" to callId,
                "name" to name,
                "arguments" to argumentsJson,
            ),
        )
        if (mapper.writeValueAsBytes(event).size > MAX_PROVIDER_EVENT_BYTES) {
            pending.remove(callId)
            seenCallIds.remove(callId)
            throw VoiceTutorMcpProtocolException()
        }
        return VoiceTutorScheduledMcpCall(callId, event)
    }

    /**
     * Tombstones only the exact synthetic item-create event while its call is still wholly
     * unobserved by the provider. The call id remains in [seenCallIds], so neither a late ACK nor
     * any replay can enqueue or execute it. Repeating the same exact rejection is a safe no-op.
     * Unknown, already released, or already started calls return null and retain their state.
     */
    fun tombstoneRejectedServerCall(providerEventId: String): VoiceTutorRejectedServerCall? {
        rejectedServerCallsByEventId[providerEventId]?.let { rejected ->
            return VoiceTutorRejectedServerCall(rejected.callId, rejected.toolName, newlyTombstoned = false)
        }
        val entry = pending.entries.singleOrNull { (_, call) ->
            call.providerCallEventId == providerEventId && call.serverCall != null &&
                !call.serverCallReleased && !call.started && call.expectedOutput == null &&
                call.providerCallAcknowledgementDeadline != null && call.acknowledgementDeadline == null
        } ?: return null
        val removed = pending.remove(entry.key) ?: return null
        val rejected = RejectedServerCall(entry.key, removed.toolName)
        rejectedServerCallsByEventId[providerEventId] = rejected
        return VoiceTutorRejectedServerCall(rejected.callId, rejected.toolName, newlyTombstoned = true)
    }

    /** Duplicate or mutated provider ACKs never release server-owned work. */
    fun acknowledgeServerCall(event: JsonNode, nowNanos: Long): VoiceTutorMcpCall? {
        if (closed || event.path("type").asText() !in OUTPUT_ACK_EVENTS) return null
        expire(nowNanos)
        val item = event.path("item")
        if (item.path("type").asText() != "function_call") return null
        val callId = item.path("call_id").asText()
        val pendingCall = pending[callId] ?: return null
        val serverCall = pendingCall.serverCall ?: return null
        if (pendingCall.serverCallReleased ||
            item.path("id").asText() != pendingCall.providerCallItemId ||
            item.path("name").asText() != serverCall.name ||
            parseArguments(item.path("arguments")) != serverCall.arguments ||
            (item.has("status") && item.path("status").asText() != "completed")
        ) return null
        pendingCall.serverCallReleased = true
        pendingCall.providerCallAcknowledgementDeadline = null
        return serverCall
    }

    fun beginExecution(callId: String): Boolean {
        val call = pending[callId] ?: return false
        if (closed || call.started || (call.serverCall != null && !call.serverCallReleased)) return false
        call.started = true
        return true
    }

    fun startedToolName(callId: String): String? = pending[callId]
        ?.takeIf { !closed && it.started }
        ?.toolName

    fun startedServerToolName(callId: String): String? = pending[callId]
        ?.takeIf { !closed && it.started && it.serverCall != null }
        ?.toolName

    fun complete(callId: String, result: VoiceTutorMcpToolResult, nowNanos: Long): Map<String, Any?>? {
        val call = pending[callId] ?: return null
        if (closed || !call.started || call.acknowledgementDeadline != null) return null
        // The bridge returns bounded JSON, but keep this final transport guard
        // for alternate adapters. Never truncate JSON into an invalid output.
        val maximum = if (call.toolName == VoiceTutorUserInputContract.TOOL) MAX_USER_INPUT_BYTES else MAX_OUTPUT_BYTES
        val validOutput = result.output.toByteArray(Charsets.UTF_8).size <= maximum &&
            runCatching { mapper.readTree(result.output)?.isObject == true }.getOrDefault(false)
        val fullOutput = if (validOutput) result.output else INVALID_RESULT_OUTPUT
        val readHash = if (validOutput && !result.isError && call.readKey != null) digest(fullOutput) else null
        val previousRead = call.readKey?.let(acknowledgedReads::get)?.takeIf { it.resultHash == readHash }
        // Only an exactly acknowledged prior result in this same human turn can
        // supply context. Always execute and authorize the fresh read first.
        val receipt = if (previousRead != null) mapper.writeValueAsString(mapOf(
            "unchanged" to true, "previousCallId" to previousRead.callId,
            "notice" to "Reuse the previous successful result for this same read; no values changed.",
        )) else null
        val output = receipt?.takeIf { it.toByteArray(Charsets.UTF_8).size < fullOutput.toByteArray(Charsets.UTF_8).size } ?: fullOutput
        call.readResultHash = readHash
        val event = linkedMapOf<String, Any?>(
            "event_id" to "buddystudy-internal-tool-output-${UUID.randomUUID()}",
            "type" to "conversation.item.create",
            "item" to linkedMapOf(
                "id" to call.outputItemId,
                "type" to "function_call_output",
                "call_id" to callId,
                "output" to output,
            ),
        )
        if (mapper.writeValueAsBytes(event).size > MAX_PROVIDER_EVENT_BYTES) throw VoiceTutorMcpProtocolException()
        call.expectedOutput = output
        // A server curriculum form can complete a select/question tool. Only this typed
        // exact GUI receipt, never provider JSON or a tool name, starts a new human budget.
        call.resetsHumanRoundBudget = result.userInputCompleted
        call.acknowledgementDeadline = nowNanos + acknowledgementTimeout.toNanos()
        return event
    }

    /** GA item.added/done and the documented item.created ACK share this shape. */
    fun acknowledge(event: JsonNode, nowNanos: Long): Boolean {
        if (closed || event.path("type").asText() !in OUTPUT_ACK_EVENTS) return false
        expire(nowNanos)
        val item = event.path("item")
        if (item.path("type").asText() != "function_call_output") return false
        val callId = item.path("call_id").asText()
        val call = pending[callId] ?: return false
        if (call.acknowledgementDeadline == null || item.path("id").asText() != call.outputItemId ||
            !item.path("output").isTextual || item.path("output").textValue() != call.expectedOutput
        ) return false
        if (item.has("status") && item.path("status").asText() != "completed") return false
        pending.remove(callId)
        if (call.readKey != null && call.readResultHash != null) {
            // Keep the first full output as the reference; receipts never form a chain.
            val previous = acknowledgedReads[call.readKey]
            if (previous?.resultHash != call.readResultHash) {
                acknowledgedReads[call.readKey] = AcknowledgedRead(callId, requireNotNull(call.readResultHash))
                while (acknowledgedReads.size > 32) acknowledgedReads.remove(acknowledgedReads.keys.first())
            }
        }
        if (call.resetsHumanRoundBudget) {
            roundCount = 0
            acknowledgedReads.clear()
        }
        if (!call.discardedResponse) continuationAcknowledged = true
        if (pending.isEmpty() && !continuationSuperseded && continuationAcknowledged) continuationReady = true
        return true
    }

    fun expire(nowNanos: Long) {
        if (!closed && pending.values.any {
                it.providerCallAcknowledgementDeadline?.let { deadline -> nowNanos >= deadline } == true ||
                    it.acknowledgementDeadline?.let { deadline -> nowNanos >= deadline } == true
            }
        ) {
            throw VoiceTutorMcpOutputAcknowledgementException()
        }
    }

    fun close() {
        closed = true
        pending.clear()
        seenCallIds.clear()
        acknowledgedReads.clear()
        rejectedServerCallsByEventId.clear()
        continuationReady = false
        continuationAcknowledged = false
    }

    private fun readKey(call: VoiceTutorMcpCall): String? {
        if (!compactRepeatedReads || call.name !in COMPACTABLE_READS || call.arguments == null) return null
        return digest(call.name + ":" + mapper.writeValueAsString(call.arguments.toSortedMap()))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class AcknowledgedRead(val callId: String, val resultHash: String)

    private fun parseArguments(node: JsonNode, maximumBytes: Int = MAX_ARGUMENT_BYTES): Map<String, Any>? {
        if (!node.isTextual || node.textValue().toByteArray(Charsets.UTF_8).size > maximumBytes) return null
        return runCatching {
            val parsed = mapper.readTree(node.textValue())
            if (parsed?.isObject != true) return null
            mapper.convertValue(parsed, object : TypeReference<Map<String, Any>>() {})
        }.getOrNull()
    }

    /** Full UUID entropy encoded inside Realtime's 32-character call_id ceiling. */
    private fun newServerCallId(): String {
        val uuid = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        return "bsvt_s_${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
    }

    private data class Pending(
        val outputItemId: String,
        val toolName: String,
        val readKey: String? = null,
        var readResultHash: String? = null,
        val providerCallItemId: String? = null,
        val providerCallEventId: String? = null,
        val serverCall: VoiceTutorMcpCall? = null,
        var serverCallReleased: Boolean = false,
        var providerCallAcknowledgementDeadline: Long? = null,
        var started: Boolean = false,
        var expectedOutput: String? = null,
        var acknowledgementDeadline: Long? = null,
        var resetsHumanRoundBudget: Boolean = false,
        val discardedResponse: Boolean = false,
    )

    private data class RejectedServerCall(
        val callId: String,
        val toolName: String,
    )

    companion object {
        private val COMPACTABLE_READS = setOf("list_studies", "get_study", "get_topic_stats", "get_study_growth")
        const val MAX_CALLS_PER_RESPONSE = 8
        const val MAX_CALLS_PER_SESSION = 256
        // A legal catalog path can contain one root plus five descendants.
        // Discovery needs one query and one complete child read for every node,
        // including the endpoint's empty leaf page.
        const val MAX_TOOL_ROUNDS = 7
        const val MAX_ARGUMENT_BYTES = 16 * 1024
        const val MAX_OUTPUT_BYTES = 16 * 1024
        private const val MAX_USER_INPUT_BYTES = 64 * 1024
        const val MAX_PROVIDER_EVENT_BYTES = 65_536
        const val MAX_PROVIDER_CALL_ID_LENGTH = 32
        val OUTPUT_ACK_EVENTS = setOf("conversation.item.created", "conversation.item.added", "conversation.item.done")
        private val PROVIDER_ID = Regex("[A-Za-z0-9_-]{1,256}")
        private val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private const val INVALID_RESULT_OUTPUT =
            """{"error":{"code":"INVALID_TOOL_RESULT","message":"The result could not be confirmed; check saved topics before retrying a write."}}"""
    }
}

internal class VoiceTutorMcpProtocolException : RuntimeException("Voice Tutor tool response violated its bounded contract.")
internal class VoiceTutorMcpOutputAcknowledgementException : RuntimeException("Voice Tutor tool output acknowledgement timed out.")
