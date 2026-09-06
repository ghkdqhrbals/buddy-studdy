package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract as Contract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata as Metadata
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Native audio context is the model's input. ASR is a transcript, never a reply/intent gate.
 * All state transitions are serialized here; tools and transcript IO have independent subscribers.
 * The legacy PCM controller deliberately remains separate from this versioned WebRTC contract.
 */
internal class VoiceTutorNativeConversationController(
    private val responseTimeout: Duration,
    initialLessonRevision: Long = 0,
    private val language: String = "ko",
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallClock: () -> Instant = Instant::now,
) {
    private val mapper = JsonMapperProvider.mapper
    private val provider = sink<String>()
    private val client = sink<String>()
    private val persistence = sink<NativeTranscript>()
    private val tools = sink<VoiceTutorMcpCall>()
    private val lifecycle = sink<String>()
    private val failure = Sinks.one<Throwable>()
    private val quotaDone = Sinks.one<Void>()
    private val toolCoordinator = VoiceTutorMcpTurnCoordinator()
    private val pause = VoiceTutorPauseCoordinator(responseTimeout)
    private var closed = false
    private var draining = false
    private var speaking = false
    private var ready = false
    private var opening = true
    private var queuedInput = false
    private var sequence = 0L
    private var eventOrder = 0L
    private var generation = 0L
    private var revision = initialLessonRevision
    private var active: Response? = null
    private var latestLearner: Input? = null
    private var latestTutor: Tutor? = null
    private var lastActivity = nanoTime()
    private var quotaRequested = false
    private var endingAfterResponse = false
    private var retryCount = 0
    private var quotaRetryCount = 0
    private var clientSpeechSequence = 0L
    private var pendingSpeech: SpeechBoundary? = null
    private val commits = ArrayDeque<SpeechBoundary>()
    private var lastCommitAt: Long? = null
    private val inputs = linkedMapOf<String, Input>()
    private val transcripts = linkedMapOf<String, TranscriptState>()
    private val pendingTools = linkedMapOf<String, ToolBoundary>()
    private val seenResponses = linkedSetOf<String>()
    private val earlyTranscripts = linkedMapOf<String, String>()
    private var integrityIncomplete = false
    private var integrityRecorded = false

    fun providerEvents(): Flux<String> = provider.asFlux()
    fun clientEvents(): Flux<String> = client.asFlux()
    fun persistenceEvents(): Flux<NativeTranscript> = persistence.asFlux()
    fun toolActions(): Flux<VoiceTutorMcpCall> = tools.asFlux()
    fun lifecycleEvents(): Flux<String> = lifecycle.asFlux()
    fun failure(): Mono<Void> = failure.asMono().flatMap { Mono.error(it) }
    fun quotaCompletion(): Mono<Void> = quotaDone.asMono()

    @Synchronized
    fun forwardClientEvent(raw: String) { if (!closed) publish(client, raw) }

    @Synchronized
    fun start() { ready = true; scheduleResponse() }

    /** Returns whether a privacy-filtered provider event should also be sent to the client. */
    @Synchronized
    fun observeProviderEvent(raw: String): Boolean {
        if (closed) return false
        val node = mapper.readTree(raw)
        val type = node.path("type").asText()
        lastActivity = nanoTime()
        if (type in VoiceTutorMcpTurnCoordinator.OUTPUT_ACK_EVENTS && toolCoordinator.acknowledge(node, nanoTime())) {
            scheduleResponse()
            return false
        }
        when (type) {
            // Provider VAD is disabled. Unexpected VAD edges cannot acquire turn authority.
            "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped" -> return false
            "input_audio_buffer.committed" -> {
                val id = node.path("item_id").asText()
                if (!validId(id)) return false
                if (id in inputs) return false
                val boundary = commits.removeFirstOrNull() ?: return false
                val input = input(id, boundary)
                input.committed = true
                earlyTranscripts.remove(id)?.let { early ->
                    if (observeProviderEvent(early)) publish(client, early)
                }
                // This boundary was authorized before the commit was sent. A pause
                // may delay its ACK, but must preserve the reply for resume.
                if (!draining && !quotaRequested) {
                    latestLearner = input
                    queuedInput = true
                    // This is the entire ordinary response path: no transcription, classifier or DB await.
                    scheduleResponse()
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val id = node.path("item_id").asText()
                val input = inputs[id] ?: run { rememberEarlyTranscript(id, raw); return false }
                if (!input.committed) return false
                enqueueTranscript(id, raw, input.sequence, input.revision, input.acceptedAt)
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val id = node.path("item_id").asText()
                inputs[id]?.transcriptionFailed = true
                if (id !in inputs) rememberEarlyTranscript(id, raw)
                else markTranscriptIncomplete()
                // Native audio remains in context; an ASR failure must not discard the learner's turn.
                return false
            }
            "response.created" -> {
                val response = active ?: return false
                val body = node.path("response")
                val id = body.path("id").asText()
                val token = body.path("metadata").path(Contract.RESPONSE_TOKEN_METADATA_KEY).asText()
                if (!validId(id) || token != response.token || id in seenResponses) return false
                if (response.id != null && response.id != id) return false
                response.id = id
                response.createdRaw = raw
                seenResponses.add(id)
                trim(seenResponses, 1024)
                // Tool-only and silent/noise reasoning is not presented as audible tutor speech.
                return false
            }
            "response.output_item.added" -> {
                val response = matchingResponse(node) ?: return false
                val item = node.path("item")
                if (item.path("type").asText() == "message") stampTutor(response, item.path("id").asText())
            }
            "response.output_audio_transcript.delta" -> {
                val response = matchingResponse(node) ?: return false
                announceResponse(response)
                stampTutor(response, node.path("item_id").asText())
            }
            "response.output_audio_transcript.done" -> {
                val response = matchingResponse(node) ?: return false
                announceResponse(response)
                val id = node.path("item_id").asText()
                stampTutor(response, id)?.raw = raw
            }
            "output_audio_buffer.started" -> {
                val response = matchingResponse(node) ?: return false
                announceResponse(response)
                response.audioStarted = true
                response.audioStopped = false
            }
            "output_audio_buffer.stopped" -> {
                val response = matchingResponse(node) ?: return false
                response.audioStopped = true
                finishResponseIfReady()
            }
            "output_audio_buffer.cleared" -> {
                val response = matchingResponse(node) ?: return false
                response.failed = true
                response.audioStopped = true
                // A cleared playout is not evidence of a completed question or confirmation.
                publish(client, json(mapOf("type" to Contract.INPUT_RETRY_EVENT, "abandonedResponseId" to response.id)))
                finishResponseIfReady()
                return false
            }
            "response.done" -> {
                val response = active ?: return false
                val body = node.path("response")
                if (response.id != body.path("id").asText()) return false
                if (response.done) return false
                response.done = true
                response.failed = response.failed || body.path("status").asText() != "completed"
                response.body = body
                body.path("output").forEach { item ->
                    if (item.path("type").asText() == "message") {
                        val id = item.path("id").asText()
                        val tutor = stampTutor(response, id)
                        val text = item.path("content").firstOrNull { it.path("transcript").isTextual }
                            ?.path("transcript")?.asText()
                        if (tutor != null && tutor.raw == null && !text.isNullOrBlank()) {
                            tutor.raw = json(mapOf("type" to "response.output_audio_transcript.done",
                                "response_id" to response.id, "item_id" to id, "transcript" to text))
                        }
                    }
                }
                // Tool-only and empty/noise responses have no playout boundary to wait for.
                response.expectsAudio = body.path("output").any { item ->
                    item.path("content").any { it.path("type").asText() in setOf("audio", "output_audio") }
                } || response.audioStarted
                if (response.expectsAudio) announceResponse(response)
                finishResponseIfReady()
                if (!response.expectsAudio) return false
            }
            "input_audio_buffer.cleared" -> {
                applyPause(pause.acknowledgeClear(node.path("event_id").asText(), nanoTime()))
                scheduleResponse()
            }
            "error" -> {
                val error = node.path("error")
                if (error.path("code").asText() == "input_audio_buffer_commit_empty") {
                    val eventId = error.path("event_id").asText()
                    if (commits.firstOrNull()?.commitEventId == eventId) commits.removeFirst()
                    scheduleResponse()
                    return false
                }
                if (classifyRealtimeProviderError(node) == VoiceTutorProviderErrorDisposition.RECOVERABLE) {
                    return false
                }
            }
        }
        return true
    }

    @Synchronized
    fun observeClientEvent(raw: String) {
        if (closed) return
        val node = mapper.readTree(raw)
        val seq = node.path("sequence").asLong()
        when (node.path("type").asText()) {
            Contract.SPEECH_STARTED_EVENT -> {
                if (seq > clientSpeechSequence && !draining && !quotaRequested && pause.acceptsSpeechEdges) {
                    clientSpeechSequence = seq
                    speaking = true
                    // A rapid restart shares the native buffer with an uncommitted short tail.
                    if (pendingSpeech == null) pendingSpeech = SpeechBoundary(++sequence, revision, ++eventOrder, wallClock(),
                        latestTutor.takeIf { active == null }, nanoTime())
                }
            }
            Contract.SPEECH_STOPPED_EVENT -> {
                if (seq == clientSpeechSequence && speaking) {
                    speaking = false
                    commitSpeech()
                }
            }
            Contract.PAUSE_REQUEST_EVENT -> applyPause(pause.requestPause(seq, nanoTime()))
            Contract.PAUSE_INPUT_QUIESCED_EVENT -> { pause.confirmInputQuiesced(seq); speaking = false }
            Contract.RESUME_REQUEST_EVENT -> applyPause(pause.requestResume(seq, nanoTime()))
            Contract.PLAYOUT_DRAINED_EVENT -> {
                val response = active
                if (response != null && response.id == node.path("responseId").asText()) {
                    response.deviceDrained = true
                    finishResponseIfReady()
                }
            }
        }
        advancePause()
        scheduleResponse()
    }

    @Synchronized
    fun tick() {
        if (closed) return
        try {
            toolCoordinator.expire(nanoTime())
            advancePause()
            pendingSpeech?.let { speech ->
                if (!speaking) commitSpeech()
                else if (nanoTime() - speech.committedAt >= Duration.ofSeconds(20).toNanos()) {
                    // Bound long answer buffers without taking the learner's turn or generating speech.
                    commitSpeech()
                    pendingSpeech = speech.copy(sequence = ++sequence, acceptedAt = wallClock(),
                        committedAt = nanoTime(), commitEventId = "buddystudy-internal-native-commit-${UUID.randomUUID()}")
                }
            }
            if (commits.firstOrNull()?.let { nanoTime() - it.committedAt > Duration.ofSeconds(5).toNanos() } == true) {
                fail(VoiceTutorProviderInputCommitTimeoutException())
            }
            active?.let { response ->
                if (response.quota && !response.audioStarted && !response.done && !response.cancellationRequested &&
                    nanoTime() - response.startedAt >= Duration.ofSeconds(3).toNanos()
                ) {
                    response.id?.let {
                        response.cancellationRequested = true
                        emit(mapOf("type" to "response.cancel", "response_id" to it))
                    }
                }
                val allowance = if (response.done) Duration.ofSeconds(120) else responseTimeout
                if (nanoTime() - response.startedAt > allowance.toNanos()) {
                    fail(VoiceTutorProviderResponseTimeoutException())
                }
            }
        } catch (error: Exception) { fail(error) }
    }

    @Synchronized
    fun requestQuotaNotice() {
        if (closed || quotaRequested) return
        quotaRequested = true
        queuedInput = false
        speaking = false
        // Prevent new input, but let the current spoken sentence complete before the terminal notice.
        scheduleResponse()
    }

    @Synchronized
    fun beginDrain(cancelActive: Boolean) {
        draining = true
        queuedInput = false
        if (cancelActive) active?.id?.let { emit(mapOf("type" to "response.cancel", "response_id" to it)) }
    }

    @Synchronized
    fun isDrained(): Boolean = !hasUnsettledTranscriptEvidence() && active == null &&
        nanoTime() - lastActivity >= Duration.ofMillis(200).toNanos()

    /** Retained after close so cancellation cannot turn a partly saved answer into complete evidence. */
    @Synchronized
    fun hasUnsettledTranscriptEvidence(): Boolean =
        (integrityIncomplete && !integrityRecorded) || speaking || pendingSpeech != null ||
            commits.isNotEmpty() || earlyTranscripts.isNotEmpty() || transcripts.values.any { !it.complete } ||
            inputs.values.any { it.committed && !it.transcriptionFailed && it.id !in transcripts }

    @Synchronized
    fun toolBoundary(callId: String): VoiceTutorDialogueBoundary? = pendingTools[callId]?.boundary

    @Synchronized
    fun toolRevision(callId: String): Long = pendingTools[callId]?.revision ?: revision

    @Synchronized
    fun toolCanExecute(callId: String): Boolean {
        val call = pendingTools[callId] ?: return false
        if (closed || draining || quotaRequested || speaking || pendingSpeech != null || commits.isNotEmpty()) return false
        return call.boundary.latestAcceptedLearnerProviderItemId == latestLearner?.id &&
            call.revision == revision
    }

    /** Mutations/selection may await their exact audit rows; normal voice never calls this gate. */
    @Synchronized
    fun toolTranscriptReady(callId: String): Boolean {
        val boundary = pendingTools[callId]?.boundary ?: return false
        return listOfNotNull(boundary.latestAcceptedLearnerProviderItemId, boundary.precedingTutorProviderItemId)
            .all { transcripts[it]?.complete == true }
    }

    @Synchronized
    fun beginTool(callId: String): Boolean = !closed && toolCoordinator.beginExecution(callId)

    @Synchronized
    fun completeTool(callId: String, result: VoiceTutorMcpToolResult) {
        if (closed) return
        val output = toolCoordinator.complete(callId, result, nanoTime()) ?: return
        pendingTools.remove(callId)
        if (!result.isError) {
            result.lessonRevision?.takeIf { it >= revision }?.let { revision = it }
            voiceTutorLessonFocusEvent(result)?.let { publish(client, it) }
            if (result.studyTreeChanged && result.changedStudyId != null) publish(client, json(mapOf(
                "type" to Contract.STUDY_TREE_CHANGED_EVENT, "studyId" to result.changedStudyId,
                "change" to result.changeKind?.name?.lowercase(), "deletedStudyIds" to result.deletedStudyIds,
            )))
        }
        emit(output)
    }

    @Synchronized
    fun requestSpokenEnd() { endingAfterResponse = true }

    @Synchronized
    fun transcriptCompleted(itemId: String) { transcripts[itemId]?.complete = true }

    @Synchronized
    fun markTranscriptIncomplete() {
        if (integrityIncomplete || closed) return
        integrityIncomplete = true
        publish(lifecycle, json(mapOf("type" to Metadata.INCOMPLETE_EVENT)))
    }

    @Synchronized
    fun transcriptIntegrityRecorded() { integrityRecorded = true }

    @Synchronized
    fun isIntegritySettled(): Boolean = !integrityIncomplete || integrityRecorded

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        pause.close()
        toolCoordinator.close()
        provider.tryEmitComplete(); client.tryEmitComplete(); persistence.tryEmitComplete()
        tools.tryEmitComplete(); lifecycle.tryEmitComplete()
    }

    private fun scheduleResponse() {
        if (!ready || closed || draining || active != null || toolCoordinator.hasPending) return
        val quota = quotaRequested
        if (!quota && (speaking || pendingSpeech != null || commits.isNotEmpty() || pause.blocksResponses || (!opening && !queuedInput && !toolCoordinator.continuationReady))) return
        if (toolCoordinator.continuationReady) toolCoordinator.consumeContinuation() else toolCoordinator.beginLearnerTurn()
        val isOpening = opening
        opening = false
        queuedInput = false
        val token = UUID.randomUUID().toString()
        val learner = latestLearner
        val response = Response(token, ++generation, revision, nanoTime(), quota, boundary(learner))
        active = response
        val instructions = when {
            quota -> "Say exactly this one sentence and nothing else; no question or tool: " + when (language) {
                "en" -> "This notice uses the last of your monthly voice time, so I'll end the call now."
                "ja" -> "今月の音声利用時間を使い切ったため、通話を終了します。"
                else -> "이번 달 음성 시간이 모두 소진되어 통화를 종료할게요."
            }
            endingAfterResponse -> "Say exactly this one goodbye and nothing else; no tool: " + when (language) {
                "en" -> "Let's talk again next time."
                "ja" -> "またお話ししましょう。"
                else -> "다음에 또 이야기해요."
            }
            isOpening -> "Say exactly this one question, without a greeting, self-introduction, name, AI title or readiness ceremony: " + when (language) {
                "en" -> "What topic would you like to talk about?"
                "ja" -> "どんなテーマについて話しましょうか？"
                else -> "어떤 주제로 이야기해 볼까요?"
            }
            else -> null
        }
        val options = linkedMapOf<String, Any>(
            "output_modalities" to listOf("audio"), "tool_choice" to if (quota || endingAfterResponse || isOpening) "none" else toolCoordinator.toolChoice,
            "metadata" to mapOf(Contract.RESPONSE_TOKEN_METADATA_KEY to token, Contract.QUOTA_NOTICE_METADATA_KEY to quota),
        )
        instructions?.let { options["instructions"] = it }
        emit(mapOf("type" to "response.create", "response" to options))
    }

    private fun finishResponseIfReady() {
        val response = active ?: return
        if (!response.done || (response.expectsAudio && !response.audioStopped)) return
        if (response.quota && (!response.expectsAudio || response.failed)) {
            if (quotaRetryCount++ == 0) { active = null; scheduleResponse() }
            else fail(VoiceTutorQuotaExhaustionNoticeInterruptedException())
            return
        }
        if (response.quota && !response.deviceDrained) return
        if (!response.failed && response.expectsAudio) {
            if (response.tutors.isEmpty() || response.tutors.values.any { it.raw == null }) {
                // Completed audible speech without its source is a gap, not a silent/tool-only turn.
                markTranscriptIncomplete()
            }
            response.tutors.forEach { (id, tutor) ->
                tutor.raw?.let { enqueueTranscript(id, it, tutor.sequence, response.revision, tutor.acceptedAt) }
                if (tutor.raw != null) latestTutor = Tutor(id, ++eventOrder, response.generation)
            }
        }
        active = null
        if (response.quota) { quotaDone.tryEmitEmpty(); return }
        if (endingAfterResponse && !response.failed) {
            draining = true
            publish(lifecycle, json(mapOf("type" to Contract.SPOKEN_LESSON_END_EVENT)))
            return
        }
        if (!response.failed) {
            retryCount = 0
            val calls = toolCoordinator.completedResponse(response.body ?: return)
            calls.forEach { call ->
                pendingTools[call.callId] = ToolBoundary(response.boundary.copy(responseGeneration = response.generation), response.revision)
                publish(tools, call)
            }
        } else if (!draining && !quotaRequested && retryCount++ == 0) {
            queuedInput = true
        } else {
            publish(client, json(mapOf("type" to Contract.INPUT_RETRY_EVENT, "abandonedResponseId" to response.id)))
        }
        advancePause()
        scheduleResponse()
    }

    private fun boundary(learner: Input?): VoiceTutorDialogueBoundary = VoiceTutorDialogueBoundary(
        responseGeneration = generation,
        latestAcceptedLearnerSpeechStartedOrder = learner?.startedOrder ?: 0,
        precedingTutorSpeechStoppedOrder = learner?.precedingTutor?.stoppedOrder ?: 0,
        precedingSpokenResponseGeneration = learner?.precedingTutor?.generation ?: 0,
        precedingTutorProviderItemId = learner?.precedingTutor?.id,
        latestAcceptedLearnerProviderItemId = learner?.id,
        latestAcceptedLearnerLessonRevision = learner?.revision ?: revision,
    )

    private fun input(id: String, speech: SpeechBoundary): Input = inputs.getOrPut(id) {
        check(inputs.size < 4096) { "Voice call exceeded bounded transcript capacity." }
        Input(id, speech.sequence, speech.revision, speech.startedOrder, speech.acceptedAt, speech.precedingTutor)
    }

    private fun commitSpeech() {
        val speech = pendingSpeech ?: return
        if (lastCommitAt?.let { nanoTime() - it < Duration.ofMillis(200).toNanos() } == true) return
        pendingSpeech = null
        if (commits.size >= 32) { fail(VoiceTutorPendingInputCommitOverflowException()); return }
        speech.committedAt = nanoTime()
        lastCommitAt = nanoTime()
        commits.addLast(speech)
        emit(mapOf("type" to "input_audio_buffer.commit", "event_id" to speech.commitEventId))
    }

    private fun stampTutor(response: Response, id: String): TutorTranscript? {
        if (!validId(id)) return null
        return response.tutors.getOrPut(id) { TutorTranscript(++sequence, wallClock()) }
    }

    private fun matchingResponse(node: JsonNode): Response? = active?.takeIf {
        it.id != null && it.id == node.path("response_id").asText()
    }

    private fun announceResponse(response: Response) {
        if (!response.announced) {
            response.createdRaw?.let { publish(client, it) }
            response.announced = true
        }
    }

    private fun rememberEarlyTranscript(id: String, raw: String) {
        if (!validId(id) || raw.length > 40_000 || earlyTranscripts.size >= 32) return
        earlyTranscripts.putIfAbsent(id, raw)
    }

    private fun enqueueTranscript(id: String, raw: String, order: Long, epoch: Long, acceptedAt: Instant) {
        if (id in transcripts) return
        val node = mapper.readTree(raw) as ObjectNode
        node.put(Metadata.POST_CALL_EVIDENCE, true)
        node.put(Metadata.CONVERSATION_SEQUENCE, order)
        node.put(Metadata.LESSON_REVISION, epoch)
        node.put(Metadata.ACCEPTED_AT_EPOCH_MILLIS, acceptedAt.toEpochMilli())
        transcripts[id] = TranscriptState()
        publish(persistence, NativeTranscript(id, mapper.writeValueAsString(node)))
    }

    private fun advancePause() = applyPause(pause.advance(active == null && commits.isEmpty() && pendingSpeech == null, nanoTime()))
    private fun applyPause(actions: List<VoiceTutorPauseCoordinator.Action>) {
        actions.forEach { action -> when (action) {
            is VoiceTutorPauseCoordinator.Action.ClearInput -> emit(mapOf("type" to "input_audio_buffer.clear", "event_id" to action.eventId))
            is VoiceTutorPauseCoordinator.Action.Acknowledge -> publish(client, json(mapOf(
                "type" to Contract.PAUSE_STATE_EVENT, "sequence" to action.sequence, "paused" to action.paused,
            )))
        } }
    }
    private fun emit(event: Map<String, Any?>) = publish(provider, json(mapOf("event_id" to "buddystudy-internal-native-${UUID.randomUUID()}") + event))
    private fun json(value: Any): String = mapper.writeValueAsString(value)
    private fun fail(error: Throwable) { failure.tryEmitValue(error) }
    private fun <T : Any> publish(sink: Sinks.Many<T>, value: T) {
        if (sink.tryEmitNext(value).isFailure && !closed) fail(VoiceTutorProviderProtocolException())
    }
    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 191
    private fun trim(set: LinkedHashSet<String>, limit: Int) { while (set.size > limit) set.remove(set.first()) }
    private fun <T : Any> sink(): Sinks.Many<T> = Sinks.many().unicast().onBackpressureBuffer(Queues.get<T>(512).get())

    data class NativeTranscript(val itemId: String, val raw: String)
    private class TranscriptState(var complete: Boolean = false)
    private data class Tutor(val id: String, val stoppedOrder: Long, val generation: Long)
    private data class Input(val id: String, val sequence: Long, val revision: Long, val startedOrder: Long,
        val acceptedAt: Instant, val precedingTutor: Tutor?, var committed: Boolean = false, var transcriptionFailed: Boolean = false)
    private data class SpeechBoundary(val sequence: Long, val revision: Long, val startedOrder: Long,
        val acceptedAt: Instant, val precedingTutor: Tutor?, var committedAt: Long,
        val commitEventId: String = "buddystudy-internal-native-commit-${UUID.randomUUID()}")
    private data class TutorTranscript(val sequence: Long, val acceptedAt: Instant, var raw: String? = null)
    private data class ToolBoundary(val boundary: VoiceTutorDialogueBoundary, val revision: Long)
    private class Response(val token: String, val generation: Long, val revision: Long, val startedAt: Long,
        val quota: Boolean, val boundary: VoiceTutorDialogueBoundary) {
        var id: String? = null
        var createdRaw: String? = null
        var announced = false
        var body: JsonNode? = null
        var done = false
        var failed = false
        var audioStarted = false
        var audioStopped = false
        var expectsAudio = false
        var deviceDrained = false
        var cancellationRequested = false
        val tutors = linkedMapOf<String, TutorTranscript>()
    }

    companion object {
        const val END_CALL_TOOL = "end_voice_conversation"
        val endCallDefinition = VoiceTutorMcpToolDefinition(END_CALL_TOOL,
            "End this voice call only when the learner asks to end or hang up, not for a pause or a topic change. Say one brief goodbye after success.",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false))
    }
}
