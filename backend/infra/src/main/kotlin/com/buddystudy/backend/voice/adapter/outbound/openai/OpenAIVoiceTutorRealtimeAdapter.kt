package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import reactor.util.concurrent.Queues
import java.time.Duration
import java.util.ArrayDeque
import java.util.Base64
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeoutException

@Component
class OpenAIVoiceTutorRealtimeAdapter(
    private val properties: BuddyStudyProperties,
    private val inputAssessment: VoiceTutorInputAssessmentUseCase,
    private val inputAssessmentProperties: VoiceTutorInputAssessmentProperties = VoiceTutorInputAssessmentProperties(),
) : VoiceTutorRealtimePort {
    private val mapper = JsonMapperProvider.mapper
    private val client = ReactorNettyWebSocketClient(
        HttpClient.create().responseTimeout(
            Duration.ofSeconds(properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300)),
        ),
        {
            WebsocketClientSpec.builder()
                .maxFramePayloadLength(MAX_PROVIDER_FRAME_BYTES)
        },
    )

    internal fun createLegacyTurnController(): VoiceTutorDuplexTurnController = VoiceTutorDuplexTurnController(
        mapper = mapper,
        continuousSpeechLimit = Duration.ofSeconds(
            properties.voiceTutor.continuousSpeechInterventionSeconds.coerceIn(5, 30),
        ),
        responseTimeout = Duration.ofSeconds(
            properties.voiceTutor.responseTimeoutSeconds.coerceIn(10, 120),
        ),
        transport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY,
        inputCoordinator = VoiceTutorInputTurnCoordinator(limits = inputAssessmentProperties),
    )

    internal fun legacyInputAssessmentRelay(
        controller: VoiceTutorDuplexTurnController,
        request: VoiceTutorRealtimeRequest,
        onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
    ): Mono<Void> = voiceTutorInputAssessmentRelay(
        controller = controller,
        userId = request.userId,
        language = request.language,
        assessment = inputAssessment,
        onProviderEvent = onProviderEvent,
    )

    override suspend fun relay(
        request: VoiceTutorRealtimeRequest,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Boolean,
    ) {
        val providerUri = UriComponentsBuilder.fromUriString(OPENAI_REALTIME_URL)
            .queryParam("model", request.model)
            .build(true)
            .toUri()
        val headers = HttpHeaders().apply {
            setBearerAuth(properties.openai.userContentApiKey)
            set("OpenAI-Beta", "realtime=v1")
            set(
                "OpenAI-Safety-Identifier",
                VoiceTutorSafetyIdentifier.create(request.userId, properties.openai.userContentApiKey),
            )
        }
        client.execute(providerUri, headers) { providerSession ->
            val drainState = VoiceTutorProviderDrainState(mapper)
            val turnController = createLegacyTurnController()
            val clientEventFlux = clientEvents.asFlux()
                .handle<String> { raw, sink ->
                    if (!turnController.observeClientEvent(raw)) {
                        sink.next(raw)
                    }
                }
                .doFinally { turnController.close() }
            val terminal = terminalEvents.asFlux()
                .next()
                .doOnNext { turnController.close() }
                .cache()
            val liveEvents = Flux.merge(clientEventFlux, turnController.providerEvents())
                .takeUntilOther(terminal)
                .doOnNext(drainState::observeClientEvent)
            val terminalProviderEvents = terminal.flatMapMany { termination ->
                if (termination.cancelActiveResponse) {
                    Flux.just(responseCancelEvent("relay-terminal"))
                } else {
                    Flux.empty()
                }
            }
            val outbound = Flux.concat(
                Mono.just(sessionUpdate(request)),
                liveEvents,
                terminalProviderEvents,
                Mono.defer {
                    val commit = drainState.beginDrain()
                    if (commit == null) Mono.empty<String>() else Mono.just(commit)
                },
            )
            val send = providerSession.send(
                outbound.map(providerSession::textMessage),
            )
            val receive = providerSession.receive()
                .filter { it.type == WebSocketMessage.Type.TEXT }
                .map { it.payloadAsText }
                .concatMap { raw ->
                    val disposition = turnController.observeProviderEvent(raw)
                    mono {
                        onProviderEvent(
                            turnController.providerEventForRelay(raw),
                            disposition.persist,
                            disposition.forwardToClient,
                        )
                    }.thenReturn(raw)
                }
                .doOnNext(drainState::observeProviderEvent)
                .then()
            val sendThenDrain = send
                .then(Mono.defer { drainState.awaitDrain(PROVIDER_DRAIN_GRACE) })
                .then(Mono.defer { providerSession.close() })
            val serverLifecycle = turnController.serverLifecycleEvents().concatMap { raw ->
                mono { onProviderEvent(raw, false, false) }.then()
            }.then()
            val inputWork = legacyInputAssessmentRelay(turnController, request, onProviderEvent)

            Mono.firstWithSignal(
                receive,
                sendThenDrain,
                turnController.inputFailure(),
                inputWork,
                serverLifecycle,
            ).then()
        }.awaitSingleOrNull()
    }

    internal fun sessionUpdate(request: VoiceTutorRealtimeRequest): String = mapper.writeValueAsString(
        mapOf(
            "type" to "session.update",
            "session" to mapOf(
                "type" to "realtime",
                "model" to request.model,
                "instructions" to request.instructions,
                "output_modalities" to listOf("audio"),
                "audio" to mapOf(
                    "input" to mapOf(
                        "format" to mapOf(
                            "type" to "audio/pcm",
                            "rate" to 24_000,
                        ),
                        "transcription" to voiceTutorInputTranscription(request.language),
                        "turn_detection" to mapOf(
                            "type" to "server_vad",
                            "create_response" to false,
                            "interrupt_response" to false,
                        ),
                    ),
                    "output" to mapOf(
                        "format" to mapOf(
                            "type" to "audio/pcm",
                            "rate" to 24_000,
                        ),
                        "voice" to request.voice,
                    ),
                ),
            ),
        ),
    )

    internal fun responseCancelEvent(action: String): String = mapper.writeValueAsString(
        linkedMapOf(
            "event_id" to "buddystudy-internal-${action.take(32).ifBlank { "cancel" }}-${UUID.randomUUID()}",
            "type" to "response.cancel",
        ),
    )

    private companion object {
        const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val MAX_PROVIDER_FRAME_BYTES = 65_536
        val PROVIDER_DRAIN_GRACE: Duration = Duration.ofSeconds(2)
    }
}

internal enum class VoiceTutorRealtimeTransport {
    LEGACY_PCM_RELAY,
    WEBRTC_SIDEBAND,
}

internal class VoiceTutorDuplexTurnController(
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = JsonMapperProvider.mapper,
    private val continuousSpeechLimit: Duration,
    private val responseTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
    private val transport: VoiceTutorRealtimeTransport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY,
    private val inputCoordinator: VoiceTutorInputTurnCoordinator? = null,
    toolsEnabled: Boolean = false,
    initialLessonRevision: Long = 0,
) {
    init {
        require(initialLessonRevision >= 0) { "Voice Tutor lesson revision was invalid." }
    }

    private var currentLessonRevision = initialLessonRevision
    private var activeResponseLessonRevision = initialLessonRevision
    private var activeSpeechLessonRevision: Long? = null
    private val responseLessonRevisions = linkedMapOf<String, Long>()
    private val inputLessonBindings = linkedMapOf<String, InputLessonBinding>()
    private val legacyInputSequences = linkedMapOf<String, Long>()
    private var activeSpeechStartedOrder = 0L
    private var activeSpeechPrecedingTutorStopOrder = 0L
    private var activeSpeechPrecedingSpokenGeneration = 0L
    private var activeSpeechPrecedingTutorProviderItemId: String? = null
    private var activeSpeechPrecedingTutorFeedbackForStudyAnswer = false
    private var activeSpeechPrecedingQuestionProviderItemId: String? = null
    private var activeSpeechPrecedingAnswerProviderItemId: String? = null
    private var activeSpeechPrecedingTutorFeedbackProviderItemId: String? = null
    private var activeSpeechPrecedingTutorNavigationOfferProviderItemId: String? = null
    private var dialogueEventOrder = 0L
    private var latestAcceptedInputBinding: InputLessonBinding? = null
    private var latestFocusIntentBinding: InputLessonBinding? = null
    private var nextTargetOfferId = 1L
    private var candidateDiscoveryGraph: CandidateDiscoveryGraph? = null
    private var activeResponseCandidateDiscovery: CandidateOfferPool? = null
    private var activeTargetOffer: VoiceTutorStudyTargetOffer? = null
    private var activeTargetOfferExchangeEvidence: TargetOfferExchangeEvidence? = null
    private var activeSpeechTargetOffer: VoiceTutorStudyTargetOffer? = null
    private val toolDiscoveryFences = linkedMapOf<String, ToolDiscoveryFence>()
    private var lastTutorSpeechStoppedOrder = 0L
    private var lastSpokenResponseGeneration = 0L
    private var lastSpokenTutorProviderItemId: String? = null
    private var lastSpokenTutorProviderItemGeneration = 0L
    private var lastSpokenStudyAnswer: StudyAnswerIdentity? = null
    private var completedStudyAnswerFeedback: StudyAnswerFeedbackEvidence? = null
    private var pendingNavigationFeedback: StudyAnswerFeedbackEvidence? = null
    private var activeResponseRespondsToStudyAnswer = false
    private var activeResponseStudyAnswer: StudyAnswerIdentity? = null
    private var activeResponseTutorContext = ""
    private val controls = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(MAX_BUFFERED_CONTROLS).get())
    private val inputWork = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<VoiceTutorInputTurnCoordinator.Action>(MAX_BUFFERED_CONTROLS).get())
    private val clientControls = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(MAX_BUFFERED_CONTROLS).get())
    private val serverLifecycle = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(1).get())
    private val pauseCoordinator = if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
        VoiceTutorPauseCoordinator(responseTimeout)
    } else {
        null
    }
    private var pauseTimer: Disposable? = null
    private val toolWork = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<VoiceTutorMcpCall>(VoiceTutorMcpTurnCoordinator.MAX_CALLS_PER_RESPONSE).get())
    private val toolCoordinator = if (toolsEnabled && transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
        VoiceTutorMcpTurnCoordinator(mapper)
    } else {
        null
    }
    private var toolAcknowledgementTimer: Disposable? = null
    private val terminalInputFailure = Sinks.one<Throwable>()
    /** Assessment evidence is frozen here; an async persistence receipt carries only item identity. */
    private val pendingInputPublications = linkedMapOf<String, VoiceTutorInputTurnCoordinator.Action.Publish>()
    private val pendingLessonEndPublications = linkedMapOf<String, Long>()
    private var inputAssessmentTimer: Disposable? = null
    private var inputCheckpointSequence: Long? = null
    private var lastInputCommitNanos: Long? = null
    private var delayedStopCommitTimer: Disposable? = null
    private var delayedStopCommit: PendingStopCommit? = null
    /** A learner edge can follow server playout stop before response.done supplies exact tutor identity. */
    private var speechAwaitingTutorFinalizationGeneration: Long? = null
    private var stopAwaitingTutorFinalization: PendingStopCommit? = null
    private val activeTutorTranscripts = linkedMapOf<String, String>()
    private val activeTutorTranscriptItemIds = linkedSetOf<String>()
    private var inputCheckpointTimer: Disposable? = null
    private var inputCheckpointTimerGeneration = 0L
    private var userSpeaking = false
    private var responseActive = false
    private var activeResponseGeneration = 0L
    private var activeResponseCreateEventId: String? = null
    private var activeResponseId: String? = null
    private var activeResponseAllowsTools = false
    private var activeResponseAudioBytes = 0L
    private var earliestResponsePlaybackEndNanos: Long? = null
    private var providerResponseDone = false
    private var playbackCompleted = false
    private var providerOutputBufferStopped = false
    private var providerOutputBufferStarted = false
    private var providerAudioObserved = false
    private var toolOnlyResponse = false
    private var playbackTimer: Disposable? = null
    private var responseTimer: Disposable? = null
    private var openingResponseRequested = false
    private var openingResponsePending = false
    private var queuedCommittedTurn = false
    private var pendingSpeechCommitCount = 0
    private var lastClientSpeechSequence = 0L
    private var activeClientSpeechSequence: Long? = null
    private val pendingInputCommits = ArrayDeque<PendingInputCommit>()
    private val recentCommittedItemIds = LinkedHashSet<String>()
    private var inputCommitTimer: Disposable? = null
    private var inputCheckpointDue = false
    private var spokenLessonEndRequested = false
    private var pendingSpokenLessonEnd: PendingSpokenLessonEnd? = null
    private var spokenLessonEndLifecycleEmitted = false
    @Volatile
    private var closed = false

    fun providerEvents(): Flux<String> = controls.asFlux().filter { !closed }

    fun inputActions(): Flux<VoiceTutorInputTurnCoordinator.Action> = inputWork.asFlux().filter { !closed }

    fun clientEvents(): Flux<String> = clientControls.asFlux().filter { !closed }

    fun serverLifecycleEvents(): Flux<String> = serverLifecycle.asFlux().filter { !closed }

    fun toolActions(): Flux<VoiceTutorMcpCall> = toolWork.asFlux().filter { !closed }

    @Synchronized
    fun mutationDialogueBoundary(): VoiceTutorDialogueBoundary = VoiceTutorDialogueBoundary(
        responseGeneration = activeResponseGeneration,
        latestAcceptedLearnerSpeechStartedOrder = latestAcceptedInputBinding?.speechStartedOrder ?: 0,
        precedingTutorSpeechStoppedOrder = latestAcceptedInputBinding?.precedingTutorSpeechStoppedOrder ?: 0,
        precedingSpokenResponseGeneration = latestAcceptedInputBinding?.precedingSpokenResponseGeneration ?: 0,
        latestAcceptedLearnerProviderItemId = latestFocusIntentBinding?.providerItemId,
        latestAcceptedLearnerLessonRevision = latestFocusIntentBinding?.lessonRevision ?: -1,
        latestAcceptedLearnerIntent = latestFocusIntentBinding?.inputIntent ?: VoiceTutorInputIntent.NONE,
        precedingTutorFeedbackForStudyAnswer =
            latestFocusIntentBinding?.precedingTutorFeedbackForStudyAnswer ?: false,
        precedingQuestionProviderItemId = latestFocusIntentBinding?.precedingQuestionProviderItemId,
        precedingAnswerProviderItemId = latestFocusIntentBinding?.precedingAnswerProviderItemId,
        precedingTutorFeedbackProviderItemId = latestFocusIntentBinding?.precedingTutorFeedbackProviderItemId,
        precedingTutorNavigationOfferProviderItemId =
            latestFocusIntentBinding?.precedingTutorNavigationOfferProviderItemId,
        latestAcceptedLearnerTargetStudyId = latestFocusIntentBinding?.targetStudyId,
        latestAcceptedLearnerTargetOfferId = latestFocusIntentBinding?.targetOfferId,
        latestAcceptedLearnerTargetCandidate = latestFocusIntentBinding?.let { binding ->
            binding.targetStudyId?.let { target ->
                binding.targetOffer?.candidates?.singleOrNull { it.studyId == target }
            }
        },
        latestAcceptedLearnerTargetTraversal = latestFocusIntentBinding?.let { binding ->
            binding.targetStudyId?.let { target -> binding.targetOffer?.candidateTraversals?.get(target) }
        },
        focusAuthorization = latestFocusIntentBinding?.focusAuthorization?.takeIf { it.isActive() },
    )

    @Synchronized
    fun beginToolExecution(callId: String): Boolean {
        if (closed) return false
        val coordinator = toolCoordinator ?: return false
        if (!coordinator.beginExecution(callId)) return false
        val toolName = coordinator.startedToolName(callId) ?: return false
        candidateReadKind(toolName)?.let {
            toolDiscoveryFences[callId] = ToolDiscoveryFence(
                toolName = toolName,
                lessonRevision = currentLessonRevision,
                learnerSpeechSequence = lastClientSpeechSequence,
                responseGeneration = activeResponseGeneration,
            )
        }
        return true
    }

    @Synchronized
    fun completeToolExecution(callId: String, result: VoiceTutorMcpToolResult): Boolean {
        if (closed) return false
        val event = try {
            toolCoordinator?.complete(callId, result, nanoTime()) ?: return false
        } catch (error: Exception) {
            terminate(error)
            return false
        }
        val discoveryFence = toolDiscoveryFences.remove(callId)
        result.candidateDiscovery?.takeIf { discovery ->
            !result.isError && discoveryFence != null &&
                candidateReadKind(discoveryFence.toolName) == discovery.source &&
                discovery.lessonRevision == discoveryFence.lessonRevision &&
                discovery.lessonRevision == currentLessonRevision &&
                discoveryFence.learnerSpeechSequence == lastClientSpeechSequence &&
                activeClientSpeechSequence == null &&
                discoveryFence.responseGeneration == activeResponseGeneration
        }?.let(::mergePendingCandidateDiscovery)
        if (!result.isError) {
            // Only a matched, server-executed result advances future responses.
            // Existing response IDs and speech/commit slots keep their old epoch.
            result.lessonRevision?.takeIf { it >= 0 }?.let { revision ->
                if (revision != currentLessonRevision) {
                    latestFocusIntentBinding?.focusAuthorization?.invalidate()
                    latestFocusIntentBinding = null
                    clearTargetDiscovery()
                }
                currentLessonRevision = maxOf(currentLessonRevision, revision)
            }
        }
        emit(event)
        if (!closed && toolAcknowledgementTimer == null) {
            toolAcknowledgementTimer = Flux.interval(INPUT_DEADLINE_POLL_INTERVAL)
                .subscribe { expireToolAcknowledgements() }
        }
        return !closed
    }

    private fun candidateReadKind(toolName: String): VoiceTutorCandidateReadKind? = when (toolName) {
        "list_studies" -> VoiceTutorCandidateReadKind.LIST_STUDIES
        "get_study" -> VoiceTutorCandidateReadKind.GET_STUDY
        else -> null
    }

    private fun mergePendingCandidateDiscovery(discovery: VoiceTutorCandidateDiscovery) {
        if (!validCandidateDiscovery(discovery)) return
        val graph = candidateDiscoveryGraph ?: CandidateDiscoveryGraph(
            lessonRevision = discovery.lessonRevision,
            currentFocusStudyId = discovery.currentFocusStudyId,
        ).also { candidateDiscoveryGraph = it }
        if (!graph.merge(discovery)) candidateDiscoveryGraph = null
    }

    private fun validCandidateDiscovery(discovery: VoiceTutorCandidateDiscovery): Boolean {
        if (discovery.lessonRevision < 0 || discovery.currentFocusStudyId?.let { it <= 0 } == true ||
            discovery.candidates.size !in 0..MAX_TARGET_OFFER_CANDIDATES ||
            discovery.candidates.map { it.studyId }.distinct().size != discovery.candidates.size ||
            discovery.candidates.any { candidate ->
                candidate.studyId <= 0 || candidate.parentStudyId?.let { it <= 0 } == true ||
                    candidate.topic.isBlank() || candidate.topic.length > 255
            }
        ) return false
        return when (val scope = discovery.scope) {
            is VoiceTutorCandidateDiscoveryScope.ExactStudy ->
                discovery.source == VoiceTutorCandidateReadKind.GET_STUDY &&
                    discovery.candidates.singleOrNull()?.studyId == scope.requestedStudyId
            is VoiceTutorCandidateDiscoveryScope.CompleteQueryPage ->
                discovery.source == VoiceTutorCandidateReadKind.LIST_STUDIES && scope.query.isNotBlank() &&
                    validCandidatePage(scope.offset, scope.limit, scope.totalCount, discovery.candidates.size)
            is VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage ->
                discovery.source == VoiceTutorCandidateReadKind.LIST_STUDIES && scope.parentStudyId > 0 &&
                    validCandidatePage(scope.offset, scope.limit, scope.totalCount, discovery.candidates.size) &&
                    discovery.candidates.all { it.parentStudyId == scope.parentStudyId }
            VoiceTutorCandidateDiscoveryScope.Unscoped -> false
        }
    }

    private fun validCandidatePage(offset: Long, limit: Long, totalCount: Long, size: Int): Boolean {
        if (limit !in 1..MAX_DISCOVERY_PAGE_LIMIT || totalCount !in 0..MAX_DISCOVERY_RESULT_CANDIDATES ||
            offset < 0 || offset % limit != 0L || (totalCount == 0L && offset != 0L) ||
            (totalCount > 0L && offset >= totalCount)
        ) return false
        return size.toLong() == minOf(limit, totalCount - offset)
    }

    private fun clearTargetDiscovery() {
        candidateDiscoveryGraph = null
        activeResponseCandidateDiscovery = null
        activeTargetOffer = null
        activeTargetOfferExchangeEvidence = null
        activeSpeechTargetOffer = null
        toolDiscoveryFences.clear()
    }

    @Synchronized
    internal fun expireToolAcknowledgements() {
        if (closed) return
        try {
            toolCoordinator?.expire(nanoTime())
        } catch (error: VoiceTutorMcpOutputAcknowledgementException) {
            terminate(error)
        }
    }

    fun inputFailure(): Mono<Void> = terminalInputFailure.asMono().flatMap { Mono.error(it) }

    @Synchronized
    fun acceptsInputEvents(): Boolean = !closed && !spokenLessonEndRequested

    @Synchronized
    fun canPublishInput(itemId: String): Boolean = !closed &&
        itemId in pendingInputPublications && inputCoordinator?.isPublicationPending(itemId) == true

    /** Called only after the controller accepted a final transcript for relay/persistence. */
    @Synchronized
    fun providerEventForRelay(raw: String): String {
        val node = mapper.readTree(raw)
        val revision = when (node.path("type").asText()) {
            "response.output_audio_transcript.done" -> responseLessonRevisions[node.path("response_id").asText()]
                ?: -1L
            "conversation.item.input_audio_transcription.completed" ->
                inputLessonBindings[node.path("item_id").asText()]?.lessonRevision
                    ?: if (inputCoordinator == null && currentLessonRevision == 0L) 0L else -1L
            else -> return raw
        }
        // Ignore an upstream field with this name. The response/commit mapping,
        // not a provider payload or the later ASR completion time, is authority.
        // Missing provenance preserves the original transcript as unassigned;
        // it must neither terminate a call nor falsely revert a lesson to zero.
        (node as com.fasterxml.jackson.databind.node.ObjectNode)
            .put(VoiceTutorTranscriptMetadata.LESSON_REVISION, revision)
        return mapper.writeValueAsString(node)
    }

    @Synchronized
    fun canAssessInput(token: Long): Boolean {
        if (closed) return false
        withInputCoordinator { expire(nanoTime()) }
        return !closed && inputCoordinator?.isAssessmentCurrent(token) == true
    }

    @Synchronized
    fun completeInputAssessment(token: Long, result: Result<VoiceTutorInputAssessmentResult>) {
        if (closed) return
        withInputCoordinator { completeAssessment(token, result, nanoTime()) }
    }

    @Synchronized
    fun confirmInputPublished(
        itemId: String,
        persisted: Boolean = false,
    ) {
        if (closed) return
        val publication = pendingInputPublications.remove(itemId) ?: return
        val binding = inputLessonBindings.remove(itemId)
        val lessonEndSequence = pendingLessonEndPublications.remove(itemId)
        val acceptedLessonEnd = persisted && lessonEndSequence != null &&
            lessonEndSequence == lastClientSpeechSequence && activeClientSpeechSequence == null
        if (acceptedLessonEnd) {
            pendingSpokenLessonEnd = PendingSpokenLessonEnd(
                speechSequence = lessonEndSequence,
                waitResponseGeneration = activeResponseGeneration.takeIf { responseActive },
            )
        }
        withInputCoordinator {
            confirmPublished(itemId, nanoTime()).also { actions ->
                val ready = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Ready>().singleOrNull()
                if (ready != null) {
                    // Consume a handled input exactly once even if storage
                    // is full or already contains it; this must not disconnect
                    // or re-assess/re-answer the same item. Mutation consent,
                    // however, requires a newly persisted exact USER item.
                    // Persistence can complete after a newer acoustic generation has
                    // already revoked this turn.  The immutable publication sequence,
                    // not callback arrival order, decides whether focus authority may
                    // be minted.  Keep the older accepted dialogue evidence for other
                    // confirmation flows, but never resurrect its one-shot focus lease.
                    val focusPublicationCurrent = publication.sequence == lastClientSpeechSequence &&
                        activeClientSpeechSequence == null
                    val focusTargetValid = focusPublicationCurrent && binding != null && validFocusTargetBinding(
                        binding, publication.intent, publication.targetStudyId, publication.targetOfferId,
                    )
                    latestFocusIntentBinding?.focusAuthorization?.invalidate()
                    val focusAuthorization = VoiceTutorFocusAuthorization().takeIf {
                        persisted && !ready.checkpoint && focusTargetValid
                    }
                    latestAcceptedInputBinding = if (persisted && !ready.checkpoint && binding != null) {
                        binding.copy(
                            providerItemId = itemId,
                            inputIntent = publication.intent,
                            targetStudyId = publication.targetStudyId.takeIf { focusTargetValid },
                            targetOfferId = publication.targetOfferId.takeIf { focusTargetValid },
                            focusAuthorization = focusAuthorization,
                        )
                    } else {
                        null
                    }
                    latestFocusIntentBinding = latestAcceptedInputBinding?.takeIf {
                        focusTargetValid && (it.inputIntent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                            it.inputIntent == VoiceTutorInputIntent.CONTINUE_TREE)
                    }
                }
            }
        }
        if (acceptedLessonEnd && !closed) {
            // The exact meaningful USER turn is durably accepted and is still
            // the newest learner generation. Never create another response. If
            // the tutor is already speaking, however, keep the call alive until
            // that exact response reaches response.done + output buffer stopped.
            if (!responseActive) {
                emitSpokenLessonEndLifecycle()
            }
        }
    }

    private fun validFocusTargetBinding(
        binding: InputLessonBinding,
        intent: VoiceTutorInputIntent,
        targetStudyId: Long?,
        targetOfferId: Long?,
    ): Boolean {
        if (intent != VoiceTutorInputIntent.SELECT_SAVED_TOPIC && intent != VoiceTutorInputIntent.CONTINUE_TREE) {
            return false
        }
        val target = targetStudyId?.takeIf { it > 0 } ?: return false
        val offer = binding.targetOffer ?: return false
        val candidate = offer.candidates.singleOrNull { it.studyId == target } ?: return false
        val traversal = offer.candidateTraversals[target] ?: return false
        if (targetOfferId == null || targetOfferId <= 0 || targetOfferId != offer.offerId ||
            offer.lessonRevision != binding.lessonRevision ||
            offer.tutorResponseGeneration != binding.precedingSpokenResponseGeneration ||
            offer.tutorSpeechStoppedOrder != binding.precedingTutorSpeechStoppedOrder ||
            offer.candidateTraversals.keys != offer.candidates.mapTo(linkedSetOf()) { it.studyId } ||
            !traversal.isValidFor(candidate, MAX_DISCOVERY_TREE_DEPTH)
        ) return false
        return intent != VoiceTutorInputIntent.CONTINUE_TREE ||
            offer.currentFocusStudyId?.let { focus ->
                candidate.parentStudyId == focus
            } == true
    }

    private fun emitSpokenLessonEndLifecycle() {
        val pending = pendingSpokenLessonEnd ?: return
        if (
            closed || spokenLessonEndLifecycleEmitted || pending.speechSequence != lastClientSpeechSequence ||
            activeClientSpeechSequence != null
        ) return
        spokenLessonEndRequested = true
        spokenLessonEndLifecycleEmitted = true
        pendingSpokenLessonEnd = null
        queuedCommittedTurn = false
        inputCoordinator?.close()
        pendingInputPublications.clear()
        pendingLessonEndPublications.clear()
        val result = serverLifecycle.tryEmitNext(
            mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)),
        )
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            terminate(IllegalStateException("Voice Tutor server lifecycle buffer overflowed."))
        }
    }

    /** All coordinator transitions, including timer completions, own this lock. */
    @Synchronized
    internal fun expirePendingInput() {
        if (closed) return
        withInputCoordinator { expire(nanoTime()) }
    }

    private fun withInputCoordinator(
        transition: VoiceTutorInputTurnCoordinator.() -> List<VoiceTutorInputTurnCoordinator.Action>,
    ) {
        val coordinator = inputCoordinator ?: return
        val actions = try {
            coordinator.transition()
        } catch (error: VoiceTutorInputTurnCoordinatorException) {
            // A missing cleanup/persistence ACK is finite and visible; do not
            // fall back to responding to unassessed audio or wait indefinitely.
            terminate(error)
            return
        }
        for (action in actions) {
            when (action) {
                is VoiceTutorInputTurnCoordinator.Action.Delete -> emit(
                    linkedMapOf(
                        "event_id" to internalEventId("input-delete"),
                        "type" to "conversation.item.delete",
                        "item_id" to action.itemId,
                    ),
                )
                is VoiceTutorInputTurnCoordinator.Action.Ready -> {
                    // A meaningful checkpoint contributes context, not permission
                    // to speak over the learner. The normal response gate owns that.
                    queuedCommittedTurn = true
                }
                else -> {
                    if (action is VoiceTutorInputTurnCoordinator.Action.Publish) {
                        pendingInputPublications[action.itemId] = action
                        // A long-speech checkpoint is incomplete by definition:
                        // later words can negate, quote or make it hypothetical.
                        if (
                            !action.checkpoint &&
                            action.intent == VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON &&
                            action.sequence == lastClientSpeechSequence
                        ) {
                            pendingLessonEndPublications[action.itemId] = action.sequence
                        }
                    }
                    val emitted = inputWork.tryEmitNext(action)
                    if (emitted.isFailure && !closed) {
                        terminate(VoiceTutorPendingInputCommitOverflowException())
                        return
                    }
                }
            }
        }
        if (coordinator.hasPending) {
            if (inputAssessmentTimer == null) {
                inputAssessmentTimer = Flux.interval(INPUT_DEADLINE_POLL_INTERVAL)
                    .subscribe { expirePendingInput() }
            }
        } else {
            inputAssessmentTimer?.dispose()
            inputAssessmentTimer = null
            if (userSpeaking && inputCheckpointDue) {
                fireContinuousSpeechDeadline()
            }
            createNormalResponseIfReady()
        }
    }

    @Synchronized
    fun startOpeningResponse() {
        if (closed || openingResponseRequested) return
        openingResponseRequested = true
        openingResponsePending = true
        createNormalResponseIfReady()
    }

    @Synchronized
    fun observeClientEvent(raw: String): Boolean {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return false
        return when (node.path("type").asText()) {
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT -> {
                if (!closed && transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    clientSpeechSequence(node)?.let(::observeClientSpeechStarted)
                }
                // These notifications control the server's turn state only.
                // Neither their type nor their client-supplied fields go upstream.
                true
            }
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
            -> {
                val pause = pauseCoordinator
                val sequence = clientSpeechSequence(node)
                if (closed || pause == null || sequence == null) return true
                when (node.path("type").asText()) {
                    VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT -> {
                        applyPauseActions(pause.requestPause(sequence, nanoTime()))
                        if (pause.blocksResponses) {
                            cancelInputCheckpointTimer()
                            inputCheckpointDue = false
                        }
                    }
                    VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT -> pause.confirmInputQuiesced(sequence)
                    VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT ->
                        applyPauseActions(pause.requestResume(sequence, nanoTime()))
                }
                advancePause()
                true
            }
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT -> {
                if (!closed && transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    clientSpeechSequence(node)?.let(::observeClientSpeechStopped)
                }
                true
            }
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT -> {
                if (closed || transport != VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY) return true
                val responseId = node.path("responseId").asText()
                if (responseActive && responseId.isNotBlank() && responseId == activeResponseId) {
                    playbackCompleted = true
                    if (providerResponseDone) {
                        advancePlaybackGate()
                    }
                }
                true
            }
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT -> {
                // Older iOS clients report an inferred PCM quiet period here.
                // NetEq may keep rendering comfort noise after real audio ends,
                // so this is optional compatibility telemetry, never a turn gate.
                true
            }
            else -> false
        }
    }

    @Synchronized
    fun observeProviderEvent(raw: String): VoiceTutorProviderRelayDisposition {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return VoiceTutorProviderRelayDisposition.DROP
        if (closed) {
            return terminalDisposition(node)
        }
        return when (node.path("type").asText()) {
            "session.updated" -> {
                if (transport == VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY) {
                    startOpeningResponse()
                }
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "response.output_audio.delta" -> if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                val matches = matchesKnownActiveResponse(node.path("response_id").asText())
                if (matches) providerAudioObserved = true
                accepted(
                    matches,
                    VoiceTutorProviderRelayDisposition.PERSIST_ONLY,
                )
            } else {
                accepted(observeResponseAudio(node))
            }
            "response.output_audio.done" -> accepted(matchesActiveResponse(node.path("response_id").asText()))
            "output_audio_buffer.started" -> {
                val matches = transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
                    matchesKnownActiveResponse(node.path("response_id").asText())
                if (matches) providerOutputBufferStarted = true
                accepted(matches, VoiceTutorProviderRelayDisposition.FORWARD_ONLY)
            }
            "output_audio_buffer.stopped" -> accepted(
                observeOutputBufferStopped(node),
                VoiceTutorProviderRelayDisposition.FORWARD_ONLY,
            )
            "output_audio_buffer.cleared" -> observeOutputBufferCleared(node)
            in TUTOR_TRANSCRIPT_EVENTS -> {
                val matches = matchesKnownActiveResponse(node.path("response_id").asText())
                if (matches && node.path("type").asText() == "response.output_audio_transcript.done") {
                    rememberTutorTranscript(node)
                }
                accepted(matches)
            }
            in USER_TRANSCRIPT_EVENTS -> {
                if (inputCoordinator == null) return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                if (node.path("type").asText() == "conversation.item.input_audio_transcription.completed") {
                    val itemId = node.path("item_id").asText()
                    val transcript = node.path("transcript")
                    if (transcript.isTextual) {
                        withInputCoordinator { observeTranscript(itemId, transcript.textValue(), raw, nanoTime()) }
                    } else {
                        withInputCoordinator { observeTranscriptionFailure(itemId, nanoTime()) }
                    }
                }
                // Acoustic activity and partial ASR do not prove communicative
                // input. Only the separately assessed exact final item is replayed.
                VoiceTutorProviderRelayDisposition.DROP
            }
            "conversation.item.input_audio_transcription.failed" -> {
                if (inputCoordinator == null) return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                withInputCoordinator { observeTranscriptionFailure(node.path("item_id").asText(), nanoTime()) }
                VoiceTutorProviderRelayDisposition.DROP
            }
            "conversation.item.deleted" -> {
                if (inputCoordinator == null) return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                withInputCoordinator { confirmDeleted(node.path("item_id").asText(), nanoTime()) }
                inputLessonBindings.remove(node.path("item_id").asText())
                VoiceTutorProviderRelayDisposition.DROP
            }
            in VoiceTutorMcpTurnCoordinator.OUTPUT_ACK_EVENTS -> {
                if (toolCoordinator?.acknowledge(node, nanoTime()) == true) {
                    if (!toolCoordinator.hasPending) {
                        toolAcknowledgementTimer?.dispose()
                        toolAcknowledgementTimer = null
                    }
                    createNormalResponseIfReady()
                }
                // Tool arguments/results and arbitrary conversation items never
                // become transcripts or client events.
                VoiceTutorProviderRelayDisposition.DROP
            }
            "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.output_item.added", "response.output_item.done",
            -> VoiceTutorProviderRelayDisposition.DROP
            "input_audio_buffer.speech_started" -> {
                if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    return VoiceTutorProviderRelayDisposition.DROP
                }
                if (inputCoordinator == null) {
                    observeSpeechStarted()
                    rememberBoundedBinding(
                        inputLessonBindings,
                        node.path("item_id").asText(),
                        InputLessonBinding(activeSpeechLessonRevision ?: currentLessonRevision, activeSpeechStartedOrder),
                    )
                    VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                } else {
                    accepted(observeLegacySpeechStarted(node))
                }
            }
            "input_audio_buffer.speech_stopped" -> {
                if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    return VoiceTutorProviderRelayDisposition.DROP
                }
                if (inputCoordinator == null) {
                    observeSpeechStopped()
                    VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                } else {
                    accepted(observeLegacySpeechStopped(node))
                }
            }
            "input_audio_buffer.committed" -> {
                val commit = when (transport) {
                    VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND ->
                        acknowledgeInputCommit(node) ?: return VoiceTutorProviderRelayDisposition.DROP
                    VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY ->
                        if (inputCoordinator == null) null else acknowledgeLegacyInputCommit(node)
                }
                if (pendingSpeechCommitCount > 0) {
                    pendingSpeechCommitCount = (pendingSpeechCommitCount - (commit?.speechSlots ?: 1)).coerceAtLeast(0)
                }
                if (commit != null) {
                    rememberBoundedBinding(
                        inputLessonBindings,
                        node.path("item_id").asText(),
                        InputLessonBinding(
                            lessonRevision = commit.lessonRevision,
                            speechStartedOrder = commit.speechStartedOrder,
                            precedingTutorSpeechStoppedOrder = commit.precedingTutorSpeechStoppedOrder,
                            precedingSpokenResponseGeneration = commit.precedingSpokenResponseGeneration,
                            precedingTutorProviderItemId = commit.precedingTutorProviderItemId,
                            precedingTutorFeedbackForStudyAnswer = commit.precedingTutorFeedbackForStudyAnswer,
                            precedingQuestionProviderItemId = commit.precedingQuestionProviderItemId,
                            precedingAnswerProviderItemId = commit.precedingAnswerProviderItemId,
                            precedingTutorFeedbackProviderItemId = commit.precedingTutorFeedbackProviderItemId,
                            precedingTutorNavigationOfferProviderItemId =
                                commit.precedingTutorNavigationOfferProviderItemId,
                            targetOffer = commit.targetOffer,
                        ),
                    )
                }
                if (inputCoordinator != null) {
                    if (commit == null) {
                        terminate(VoiceTutorProviderInputCorrelationException())
                        return VoiceTutorProviderRelayDisposition.DROP
                    }
                    withInputCoordinator {
                        observeCommitted(
                            node.path("item_id").asText(), commit.sequence, commit.checkpoint, nanoTime(),
                            commit.targetOffer,
                        )
                    }
                } else {
                    queuedCommittedTurn = true
                    createNormalResponseIfReady()
                }
                if (
                    transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
                    pendingInputCommits.isEmpty() && userSpeaking &&
                    inputCheckpointDue
                ) {
                    fireContinuousSpeechDeadline()
                }
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "input_audio_buffer.cleared" -> {
                val pause = pauseCoordinator ?: return VoiceTutorProviderRelayDisposition.DROP
                try {
                    applyPauseActions(pause.acknowledgeClear(node.path("event_id").asText(), nanoTime()))
                    advancePause()
                    createNormalResponseIfReady()
                } catch (error: VoiceTutorPauseAcknowledgementTimeoutException) {
                    terminate(error)
                }
                VoiceTutorProviderRelayDisposition.DROP
            }
            "response.created" -> accepted(observeResponseCreated(node))
            "response.done" -> accepted(observeResponseDone(node))
            "error" -> if (observeEmptyInputCommit(node)) {
                VoiceTutorProviderRelayDisposition.DROP
            } else {
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            else -> VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
        }
    }

    /** Compatibility hook name: elapsed speech time only checkpoints transcription, never creates a response. */
    @Synchronized
    internal fun fireContinuousSpeechDeadline() {
        cancelInputCheckpointTimer()
        if (
            closed || pauseCoordinator?.blocksResponses == true || !openingResponseRequested || openingResponsePending ||
            !userSpeaking || transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND
        ) return
        val sequence = activeClientSpeechSequence ?: return
        if (speechAwaitingTutorFinalizationGeneration != null) {
            // response.done owns the exact tutor item/offer boundary. Do not
            // commit a checkpoint whose immutable binding would omit it.
            inputCheckpointDue = true
            return
        }
        if (pendingInputCommits.isNotEmpty() || inputCoordinator?.hasPending == true || delayedStopCommit != null) {
            // At most one deferred checkpoint, not a queue of timer-triggered work.
            // Existing commit/ASR/publication/deletion deadlines remain authoritative.
            inputCheckpointDue = true
            return
        }
        val sincePreviousCommit = lastInputCommitNanos?.let { (nanoTime() - it).coerceAtLeast(0) }
        if (sincePreviousCommit != null && sincePreviousCommit < MIN_INPUT_COMMIT_SPACING.toNanos()) {
            inputCheckpointDue = false
            scheduleInputCheckpoint(Duration.ofNanos(MIN_INPUT_COMMIT_SPACING.toNanos() - sincePreviousCommit))
            return
        }
        if (pendingSpeechCommitCount >= MAX_PENDING_SPEECH_COMMITS) {
            terminate(VoiceTutorPendingInputCommitOverflowException())
            return
        }
        inputCheckpointDue = false
        inputCheckpointSequence = sequence
        // Periodically commit the accumulated input for ASR without ending,
        // muting or granting away the learner's active speech turn. Its final
        // stop still owns a separate reserved tail slot and exact commit ACK.
        pendingSpeechCommitCount += 1
        requestInputCommit(sequence, checkpoint = true)
        scheduleInputCheckpoint()
    }

    fun close() = terminate(null)

    private fun terminate(error: Throwable?) {
        synchronized(this) {
            if (closed) return
            closed = true
            cancelInputCheckpointTimer()
            inputCheckpointDue = false
            playbackTimer?.dispose()
            playbackTimer = null
            responseTimer?.dispose()
            responseTimer = null
            inputCommitTimer?.dispose()
            inputCommitTimer = null
            inputAssessmentTimer?.dispose()
            inputAssessmentTimer = null
            delayedStopCommitTimer?.dispose()
            delayedStopCommitTimer = null
            delayedStopCommit = null
            speechAwaitingTutorFinalizationGeneration = null
            stopAwaitingTutorFinalization = null
            toolAcknowledgementTimer?.dispose()
            toolAcknowledgementTimer = null
            pauseTimer?.dispose()
            pauseTimer = null
            pauseCoordinator?.close()
            toolCoordinator?.close()
            inputCoordinator?.close()
            pendingInputPublications.clear()
            pendingLessonEndPublications.clear()
            pendingSpokenLessonEnd = null
            activeTutorTranscripts.clear()
            activeTutorTranscriptItemIds.clear()
            latestFocusIntentBinding?.focusAuthorization?.invalidate()
            latestFocusIntentBinding = null
            clearTargetDiscovery()
            pendingInputCommits.clear()
            recentCommittedItemIds.clear()
            legacyInputSequences.clear()
            activeClientSpeechSequence = null
            if (error == null) {
                controls.tryEmitComplete()
            } else {
                controls.tryEmitError(error)
                // A full/unrequested outbound queue delays its onError. This
                // separate signal ends the relay even under socket backpressure.
                terminalInputFailure.tryEmitValue(error)
            }
            inputWork.tryEmitComplete()
            toolWork.tryEmitComplete()
            clientControls.tryEmitComplete()
            serverLifecycle.tryEmitComplete()
        }
    }

    private fun clientSpeechSequence(node: com.fasterxml.jackson.databind.JsonNode): Long? {
        val sequence = node.path("sequence")
        return sequence.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue()?.takeIf { it > 0 }
    }

    private fun providerItemId(node: com.fasterxml.jackson.databind.JsonNode): String? =
        node.path("item_id").takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }

    private fun observeLegacySpeechStarted(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        val itemId = providerItemId(node) ?: throw VoiceTutorProviderInputCorrelationException()
        if (activeClientSpeechSequence != null || itemId in legacyInputSequences) return false
        if (lastClientSpeechSequence == Long.MAX_VALUE) throw VoiceTutorProviderInputCorrelationException()
        val sequence = lastClientSpeechSequence + 1
        lastClientSpeechSequence = sequence
        pendingLessonEndPublications.entries.removeAll { it.value < sequence }
        retractSpokenLessonEndBeforeLifecycle(sequence)
        activeClientSpeechSequence = sequence
        activeSpeechStartedOrder = nextDialogueEventOrder()
        val tutorOutputCompleted = !responseActive || playbackCompleted
        activeSpeechPrecedingTutorStopOrder = if (tutorOutputCompleted) lastTutorSpeechStoppedOrder else 0
        activeSpeechPrecedingSpokenGeneration = if (tutorOutputCompleted) lastSpokenResponseGeneration else 0
        observeSpeechStarted()
        rememberBoundedBinding(legacyInputSequences, itemId, sequence)
        rememberBoundedBinding(
            inputLessonBindings,
            itemId,
            InputLessonBinding(
                activeSpeechLessonRevision ?: currentLessonRevision,
                activeSpeechStartedOrder,
                activeSpeechPrecedingTutorStopOrder,
                activeSpeechPrecedingSpokenGeneration,
            ),
        )
        return true
    }

    private fun observeLegacySpeechStopped(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        val itemId = providerItemId(node) ?: throw VoiceTutorProviderInputCorrelationException()
        val sequence = legacyInputSequences[itemId] ?: return false
        if (activeClientSpeechSequence != sequence) return false
        activeClientSpeechSequence = null
        observeSpeechStopped()
        return true
    }

    private fun acknowledgeLegacyInputCommit(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): PendingInputCommit? {
        val itemId = providerItemId(node) ?: return null
        val sequence = legacyInputSequences.remove(itemId) ?: return null
        val binding = inputLessonBindings[itemId] ?: return null
        return PendingInputCommit(
            sequence = sequence,
            requestedAtNanos = nanoTime(),
            checkpoint = false,
            speechSlots = 1,
            lessonRevision = binding.lessonRevision,
            speechStartedOrder = binding.speechStartedOrder,
            precedingTutorSpeechStoppedOrder = binding.precedingTutorSpeechStoppedOrder,
            precedingSpokenResponseGeneration = binding.precedingSpokenResponseGeneration,
            precedingTutorProviderItemId = binding.precedingTutorProviderItemId,
            precedingTutorFeedbackForStudyAnswer = binding.precedingTutorFeedbackForStudyAnswer,
            precedingQuestionProviderItemId = binding.precedingQuestionProviderItemId,
            precedingAnswerProviderItemId = binding.precedingAnswerProviderItemId,
            precedingTutorFeedbackProviderItemId = binding.precedingTutorFeedbackProviderItemId,
            precedingTutorNavigationOfferProviderItemId =
                binding.precedingTutorNavigationOfferProviderItemId,
            targetOffer = binding.targetOffer,
        )
    }

    private fun observeClientSpeechStarted(sequence: Long) {
        if (sequence <= lastClientSpeechSequence) return
        // Revocation happens at the first fresh speech edge, even while pause
        // suppression prevents this audio from becoming an accepted utterance.
        latestFocusIntentBinding?.focusAuthorization?.invalidate()
        latestFocusIntentBinding = null
        if (pauseCoordinator?.acceptsSpeechEdges != true) {
            // Keep a high-water mark even for suppressed edges. Replaying an
            // old held start after resume cannot revive a discarded utterance.
            lastClientSpeechSequence = sequence
            pendingLessonEndPublications.entries.removeAll { it.value < sequence }
            retractSpokenLessonEndBeforeLifecycle(sequence)
            clearTargetDiscovery()
            return
        }
        if (activeClientSpeechSequence != null) return
        if (pendingSpeechCommitCount >= MAX_PENDING_SPEECH_COMMITS) {
            throw VoiceTutorPendingInputCommitOverflowException()
        }
        lastClientSpeechSequence = sequence
        pendingLessonEndPublications.entries.removeAll { it.value < sequence }
        retractSpokenLessonEndBeforeLifecycle(sequence)
        activeClientSpeechSequence = sequence
        activeSpeechStartedOrder = nextDialogueEventOrder()
        // This utterance can acknowledge only a question already finished when
        // it began. Freeze that boundary: later mixed tool/confirmation audio
        // must not invalidate a valid learner acknowledgement retroactively.
        val tutorOutputCompleted = !responseActive || providerOutputBufferStopped
        activeSpeechPrecedingTutorStopOrder = if (tutorOutputCompleted) lastTutorSpeechStoppedOrder else 0
        activeSpeechPrecedingSpokenGeneration = if (tutorOutputCompleted) lastSpokenResponseGeneration else 0
        activeSpeechPrecedingTutorProviderItemId = lastSpokenTutorProviderItemId
            ?.takeIf {
                tutorOutputCompleted && lastSpokenResponseGeneration > 0 &&
                    lastSpokenTutorProviderItemGeneration == lastSpokenResponseGeneration
            }
        val awaitsTutorFinalization = responseActive && providerOutputBufferStopped && !providerResponseDone
        speechAwaitingTutorFinalizationGeneration = activeResponseGeneration.takeIf { awaitsTutorFinalization }
        inputCheckpointSequence = null
        observeSpeechStarted(deferTutorEvidence = awaitsTutorFinalization)
    }

    private fun retractSpokenLessonEndBeforeLifecycle(sequence: Long) {
        pendingSpokenLessonEnd?.takeIf { sequence > it.speechSequence }?.let {
            pendingSpokenLessonEnd = null
            queuedCommittedTurn = true
        }
    }

    private fun observeClientSpeechStopped(sequence: Long) {
        if (activeClientSpeechSequence != sequence) return
        val lessonRevision = activeSpeechLessonRevision ?: currentLessonRevision
        val speechStartedOrder = activeSpeechStartedOrder
        val precedingTutorSpeechStoppedOrder = activeSpeechPrecedingTutorStopOrder
        val precedingSpokenResponseGeneration = activeSpeechPrecedingSpokenGeneration
        val precedingTutorProviderItemId = activeSpeechPrecedingTutorProviderItemId
        val precedingTutorFeedbackForStudyAnswer = activeSpeechPrecedingTutorFeedbackForStudyAnswer
        val precedingQuestionProviderItemId = activeSpeechPrecedingQuestionProviderItemId
        val precedingAnswerProviderItemId = activeSpeechPrecedingAnswerProviderItemId
        val precedingTutorFeedbackProviderItemId = activeSpeechPrecedingTutorFeedbackProviderItemId
        val precedingTutorNavigationOfferProviderItemId =
            activeSpeechPrecedingTutorNavigationOfferProviderItemId
        val targetOffer = activeSpeechTargetOffer
        activeClientSpeechSequence = null
        if (speechAwaitingTutorFinalizationGeneration != null) {
            var waiting = PendingStopCommit(
                sequence, sequence, 1, lessonRevision, speechStartedOrder,
                precedingTutorSpeechStoppedOrder, precedingSpokenResponseGeneration,
                precedingTutorProviderItemId, precedingTutorFeedbackForStudyAnswer,
                precedingQuestionProviderItemId,
                precedingAnswerProviderItemId, precedingTutorFeedbackProviderItemId,
                precedingTutorNavigationOfferProviderItemId,
                targetOffer,
            )
            delayedStopCommit?.let { beforeTutorStop ->
                // response.done may arrive after the old spacing timer. Merge
                // now so that timer cannot commit the shared native buffer with
                // only the pre-stop slot or authority.
                delayedStopCommitTimer?.dispose()
                delayedStopCommitTimer = null
                delayedStopCommit = null
                waiting = mergeMixedTutorBoundaryStopCommits(beforeTutorStop, waiting)
            }
            val existingWaiting = stopAwaitingTutorFinalization
            if (existingWaiting != null && existingWaiting.speechSlots >= MAX_PENDING_SPEECH_COMMITS) {
                throw VoiceTutorPendingInputCommitOverflowException()
            }
            stopAwaitingTutorFinalization = existingWaiting?.copy(
                lastSequence = sequence,
                speechSlots = existingWaiting.speechSlots + 1,
            ) ?: waiting
            observeSpeechStopped()
            return
        }
        observeSpeechStopped()
        val sincePreviousCommit = lastInputCommitNanos?.let { (nanoTime() - it).coerceAtLeast(0) }
        val alreadyDelayed = delayedStopCommit
        if (alreadyDelayed != null) {
            // Rapid mute/unmute can finish another utterance before this flush.
            // One native buffer contains all these segments: keep the first
            // deadline, correlate the latest sequence and settle every reserved
            // speech slot with that one commit ACK (or exact empty-buffer error).
            delayedStopCommit = alreadyDelayed.copy(
                lastSequence = sequence,
                speechSlots = alreadyDelayed.speechSlots + 1,
            )
            if (sincePreviousCommit == null || sincePreviousCommit >= MIN_INPUT_COMMIT_SPACING.toNanos()) {
                flushDelayedStopCommit(alreadyDelayed.firstSequence)
            } else if (delayedStopCommitTimer == null) {
                delayedStopCommitTimer = Mono.delay(
                    Duration.ofNanos(MIN_INPUT_COMMIT_SPACING.toNanos() - sincePreviousCommit),
                ).subscribe { flushDelayedStopCommit(alreadyDelayed.firstSequence) }
            }
            return
        }
        if (inputCoordinator != null && sincePreviousCommit != null &&
            sincePreviousCommit < MIN_INPUT_COMMIT_SPACING.toNanos()
        ) {
            // A speech stop immediately after a long-speech checkpoint must not
            // commit an empty tail. Keep its pending slot and flush once, shortly
            // after the checkpoint; microphone/RTP/output playback stay untouched.
            delayedStopCommit = PendingStopCommit(
                sequence, sequence, 1, lessonRevision, speechStartedOrder,
                precedingTutorSpeechStoppedOrder, precedingSpokenResponseGeneration,
                precedingTutorProviderItemId, precedingTutorFeedbackForStudyAnswer,
                precedingQuestionProviderItemId,
                precedingAnswerProviderItemId, precedingTutorFeedbackProviderItemId,
                precedingTutorNavigationOfferProviderItemId,
                targetOffer,
            )
            delayedStopCommitTimer = Mono.delay(
                Duration.ofNanos(MIN_INPUT_COMMIT_SPACING.toNanos() - sincePreviousCommit),
            ).subscribe { flushDelayedStopCommit(sequence) }
        } else {
            requestInputCommit(
                sequence,
                checkpoint = false,
                lessonRevision = lessonRevision,
                speechStartedOrder = speechStartedOrder,
                precedingTutorSpeechStoppedOrder = precedingTutorSpeechStoppedOrder,
                precedingSpokenResponseGeneration = precedingSpokenResponseGeneration,
                precedingTutorProviderItemId = precedingTutorProviderItemId,
                precedingTutorFeedbackForStudyAnswer = precedingTutorFeedbackForStudyAnswer,
                precedingQuestionProviderItemId = precedingQuestionProviderItemId,
                precedingAnswerProviderItemId = precedingAnswerProviderItemId,
                precedingTutorFeedbackProviderItemId = precedingTutorFeedbackProviderItemId,
                precedingTutorNavigationOfferProviderItemId =
                    precedingTutorNavigationOfferProviderItemId,
                targetOffer = targetOffer,
            )
        }
    }

    @Synchronized
    internal fun flushDelayedStopCommit(sequence: Long) {
        val pending = delayedStopCommit ?: return
        if (closed || pending.firstSequence != sequence) return
        if (activeClientSpeechSequence != null) {
            // A disposed spacing timer may already be queued. It must not
            // commit a buffer now containing a newer active speech segment.
            delayedStopCommitTimer?.dispose()
            delayedStopCommitTimer = null
            return
        }
        delayedStopCommit = null
        delayedStopCommitTimer?.dispose()
        delayedStopCommitTimer = null
        requestInputCommit(
            pending.lastSequence,
            checkpoint = false,
            speechSlots = pending.speechSlots,
            lessonRevision = pending.lessonRevision,
            speechStartedOrder = pending.speechStartedOrder,
            precedingTutorSpeechStoppedOrder = pending.precedingTutorSpeechStoppedOrder,
            precedingSpokenResponseGeneration = pending.precedingSpokenResponseGeneration,
            precedingTutorProviderItemId = pending.precedingTutorProviderItemId,
            precedingTutorFeedbackForStudyAnswer = pending.precedingTutorFeedbackForStudyAnswer,
            precedingQuestionProviderItemId = pending.precedingQuestionProviderItemId,
            precedingAnswerProviderItemId = pending.precedingAnswerProviderItemId,
            precedingTutorFeedbackProviderItemId = pending.precedingTutorFeedbackProviderItemId,
            precedingTutorNavigationOfferProviderItemId =
                pending.precedingTutorNavigationOfferProviderItemId,
            targetOffer = pending.targetOffer,
        )
    }

    private fun requestInputCommit(
        sequence: Long,
        checkpoint: Boolean,
        speechSlots: Int = 1,
        lessonRevision: Long = activeSpeechLessonRevision ?: currentLessonRevision,
        speechStartedOrder: Long = activeSpeechStartedOrder,
        precedingTutorSpeechStoppedOrder: Long = activeSpeechPrecedingTutorStopOrder,
        precedingSpokenResponseGeneration: Long = activeSpeechPrecedingSpokenGeneration,
        precedingTutorProviderItemId: String? = activeSpeechPrecedingTutorProviderItemId,
        precedingTutorFeedbackForStudyAnswer: Boolean = activeSpeechPrecedingTutorFeedbackForStudyAnswer,
        precedingQuestionProviderItemId: String? = activeSpeechPrecedingQuestionProviderItemId,
        precedingAnswerProviderItemId: String? = activeSpeechPrecedingAnswerProviderItemId,
        precedingTutorFeedbackProviderItemId: String? = activeSpeechPrecedingTutorFeedbackProviderItemId,
        precedingTutorNavigationOfferProviderItemId: String? =
            activeSpeechPrecedingTutorNavigationOfferProviderItemId,
        targetOffer: VoiceTutorStudyTargetOffer? = activeSpeechTargetOffer,
    ) {
        if (closed) return
        // Register before emitting: an immediate provider ACK must find the
        // outstanding commit, while a later start has its own pending count.
        val requestedAt = nanoTime()
        val eventId = internalEventId(if (checkpoint) "input-checkpoint" else "input-commit")
        pendingInputCommits.addLast(PendingInputCommit(
            sequence, requestedAt, checkpoint, eventId, speechSlots, lessonRevision, speechStartedOrder,
            precedingTutorSpeechStoppedOrder, precedingSpokenResponseGeneration,
            precedingTutorProviderItemId, precedingTutorFeedbackForStudyAnswer,
            precedingQuestionProviderItemId,
            precedingAnswerProviderItemId, precedingTutorFeedbackProviderItemId,
            precedingTutorNavigationOfferProviderItemId,
            targetOffer,
        ))
        lastInputCommitNanos = requestedAt
        scheduleInputCommitTimeout()
        emit(
            linkedMapOf(
                "event_id" to eventId,
                "type" to "input_audio_buffer.commit",
            ),
        )
    }

    private fun observeSpeechStarted(deferTutorEvidence: Boolean = false) {
        // A newly observed utterance may retract or redirect the previous focus choice.
        // It cannot authorize a focus itself until its exact final item is assessed and persisted.
        latestFocusIntentBinding?.focusAuthorization?.invalidate()
        latestFocusIntentBinding = null
        if (!deferTutorEvidence) bindAndConsumeFinalizedTutorEvidence()
        if (!userSpeaking) {
            activeSpeechLessonRevision = currentLessonRevision
            pendingSpeechCommitCount = (pendingSpeechCommitCount + 1)
                .coerceAtMost(MAX_PENDING_SPEECH_COMMITS)
        }
        userSpeaking = true
        inputCheckpointDue = false
        scheduleInputCheckpoint()
    }

    private fun bindAndConsumeFinalizedTutorEvidence() {
        activeSpeechTargetOffer = activeTargetOffer?.takeIf { offer ->
            offer.lessonRevision == currentLessonRevision &&
                offer.tutorResponseGeneration == activeSpeechPrecedingSpokenGeneration &&
                offer.tutorSpeechStoppedOrder == activeSpeechPrecedingTutorStopOrder
        }
        val exchange = activeTargetOfferExchangeEvidence?.takeIf { evidence ->
            activeSpeechTargetOffer?.offerId == evidence.offerId &&
                evidence.navigationOfferProviderItemId == activeSpeechPrecedingTutorProviderItemId &&
                evidence.feedback.identity.lessonRevision == currentLessonRevision
        }
        activeSpeechPrecedingTutorFeedbackForStudyAnswer = exchange != null
        activeSpeechPrecedingQuestionProviderItemId = exchange?.feedback?.identity?.questionProviderItemId
        activeSpeechPrecedingAnswerProviderItemId = exchange?.feedback?.identity?.answerProviderItemId
        activeSpeechPrecedingTutorFeedbackProviderItemId = exchange?.feedback?.feedbackProviderItemId
        activeSpeechPrecedingTutorNavigationOfferProviderItemId = exchange?.navigationOfferProviderItemId
        activeTargetOffer = null
        activeTargetOfferExchangeEvidence = null
        candidateDiscoveryGraph = null
        activeResponseCandidateDiscovery = null
        toolDiscoveryFences.clear()
    }

    private fun observeSpeechStopped() {
        userSpeaking = false
        activeSpeechLessonRevision = null
        activeSpeechStartedOrder = 0
        activeSpeechPrecedingTutorStopOrder = 0
        activeSpeechPrecedingSpokenGeneration = 0
        activeSpeechPrecedingTutorProviderItemId = null
        activeSpeechPrecedingTutorFeedbackForStudyAnswer = false
        activeSpeechPrecedingQuestionProviderItemId = null
        activeSpeechPrecedingAnswerProviderItemId = null
        activeSpeechPrecedingTutorFeedbackProviderItemId = null
        activeSpeechPrecedingTutorNavigationOfferProviderItemId = null
        activeSpeechTargetOffer = null
        cancelInputCheckpointTimer()
        inputCheckpointDue = false
        if (pendingSpeechCommitCount == 0) createNormalResponseIfReady()
    }

    private fun acknowledgeInputCommit(node: com.fasterxml.jackson.databind.JsonNode): PendingInputCommit? {
        val item = node.path("item_id")
        val itemId = item.takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            ?: return null
        // ACKs have provider-generated item ids, not the client's sequence.
        // Retain a bounded replay window, including unsolicited ACKs, so a
        // duplicate cannot consume a later utterance's outstanding commit.
        if (!recentCommittedItemIds.add(itemId)) return null
        if (recentCommittedItemIds.size > MAX_RECENT_COMMITTED_ITEMS) {
            val oldest = recentCommittedItemIds.iterator()
            oldest.next()
            oldest.remove()
        }
        if (pendingInputCommits.isEmpty()) return null
        val acknowledged = pendingInputCommits.removeFirst()
        scheduleInputCommitTimeout()
        return acknowledged
    }

    private fun observeEmptyInputCommit(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (inputCoordinator == null) return false
        val error = node.path("error")
        if (error.path("code").asText() != "input_audio_buffer_commit_empty") return false
        val eventId = error.path("event_id").asText()
        val commit = pendingInputCommits.firstOrNull { it.eventId == eventId } ?: return false
        // Only an exact, server-owned commit error can release its own slot. A
        // checkpoint followed by mute/stop may have no remaining native audio;
        // that is neither a provider disconnect nor approval of learner input.
        pendingInputCommits.remove(commit)
        pendingSpeechCommitCount = (pendingSpeechCommitCount - commit.speechSlots).coerceAtLeast(0)
        scheduleInputCommitTimeout()
        if (commit.checkpoint && activeClientSpeechSequence == commit.sequence) {
            // An empty later checkpoint must not erase evidence of an earlier
            // checkpoint in this same speech sequence or invalidate its tail.
            inputCheckpointDue = false
            scheduleInputCheckpoint()
        } else if (inputCheckpointSequence != commit.sequence) {
            inputCoordinator.discardSpeechSequence(commit.sequence)
            val emitted = inputWork.tryEmitNext(
                VoiceTutorInputTurnCoordinator.Action.Retry(
                    VoiceTutorInputTurnCoordinator.RetryReason.TRANSCRIPTION_FAILED,
                ),
            )
            if (emitted.isFailure && !closed) {
                terminate(VoiceTutorPendingInputCommitOverflowException())
            }
        }
        withInputCoordinator { expire(nanoTime()) }
        return true
    }

    private fun scheduleInputCommitTimeout() {
        inputCommitTimer?.dispose()
        inputCommitTimer = null
        val pending = pendingInputCommits.peekFirst() ?: return
        val elapsed = (nanoTime() - pending.requestedAtNanos).coerceAtLeast(0)
        val remaining = (responseTimeout.toNanos() - elapsed).coerceAtLeast(1)
        inputCommitTimer = Mono.delay(Duration.ofNanos(remaining))
            .subscribe { fireInputCommitTimeout(pending) }
    }

    @Synchronized
    internal fun fireInputCommitTimeout(sequence: Long) {
        if (closed || pendingInputCommits.peekFirst()?.sequence != sequence) return
        fireInputCommitTimeout(pendingInputCommits.peekFirst())
    }

    @Synchronized
    private fun fireInputCommitTimeout(pending: PendingInputCommit) {
        // One speech sequence can have a checkpoint and a final tail; an old
        // timer may never expire that sequence's newer outstanding commit.
        if (closed || pendingInputCommits.peekFirst()?.eventId != pending.eventId) return
        inputCommitTimer = null
        terminate(VoiceTutorProviderInputCommitTimeoutException())
    }

    private fun scheduleInputCheckpoint(delay: Duration = continuousSpeechLimit) {
        if (closed || pauseCoordinator?.blocksResponses == true || !openingResponseRequested ||
            openingResponsePending || !userSpeaking || transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND
        ) return
        val sequence = activeClientSpeechSequence ?: return
        cancelInputCheckpointTimer()
        val generation = inputCheckpointTimerGeneration
        inputCheckpointTimer = Mono.delay(delay)
            .subscribe { fireInputCheckpointTimer(sequence, generation) }
    }

    private fun cancelInputCheckpointTimer() {
        inputCheckpointTimer?.dispose()
        inputCheckpointTimer = null
        inputCheckpointTimerGeneration += 1
    }

    @Synchronized
    private fun fireInputCheckpointTimer(sequence: Long, generation: Long) {
        // Disposed callbacks may race a later speech start or a newly scheduled
        // chunk of this sequence. They must not commit the later buffer early.
        if (closed || activeClientSpeechSequence != sequence || inputCheckpointTimerGeneration != generation) return
        fireContinuousSpeechDeadline()
    }

    private fun createNormalResponseIfReady() {
        if (
            closed || pendingSpokenLessonEnd != null || spokenLessonEndRequested ||
            pauseCoordinator?.blocksResponses == true ||
            !openingResponseRequested || userSpeaking ||
            pendingSpeechCommitCount > 0 || pendingInputCommits.isNotEmpty() || delayedStopCommit != null || responseActive ||
            inputCoordinator?.hasPending == true || toolCoordinator?.hasPending == true ||
            (!openingResponsePending && !queuedCommittedTurn && toolCoordinator?.continuationReady != true)
        ) return
        val opening = openingResponsePending
        val toolContinuation = toolCoordinator?.continuationReady == true
        if (opening) {
            openingResponsePending = false
        } else {
            // Every accepted learner item is already in provider context. If
            // they spoke during a tool call, this one response addresses the
            // latest input AND the tool result, after both gates are released.
            queuedCommittedTurn = false
            if (toolContinuation) toolCoordinator?.consumeContinuation() else toolCoordinator?.beginLearnerTurn()
        }
        // Opening speech uses the same response/playout gate as an ordinary turn.
        // Keep any early learner commit queued until its transport completion gate.
        val responseEventId = internalEventId(if (opening) "opening-response" else "turn-response")
        beginResponse(responseEventId, allowTools = !opening && toolCoordinator?.toolChoice == "auto")
        emit(
            linkedMapOf(
                "event_id" to responseEventId,
                "type" to "response.create",
                "response" to linkedMapOf(
                    "tool_choice" to if (opening) "none" else toolCoordinator?.toolChoice ?: "none",
                    "metadata" to linkedMapOf(
                        VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to responseEventId,
                    ),
                ),
            ),
        )
    }

    @Synchronized
    internal fun advancePause() {
        val pause = pauseCoordinator ?: return
        if (closed) return
        try {
            applyPauseActions(
                pause.advance(
                    boundaryReady = !responseActive && !userSpeaking && activeClientSpeechSequence == null &&
                        pendingSpeechCommitCount == 0 && pendingInputCommits.isEmpty() && delayedStopCommit == null,
                    now = nanoTime(),
                ),
            )
        } catch (error: VoiceTutorPauseAcknowledgementTimeoutException) {
            terminate(error)
            return
        }
        if (pause.needsClock) {
            if (pauseTimer == null) {
                pauseTimer = Flux.interval(INPUT_DEADLINE_POLL_INTERVAL).subscribe { advancePause() }
            }
        } else {
            pauseTimer?.dispose()
            pauseTimer = null
        }
    }

    private fun applyPauseActions(actions: List<VoiceTutorPauseCoordinator.Action>) {
        for (action in actions) {
            when (action) {
                is VoiceTutorPauseCoordinator.Action.ClearInput -> emit(
                    linkedMapOf("event_id" to action.eventId, "type" to "input_audio_buffer.clear"),
                )
                is VoiceTutorPauseCoordinator.Action.Acknowledge -> {
                    val result = clientControls.tryEmitNext(
                        mapper.writeValueAsString(
                            mapOf(
                                "type" to VoiceTutorRealtimeContract.PAUSE_STATE_EVENT,
                                "sequence" to action.sequence,
                                "paused" to action.paused,
                            ),
                        ),
                    )
                    if (result.isFailure && !closed) {
                        terminate(IllegalStateException("Voice Tutor client control buffer overflowed."))
                    }
                }
            }
        }
    }

    private fun emit(event: Map<String, Any?>) {
        if (closed) return
        val result = controls.tryEmitNext(mapper.writeValueAsString(event))
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            terminate(IllegalStateException("Voice Tutor provider control buffer overflowed."))
        }
    }

    private fun beginResponse(createEventId: String, allowTools: Boolean = false) {
        inputCoordinator?.teacherResponseStarted()
        activeTutorTranscripts.clear()
        activeTutorTranscriptItemIds.clear()
        activeTargetOffer = null
        activeTargetOfferExchangeEvidence = null
        activeResponseCandidateDiscovery = candidateDiscoveryGraph?.offerPool()?.takeIf {
            it.lessonRevision == currentLessonRevision
        }
        playbackTimer?.dispose()
        playbackTimer = null
        activeResponseGeneration = if (activeResponseGeneration == Long.MAX_VALUE) {
            1
        } else {
            activeResponseGeneration + 1
        }
        val studyAnswer = latestAcceptedInputBinding?.takeIf {
            it.inputIntent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION
        }?.let { binding ->
            val question = binding.precedingTutorProviderItemId
                ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            val answer = binding.providerItemId
                ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            if (question != null && answer != null) {
                StudyAnswerIdentity(binding.lessonRevision, question, answer)
            } else {
                null
            }
        }
        activeResponseStudyAnswer = studyAnswer?.takeUnless { it == lastSpokenStudyAnswer }
        activeResponseRespondsToStudyAnswer = activeResponseStudyAnswer != null
        if (activeResponseRespondsToStudyAnswer) {
            // A new answer needs its own exact persisted feedback item. Tool-only
            // rounds leave it pending; an actual spoken response consumes it once.
            completedStudyAnswerFeedback = null
            pendingNavigationFeedback = null
        }
        activeResponseTutorContext = ""
        responseActive = true
        activeResponseLessonRevision = currentLessonRevision
        activeResponseCreateEventId = createEventId
        activeResponseId = null
        activeResponseAllowsTools = allowTools
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        providerOutputBufferStarted = false
        providerAudioObserved = false
        toolOnlyResponse = false
        responseTimer?.dispose()
        val responseGeneration = activeResponseGeneration
        responseTimer = Mono.delay(responseTimeout)
            .subscribe { fireResponseTimeout(responseGeneration, createEventId) }
    }

    private fun observeResponseCreated(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive) return false
        if (!matchesActiveResponseToken(node.path("response"))) return false
        val responseId = node.path("response").path("id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        rememberBoundedBinding(responseLessonRevisions, responseId, activeResponseLessonRevision)
        return true
    }

    private fun observeResponseAudio(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive || activeResponseId == null) return false
        val responseId = node.path("response_id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        val bytes = runCatching { Base64.getDecoder().decode(node.path("delta").asText()).size.toLong() }
            .getOrDefault(0)
        if (bytes > 0) {
            val receivedAt = nanoTime()
            val playbackStart = maxOf(earliestResponsePlaybackEndNanos ?: receivedAt, receivedAt)
            earliestResponsePlaybackEndNanos = playbackStart + audioDurationNanos(bytes)
        }
        activeResponseAudioBytes = (activeResponseAudioBytes + bytes).coerceAtMost(MAX_RESPONSE_AUDIO_BYTES)
        return true
    }

    private fun observeResponseDone(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive || providerResponseDone) return false
        val response = node.path("response")
        // A stale provider result cannot fail or complete a different response.
        if (!matchesActiveResponseToken(response)) return false
        val responseId = response.path("id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            response.path("status").asText() != "completed"
        ) {
            throw VoiceTutorProviderIncompleteResponseException()
        }
        if (response.path("status").asText() !in COMPLETING_RESPONSE_STATUSES) return true
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        if (response.path("output").any { it.path("type").asText() == "function_call" } && !activeResponseAllowsTools) {
            // An opening or exhausted tool round never has
            // permission to execute a function, even if a provider emits one.
            throw VoiceTutorMcpProtocolException()
        }
        rememberBoundedBinding(responseLessonRevisions, responseId, activeResponseLessonRevision)
        val toolCalls = toolCoordinator?.completedResponse(response) ?: emptyList()
        toolOnlyResponse = toolCalls.isNotEmpty() &&
            response.path("output").all { it.path("type").asText() == "function_call" } &&
            !providerOutputBufferStarted && !providerAudioObserved && activeTutorTranscripts.isEmpty()
        if (toolOnlyResponse) {
            activeResponseCandidateDiscovery = null
        }
        providerResponseDone = true
        responseTimer?.dispose()
        responseTimer = null
        for (call in toolCalls) {
            if (toolWork.tryEmitNext(call).isFailure && !closed) {
                terminate(VoiceTutorMcpProtocolException())
                return true
            }
        }
        activeResponseTutorContext = completedTutorContext(response)
        withInputCoordinator {
            teacherResponseCompleted(tutorContextForAssessment(activeResponseTutorContext), nanoTime())
        }
        promoteTargetOfferIfReady()
        advancePlaybackGate()
        return true
    }

    private fun rememberTutorTranscript(node: com.fasterxml.jackson.databind.JsonNode) {
        if (inputCoordinator == null || activeTutorTranscripts.size >= MAX_TUTOR_CONTEXT_PARTS) return
        val text = node.path("transcript").takeIf { it.isTextual }?.textValue() ?: return
        val itemId = node.path("item_id").asText()
            .takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS } ?: return
        val key = itemId +
            ":" + node.path("content_index").asInt(0)
        activeTutorTranscripts[key] = text.take(MAX_TUTOR_CONTEXT_CHARACTERS)
        activeTutorTranscriptItemIds += itemId
    }

    private fun completedTutorContext(response: com.fasterxml.jackson.databind.JsonNode): String {
        if (activeTutorTranscripts.isNotEmpty()) {
            return activeTutorTranscripts.values.joinToString("\n").take(MAX_TUTOR_CONTEXT_CHARACTERS)
        }
        // response.done is also authoritative if final transcript events were
        // unavailable. Read assistant text only; never invent learner context.
        return response.path("output").asSequence()
            .filter { it.path("role").asText() == "assistant" }
            .flatMap { it.path("content").asSequence() }
            .mapNotNull { it.path("transcript").takeIf { text -> text.isTextual }?.textValue() }
            .take(MAX_TUTOR_CONTEXT_PARTS)
            .joinToString("\n")
            .take(MAX_TUTOR_CONTEXT_CHARACTERS)
    }

    private fun tutorContextForAssessment(latestTutorContext: String): String {
        val feedback = pendingNavigationFeedback?.takeIf {
            !activeResponseRespondsToStudyAnswer && activeResponseCandidateDiscovery != null &&
                it == completedStudyAnswerFeedback && it.identity == lastSpokenStudyAnswer &&
                it.identity.lessonRevision == activeResponseLessonRevision
        } ?: return latestTutorContext
        val feedbackLabel = "[completed answer feedback]\n"
        val offerLabel = "\n[latest navigation offer]\n"
        val contentBudget = (MAX_TUTOR_CONTEXT_CHARACTERS - feedbackLabel.length - offerLabel.length)
            .coerceAtLeast(2)
        val feedbackBudget = contentBudget / 2
        val offerBudget = contentBudget - feedbackBudget
        return feedbackLabel + feedback.tutorContext.takeLast(feedbackBudget) + offerLabel +
            latestTutorContext.takeLast(offerBudget)
    }

    private fun observeOutputBufferStopped(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) return false
        val responseId = node.path("response_id").asText()
        if (!matchesKnownActiveResponse(responseId)) return false
        if (!providerOutputBufferStopped) {
            // Only a matching, actual server output boundary counts. A mixed
            // tool/audio response keeps its own generation; its late tail is
            // not evidence that a later confirmation question was spoken.
            lastTutorSpeechStoppedOrder = nextDialogueEventOrder()
            lastSpokenResponseGeneration = activeResponseGeneration
        }
        providerOutputBufferStopped = true
        promoteTargetOfferIfReady()
        advancePlaybackGate()
        return true
    }

    private fun promoteTargetOfferIfReady() {
        if (!responseActive || !providerResponseDone || !providerOutputBufferStopped ||
            (!providerOutputBufferStarted && !providerAudioObserved)
        ) return
        val discovery = activeResponseCandidateDiscovery?.takeIf {
            it.lessonRevision == activeResponseLessonRevision &&
                it.lessonRevision == currentLessonRevision && validCandidateOfferPool(it)
        }
        // One discovery graph belongs to exactly this next spoken response.
        // Tool-only rounds retain it; any completed audio response consumes it,
        // even when no candidate was safely eligible or no transcript arrived.
        candidateDiscoveryGraph = null
        activeResponseCandidateDiscovery = null
        if (discovery == null) return
        val tutorAudioTranscript = activeTutorTranscripts.values.joinToString("\n")
            .take(MAX_TUTOR_CONTEXT_CHARACTERS)
            .takeIf { it.isNotBlank() } ?: run {
            return
        }
        if (nextTargetOfferId <= 0 || nextTargetOfferId == Long.MAX_VALUE ||
            lastTutorSpeechStoppedOrder <= 0 || lastSpokenResponseGeneration != activeResponseGeneration
        ) {
            return
        }
        activeTargetOffer = VoiceTutorStudyTargetOffer(
            offerId = nextTargetOfferId++,
            lessonRevision = discovery.lessonRevision,
            tutorResponseGeneration = activeResponseGeneration,
            tutorSpeechStoppedOrder = lastTutorSpeechStoppedOrder,
            currentFocusStudyId = discovery.currentFocusStudyId,
            candidates = discovery.candidates,
            tutorAudioTranscript = tutorAudioTranscript,
            candidateTraversals = discovery.candidateTraversals,
        )
    }

    private fun validCandidateOfferPool(pool: CandidateOfferPool): Boolean =
        pool.lessonRevision >= 0 && pool.currentFocusStudyId?.let { it > 0 } != false &&
            pool.candidates.size in 1..MAX_TARGET_OFFER_CANDIDATES &&
            pool.candidates.map { it.studyId }.distinct().size == pool.candidates.size &&
            pool.candidateTraversals.keys == pool.candidates.mapTo(linkedSetOf()) { it.studyId } &&
            pool.candidates.all { candidate ->
                candidate.studyId > 0 && candidate.parentStudyId?.let { it > 0 } != false &&
                    candidate.topic.isNotBlank() && candidate.topic.length <= 255 &&
                    pool.candidateTraversals[candidate.studyId]
                        ?.isValidFor(candidate, MAX_DISCOVERY_TREE_DEPTH) == true
            }

    private fun observeOutputBufferCleared(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition {
        if (transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            return VoiceTutorProviderRelayDisposition.DROP
        }
        // A cleared WebRTC buffer means a tutor sentence was cut short. It is never
        // a valid turn completion, including when a stale or forged response id is used.
        node.path("response_id").asText()
        throw VoiceTutorProviderOutputBufferClearedException()
    }

    private fun matchesActiveResponse(responseId: String): Boolean =
        responseActive && responseId.isNotBlank() && (activeResponseId == null || responseId == activeResponseId)

    private fun matchesKnownActiveResponse(responseId: String): Boolean =
        responseActive && activeResponseId != null && responseId == activeResponseId

    private fun matchesActiveResponseToken(response: com.fasterxml.jackson.databind.JsonNode): Boolean =
        response.path("metadata").path(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY).asText() ==
            activeResponseCreateEventId

    private fun advancePlaybackGate() {
        if (!responseActive || !providerResponseDone) return
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            // The same response must finish successfully AND exhaust its server
            // output buffer. Subsequent audio stays on the continuous RTP track;
            // creating a response does not cancel, clear, or reset the old tail.
            // This proves server completion, not that the device heard every sample.
            // A completed, function-only response has no audio buffer and will
            // not emit stopped. This is not a shortcut for spoken responses.
            if (providerOutputBufferStopped || toolOnlyResponse) {
                finishActiveResponse()
            } else if (playbackTimer == null) {
                val responseGeneration = activeResponseGeneration
                val responseId = activeResponseId
                playbackTimer = Mono.delay(responseTimeout)
                    .subscribe { fireWebRtcPlayoutTimeout(responseGeneration, responseId) }
            }
            return
        }
        if (activeResponseAudioBytes == 0L) {
            finishActiveResponse()
            return
        }
        if (playbackCompleted) {
            val remainingNanos = earliestPlaybackCompletionNanos() - nanoTime()
            if (remainingNanos <= 0) {
                finishActiveResponse()
            } else {
                schedulePlaybackTimer(remainingNanos, requiresAcknowledgement = true)
            }
        } else {
            val fallbackNanos = (responseAudioDurationNanos() + PLAYBACK_ACK_GRACE_NANOS)
                .coerceAtLeast(PLAYBACK_ACK_MIN_NANOS)
            schedulePlaybackTimer(fallbackNanos, requiresAcknowledgement = false)
        }
    }

    private fun earliestPlaybackCompletionNanos(): Long {
        return earliestResponsePlaybackEndNanos ?: nanoTime()
    }

    private fun responseAudioDurationNanos(): Long =
        audioDurationNanos(activeResponseAudioBytes)

    private fun audioDurationNanos(audioBytes: Long): Long =
        ((audioBytes * NANOS_PER_SECOND) + PCM_BYTES_PER_SECOND - 1) / PCM_BYTES_PER_SECOND

    private fun schedulePlaybackTimer(delayNanos: Long, requiresAcknowledgement: Boolean) {
        val responseGeneration = activeResponseGeneration
        val responseId = activeResponseId
        playbackTimer?.dispose()
        playbackTimer = Mono.delay(Duration.ofNanos(delayNanos.coerceAtLeast(1)))
            .subscribe {
                firePlaybackTimer(responseGeneration, responseId, requiresAcknowledgement)
            }
    }

    @Synchronized
    internal fun firePlaybackTimeout(responseId: String?) {
        firePlaybackTimer(activeResponseGeneration, responseId, requiresAcknowledgement = false)
    }

    @Synchronized
    internal fun firePlaybackFloor(responseId: String?) {
        firePlaybackTimer(activeResponseGeneration, responseId, requiresAcknowledgement = true)
    }

    @Synchronized
    internal fun fireResponseTimeout(createEventId: String?) {
        fireResponseTimeout(activeResponseGeneration, createEventId)
    }

    @Synchronized
    internal fun fireResponseTimeout(responseGeneration: Long, createEventId: String?) {
        if (
            closed ||
            !responseActive ||
            providerResponseDone ||
            responseGeneration != activeResponseGeneration ||
            createEventId != activeResponseCreateEventId
        ) {
            return
        }
        responseTimer = null
        emit(
            linkedMapOf(
                "event_id" to "buddystudy-internal-response-timeout-${UUID.randomUUID()}",
                "type" to "response.cancel",
            ),
        )
        terminate(VoiceTutorProviderResponseTimeoutException())
    }

    @Synchronized
    internal fun fireWebRtcPlayoutTimeout(responseGeneration: Long, responseId: String?) {
        if (
            closed ||
            transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND ||
            !responseActive ||
            !providerResponseDone ||
            providerOutputBufferStopped ||
            responseGeneration != activeResponseGeneration ||
            responseId != activeResponseId
        ) {
            return
        }
        playbackTimer = null
        terminate(VoiceTutorProviderPlayoutTimeoutException())
    }

    @Synchronized
    private fun firePlaybackTimer(
        responseGeneration: Long,
        responseId: String?,
        requiresAcknowledgement: Boolean,
    ) {
        if (
            !closed &&
            responseActive &&
            providerResponseDone &&
            responseGeneration == activeResponseGeneration &&
            responseId == activeResponseId &&
            (!requiresAcknowledgement || playbackCompleted)
        ) {
            if (nanoTime() >= earliestPlaybackCompletionNanos()) {
                finishActiveResponse()
            } else {
                advancePlaybackGate()
            }
        }
    }

    private fun finishActiveResponse() {
        if (!responseActive) return
        val completedResponseGeneration = activeResponseGeneration
        val completedTutorItemId = activeTutorTranscriptItemIds.singleOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
        val spokenResponseCompleted = providerOutputBufferStopped &&
            (providerOutputBufferStarted || providerAudioObserved)
        var feedbackThisResponse: StudyAnswerFeedbackEvidence? = null
        if (spokenResponseCompleted) {
            // Keep exact identity aligned with lastSpokenResponseGeneration. A
            // response without one final transcript item deliberately clears it.
            lastSpokenTutorProviderItemId = completedTutorItemId
            lastSpokenTutorProviderItemGeneration = completedResponseGeneration.takeIf {
                completedTutorItemId != null
            } ?: 0
        }
        if (activeResponseRespondsToStudyAnswer && spokenResponseCompleted) {
            activeResponseStudyAnswer?.let { identity ->
                // Any spoken answer response consumes the answer-to-feedback slot.
                // Only one exact final tutor item plus nonempty completed context
                // creates feedback evidence; a later navigation offer cannot fill it in.
                lastSpokenStudyAnswer = identity
                val feedbackItemId = completedTutorItemId
                val tutorContext = activeResponseTutorContext.takeIf { it.isNotBlank() }
                if (feedbackItemId != null && tutorContext != null) {
                    StudyAnswerFeedbackEvidence(identity, feedbackItemId, tutorContext).also { evidence ->
                        feedbackThisResponse = evidence
                        completedStudyAnswerFeedback = evidence
                        pendingNavigationFeedback = evidence
                    }
                }
            }
        }
        if (spokenResponseCompleted) {
            val offer = activeTargetOffer?.takeIf {
                it.tutorResponseGeneration == completedResponseGeneration &&
                    it.lessonRevision == activeResponseLessonRevision
            }
            if (offer != null) {
                val feedback = feedbackThisResponse ?: pendingNavigationFeedback?.takeIf {
                    it == completedStudyAnswerFeedback && it.identity == lastSpokenStudyAnswer &&
                        it.identity.lessonRevision == offer.lessonRevision
                }
                activeTargetOfferExchangeEvidence = if (feedback != null && completedTutorItemId != null) {
                    TargetOfferExchangeEvidence(offer.offerId, feedback, completedTutorItemId)
                } else {
                    null
                }
                // One completed offer gets one opportunity to carry this feedback.
                // A second tutor response before consent cannot reuse the exchange.
                pendingNavigationFeedback = null
            } else if (!activeResponseRespondsToStudyAnswer) {
                // Any other spoken tutor turn is an intervening unanswered turn.
                pendingNavigationFeedback = null
            }
        }
        finalizeSpeechAwaitingTutorResponse(completedResponseGeneration, completedTutorItemId)
        playbackTimer?.dispose()
        playbackTimer = null
        responseTimer?.dispose()
        responseTimer = null
        responseActive = false
        activeResponseCreateEventId = null
        activeResponseId = null
        activeResponseAllowsTools = false
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        activeResponseCandidateDiscovery = null
        activeResponseRespondsToStudyAnswer = false
        activeResponseStudyAnswer = null
        activeResponseTutorContext = ""
        if (closed) return
        pendingSpokenLessonEnd?.let { pending ->
            if (
                pending.waitResponseGeneration == completedResponseGeneration &&
                pending.speechSequence == lastClientSpeechSequence
            ) {
                emitSpokenLessonEndLifecycle()
            }
            if (pendingSpokenLessonEnd != null || spokenLessonEndRequested) return
        }
        if (userSpeaking) {
            if (inputCheckpointDue) {
                fireContinuousSpeechDeadline()
            } else if (inputCheckpointTimer == null) {
                scheduleInputCheckpoint()
            }
        } else {
            createNormalResponseIfReady()
        }
    }

    /**
     * WebRTC can report server playout stop just before response.done. Speech that begins in that
     * narrow window belongs after the completed tutor sentence, but its immutable input commit must
     * wait until response.done supplies the exact provider item, offer and answer-feedback evidence.
     */
    private fun finalizeSpeechAwaitingTutorResponse(
        completedResponseGeneration: Long,
        completedTutorItemId: String?,
    ) {
        if (speechAwaitingTutorFinalizationGeneration != completedResponseGeneration) return
        activeSpeechPrecedingTutorStopOrder = lastTutorSpeechStoppedOrder
        activeSpeechPrecedingSpokenGeneration = completedResponseGeneration
        activeSpeechPrecedingTutorProviderItemId = completedTutorItemId
        bindAndConsumeFinalizedTutorEvidence()

        val stopped = stopAwaitingTutorFinalization
        speechAwaitingTutorFinalizationGeneration = null
        stopAwaitingTutorFinalization = null
        if (stopped == null) return
        val finalized = if (stopped.mixedTutorBoundary) {
            stopped
        } else {
            stopped.copy(
                precedingTutorSpeechStoppedOrder = activeSpeechPrecedingTutorStopOrder,
                precedingSpokenResponseGeneration = activeSpeechPrecedingSpokenGeneration,
                precedingTutorProviderItemId = activeSpeechPrecedingTutorProviderItemId,
                precedingTutorFeedbackForStudyAnswer = activeSpeechPrecedingTutorFeedbackForStudyAnswer,
                precedingQuestionProviderItemId = activeSpeechPrecedingQuestionProviderItemId,
                precedingAnswerProviderItemId = activeSpeechPrecedingAnswerProviderItemId,
                precedingTutorFeedbackProviderItemId = activeSpeechPrecedingTutorFeedbackProviderItemId,
                precedingTutorNavigationOfferProviderItemId =
                    activeSpeechPrecedingTutorNavigationOfferProviderItemId,
                targetOffer = activeSpeechTargetOffer,
            )
        }
        val earlierUncommittedSpeech = delayedStopCommit
        val mixedTutorBoundary = finalized.mixedTutorBoundary || earlierUncommittedSpeech != null
        val pendingCommit = if (earlierUncommittedSpeech == null) {
            finalized
        } else {
            // Both stopped segments are still in the same native input buffer,
            // but one began before tutor playout stopped and the other after it.
            // Preserve every reserved slot while removing all authority that
            // depends on a single unambiguous tutor/learner boundary.
            mergeMixedTutorBoundaryStopCommits(earlierUncommittedSpeech, finalized)
        }
        if (mixedTutorBoundary) {
            delayedStopCommitTimer?.dispose()
            delayedStopCommitTimer = null
            clearActiveSpeechTutorBoundary()
        }
        if (activeClientSpeechSequence != null) {
            // A rapid stop/start still shares the native buffer. Carry the
            // finalized stopped segments until the current segment stops. A
            // prior timer must never flush and split this still-active buffer.
            delayedStopCommit = pendingCommit
            return
        }
        if (mixedTutorBoundary) {
            delayedStopCommit = pendingCommit
            flushDelayedStopCommit(pendingCommit.firstSequence)
        } else {
            requestPendingStopCommit(pendingCommit)
        }
        clearActiveSpeechTutorBoundary()
    }

    private fun mergeMixedTutorBoundaryStopCommits(
        beforeTutorStop: PendingStopCommit,
        afterTutorStop: PendingStopCommit,
    ): PendingStopCommit {
        if (beforeTutorStop.speechSlots > MAX_PENDING_SPEECH_COMMITS - afterTutorStop.speechSlots) {
            throw VoiceTutorPendingInputCommitOverflowException()
        }
        return beforeTutorStop.copy(
            lastSequence = afterTutorStop.lastSequence,
            speechSlots = beforeTutorStop.speechSlots + afterTutorStop.speechSlots,
            precedingTutorSpeechStoppedOrder = 0,
            precedingSpokenResponseGeneration = 0,
            precedingTutorProviderItemId = null,
            precedingTutorFeedbackForStudyAnswer = false,
            precedingQuestionProviderItemId = null,
            precedingAnswerProviderItemId = null,
            precedingTutorFeedbackProviderItemId = null,
            precedingTutorNavigationOfferProviderItemId = null,
            targetOffer = null,
            mixedTutorBoundary = true,
        )
    }

    private fun requestPendingStopCommit(pending: PendingStopCommit) {
        requestInputCommit(
            pending.lastSequence,
            checkpoint = false,
            speechSlots = pending.speechSlots,
            lessonRevision = pending.lessonRevision,
            speechStartedOrder = pending.speechStartedOrder,
            precedingTutorSpeechStoppedOrder = pending.precedingTutorSpeechStoppedOrder,
            precedingSpokenResponseGeneration = pending.precedingSpokenResponseGeneration,
            precedingTutorProviderItemId = pending.precedingTutorProviderItemId,
            precedingTutorFeedbackForStudyAnswer = pending.precedingTutorFeedbackForStudyAnswer,
            precedingQuestionProviderItemId = pending.precedingQuestionProviderItemId,
            precedingAnswerProviderItemId = pending.precedingAnswerProviderItemId,
            precedingTutorFeedbackProviderItemId = pending.precedingTutorFeedbackProviderItemId,
            precedingTutorNavigationOfferProviderItemId =
                pending.precedingTutorNavigationOfferProviderItemId,
            targetOffer = pending.targetOffer,
        )
    }

    private fun clearActiveSpeechTutorBoundary() {
        activeSpeechPrecedingTutorStopOrder = 0
        activeSpeechPrecedingSpokenGeneration = 0
        activeSpeechPrecedingTutorProviderItemId = null
        activeSpeechPrecedingTutorFeedbackForStudyAnswer = false
        activeSpeechPrecedingQuestionProviderItemId = null
        activeSpeechPrecedingAnswerProviderItemId = null
        activeSpeechPrecedingTutorFeedbackProviderItemId = null
        activeSpeechPrecedingTutorNavigationOfferProviderItemId = null
        activeSpeechTargetOffer = null
    }

    private fun internalEventId(action: String): String =
        "$DUPLEX_EVENT_PREFIX$action-${UUID.randomUUID()}"

    private fun nextDialogueEventOrder(): Long {
        // Saturating rather than wrapping preserves conservative strict-order
        // checks even beyond any practical bounded call length.
        if (dialogueEventOrder < Long.MAX_VALUE) dialogueEventOrder += 1
        return dialogueEventOrder
    }

    private fun <T> rememberBoundedBinding(bindings: MutableMap<String, T>, itemId: String, value: T) {
        if (itemId.isBlank() || itemId.length > MAX_PROVIDER_ITEM_ID_CHARACTERS) return
        bindings[itemId] = value
        if (bindings.size > MAX_RECENT_COMMITTED_ITEMS) {
            val oldest = bindings.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun accepted(
        accepted: Boolean,
        disposition: VoiceTutorProviderRelayDisposition = VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST,
    ): VoiceTutorProviderRelayDisposition =
        if (accepted) {
            disposition
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }

    private fun terminalDisposition(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition = when (node.path("type").asText()) {
        in USER_TRANSCRIPT_EVENTS -> if (inputCoordinator == null) {
            VoiceTutorProviderRelayDisposition.PERSIST_ONLY
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }
        in TUTOR_TRANSCRIPT_EVENTS -> if (matchesKnownActiveResponse(node.path("response_id").asText())) {
            VoiceTutorProviderRelayDisposition.PERSIST_ONLY
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }
        else -> VoiceTutorProviderRelayDisposition.DROP
    }

    private data class StudyAnswerIdentity(
        val lessonRevision: Long,
        val questionProviderItemId: String,
        val answerProviderItemId: String,
    )

    private data class StudyAnswerFeedbackEvidence(
        val identity: StudyAnswerIdentity,
        val feedbackProviderItemId: String,
        val tutorContext: String,
    )

    private data class TargetOfferExchangeEvidence(
        val offerId: Long,
        val feedback: StudyAnswerFeedbackEvidence,
        val navigationOfferProviderItemId: String,
    )

    private data class PendingInputCommit(
        val sequence: Long,
        val requestedAtNanos: Long,
        val checkpoint: Boolean = false,
        val eventId: String = UUID.randomUUID().toString(),
        val speechSlots: Int = 1,
        val lessonRevision: Long = 0,
        val speechStartedOrder: Long = 0,
        val precedingTutorSpeechStoppedOrder: Long = 0,
        val precedingSpokenResponseGeneration: Long = 0,
        val precedingTutorProviderItemId: String? = null,
        val precedingTutorFeedbackForStudyAnswer: Boolean = false,
        val precedingQuestionProviderItemId: String? = null,
        val precedingAnswerProviderItemId: String? = null,
        val precedingTutorFeedbackProviderItemId: String? = null,
        val precedingTutorNavigationOfferProviderItemId: String? = null,
        val targetOffer: VoiceTutorStudyTargetOffer? = null,
    )

    private data class PendingStopCommit(
        val firstSequence: Long,
        val lastSequence: Long,
        val speechSlots: Int,
        // One native buffer can merge rapid adjacent utterances. Keep its first
        // epoch even if an MCP result arrives before the delayed tail flushes.
        val lessonRevision: Long,
        val speechStartedOrder: Long,
        val precedingTutorSpeechStoppedOrder: Long,
        val precedingSpokenResponseGeneration: Long,
        val precedingTutorProviderItemId: String?,
        val precedingTutorFeedbackForStudyAnswer: Boolean,
        val precedingQuestionProviderItemId: String?,
        val precedingAnswerProviderItemId: String?,
        val precedingTutorFeedbackProviderItemId: String?,
        val precedingTutorNavigationOfferProviderItemId: String?,
        val targetOffer: VoiceTutorStudyTargetOffer?,
        val mixedTutorBoundary: Boolean = false,
    )

    private data class InputLessonBinding(
        val lessonRevision: Long,
        val speechStartedOrder: Long,
        val precedingTutorSpeechStoppedOrder: Long = 0,
        val precedingSpokenResponseGeneration: Long = 0,
        val precedingTutorProviderItemId: String? = null,
        val precedingTutorFeedbackForStudyAnswer: Boolean = false,
        val precedingQuestionProviderItemId: String? = null,
        val precedingAnswerProviderItemId: String? = null,
        val precedingTutorFeedbackProviderItemId: String? = null,
        val precedingTutorNavigationOfferProviderItemId: String? = null,
        val targetOffer: VoiceTutorStudyTargetOffer? = null,
        val providerItemId: String? = null,
        val inputIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        val targetStudyId: Long? = null,
        val targetOfferId: Long? = null,
        val focusAuthorization: VoiceTutorFocusAuthorization? = null,
    )

    private data class CandidateOfferPool(
        val lessonRevision: Long,
        val currentFocusStudyId: Long?,
        val candidates: List<VoiceTutorStudyTargetCandidate>,
        val candidateTraversals: Map<Long, VoiceTutorStudyTargetTraversal>,
    )

    /**
     * Call-local graph assembled only from exact server-validated page slices.
     * A complete zero/one result can prove a leaf or one navigation edge. For a
     * wider result, its exact offset-zero slice already proves a real branch and
     * may offer only actual returned nodes; missing later pages can never turn a
     * partial slice into leaf/single-edge authority.
     */
    private class CandidateDiscoveryGraph(
        val lessonRevision: Long,
        val currentFocusStudyId: Long?,
    ) {
        private val nodes = linkedMapOf<Long, VoiceTutorStudyTargetCandidate>()
        private val exactSeeds = linkedSetOf<Long>()
        private val queryPages = linkedMapOf<QueryPageKey, CandidatePageSeries>()
        private val directChildPages = linkedMapOf<DirectChildrenPageKey, CandidatePageSeries>()

        fun merge(discovery: VoiceTutorCandidateDiscovery): Boolean {
            if (discovery.lessonRevision != lessonRevision ||
                discovery.currentFocusStudyId != currentFocusStudyId
            ) return false
            for (candidate in discovery.candidates) {
                val previous = nodes.putIfAbsent(candidate.studyId, candidate)
                if (previous != null && previous != candidate) return false
            }
            when (val scope = discovery.scope) {
                is VoiceTutorCandidateDiscoveryScope.ExactStudy -> exactSeeds += scope.requestedStudyId
                is VoiceTutorCandidateDiscoveryScope.CompleteQueryPage -> {
                    val key = QueryPageKey(scope.query, scope.limit)
                    if (queryPages.keys.any { it.query == scope.query && it.limit != scope.limit }) return false
                    val pages = queryPages.getOrPut(key) {
                        CandidatePageSeries(scope.limit, scope.totalCount)
                    }
                    if (!pages.merge(scope.offset, scope.totalCount, discovery.candidates.map { it.studyId })) {
                        return false
                    }
                }
                is VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage -> {
                    val key = DirectChildrenPageKey(scope.parentStudyId, scope.limit)
                    if (directChildPages.keys.any {
                            it.parentStudyId == scope.parentStudyId && it.limit != scope.limit
                        }
                    ) return false
                    val pages = directChildPages.getOrPut(key) {
                        CandidatePageSeries(scope.limit, scope.totalCount)
                    }
                    if (!pages.merge(scope.offset, scope.totalCount, discovery.candidates.map { it.studyId })) {
                        return false
                    }
                }
                VoiceTutorCandidateDiscoveryScope.Unscoped -> return false
            }
            return nodes.size <= MAX_DISCOVERY_GRAPH_NODES &&
                queryPages.size <= MAX_DISCOVERY_QUERY_GROUPS &&
                directChildPages.size <= MAX_DISCOVERY_GRAPH_NODES
        }

        fun offerPool(): CandidateOfferPool? {
            val eligible = linkedMapOf<Long, VoiceTutorStudyTargetTraversal>()

            // During a lesson only actual direct children from the verified
            // current-focus slice may be proposed. The write path rechecks the
            // selected edge; this discovery does not claim the branch is complete.
            currentFocusStudyId?.let { focus ->
                directChildPage(focus)?.offerableIds()?.forEach { candidateId ->
                    eligible.putIfAbsent(candidateId, VoiceTutorStudyTargetTraversal())
                }
            }

            // An offset-zero result with totalCount > 1 is already a real
            // ambiguity. A complete singleton follows only complete zero/one
            // child results until a leaf or that first real branch is reached.
            for (pages in queryPages.values) {
                val group = pages.offerableIds() ?: continue
                when {
                    pages.isBranch -> group.forEach { candidateId ->
                        eligible.putIfAbsent(candidateId, VoiceTutorStudyTargetTraversal())
                    }
                    pages.isCompleteSingleton -> {
                        val seed = group.single()
                        val exactDirectChild = currentFocusStudyId?.let { focus ->
                            nodes[seed]?.parentStudyId == focus
                        } == true
                        if (exactDirectChild) {
                            eligible.putIfAbsent(seed, VoiceTutorStudyTargetTraversal())
                        } else {
                            resolveInitialEndpoint(seed, eligible)
                        }
                    }
                }
            }
            for (seed in exactSeeds) resolveInitialEndpoint(seed, eligible)

            if (eligible.isEmpty()) return null
            // Contiguous slices can extend the deterministic first window, but
            // a missing later slice never delays an already-proved real branch.
            // A learner wanting an unseen node needs a narrower fresh read/offer.
            val selected = eligible.entries.take(MAX_TARGET_OFFER_CANDIDATES)
            val candidates = selected.map { nodes[it.key] ?: return null }
            return CandidateOfferPool(
                lessonRevision,
                currentFocusStudyId,
                candidates,
                selected.associateTo(linkedMapOf()) { it.key to it.value },
            )
        }

        private fun resolveInitialEndpoint(
            seed: Long,
            eligible: MutableMap<Long, VoiceTutorStudyTargetTraversal>,
        ) {
            var current = seed
            val visited = linkedSetOf<Long>()
            val followedEdges = mutableListOf<VoiceTutorStudyTargetSingleChildEdge>()
            repeat(MAX_DISCOVERY_TREE_DEPTH) {
                if (!visited.add(current)) return
                val pages = directChildPage(current) ?: return
                val children = pages.offerableIds() ?: return
                when {
                    pages.isCompleteLeaf -> {
                        if (nodes.containsKey(current)) {
                            eligible.putIfAbsent(
                                current,
                                VoiceTutorStudyTargetTraversal(
                                    singleChildEdges = followedEdges.toList(),
                                    terminalLeafStudyId = current,
                                ),
                            )
                        }
                        return
                    }
                    pages.isCompleteSingleton -> {
                        val child = children.single()
                        if (!nodes.containsKey(child)) return
                        followedEdges += VoiceTutorStudyTargetSingleChildEdge(current, child)
                        current = child
                    }
                    else -> {
                        val traversal = VoiceTutorStudyTargetTraversal(
                            singleChildEdges = followedEdges.toList(),
                        )
                        children.forEach { child -> eligible.putIfAbsent(child, traversal) }
                        return
                    }
                }
            }
        }

        private fun directChildPage(parentStudyId: Long): CandidatePageSeries? =
            directChildPages.entries.singleOrNull { it.key.parentStudyId == parentStudyId }?.value

        private data class QueryPageKey(val query: String, val limit: Long)

        private data class DirectChildrenPageKey(val parentStudyId: Long, val limit: Long)

        private class CandidatePageSeries(
            private val limit: Long,
            private val totalCount: Long,
        ) {
            private val pages = linkedMapOf<Long, List<Long>>()

            val isBranch: Boolean get() = totalCount > 1
            val isCompleteLeaf: Boolean get() = totalCount == 0L && pages[0L]?.isEmpty() == true
            val isCompleteSingleton: Boolean get() = totalCount == 1L && pages[0L]?.size == 1

            fun merge(offset: Long, observedTotalCount: Long, ids: List<Long>): Boolean {
                if (observedTotalCount != totalCount ||
                    !validPage(offset, ids.size) || ids.distinct().size != ids.size
                ) return false
                val previous = pages.putIfAbsent(offset, ids)
                if (previous != null && previous != ids) return false
                val allIds = pages.values.flatten()
                return allIds.distinct().size == allIds.size && pages.size <= MAX_DISCOVERY_PAGE_COUNT
            }

            fun offerableIds(): List<Long>? {
                if (totalCount == 0L) return pages[0L]?.takeIf { it.isEmpty() }
                if (totalCount == 1L) return pages[0L]?.takeIf { it.size == 1 }
                // Only a verified offset-zero branch slice starts authority.
                // Add later slices only while they are contiguous, preserving
                // the server's stable order and the bounded offer window.
                val values = mutableListOf<Long>()
                var offset = 0L
                while (offset < totalCount && values.size < MAX_TARGET_OFFER_CANDIDATES) {
                    val page = pages[offset] ?: break
                    if (!validPage(offset, page.size)) return null
                    values += page
                    offset += limit
                }
                return values.take(MAX_TARGET_OFFER_CANDIDATES).takeIf { it.isNotEmpty() }
            }

            private fun validPage(offset: Long, size: Int): Boolean =
                offset >= 0 && offset % limit == 0L &&
                    ((totalCount == 0L && offset == 0L) || (totalCount > 0L && offset < totalCount)) &&
                    size.toLong() == minOf(limit, totalCount - offset)
        }
    }

    private data class ToolDiscoveryFence(
        val toolName: String,
        val lessonRevision: Long,
        val learnerSpeechSequence: Long,
        val responseGeneration: Long,
    )

    private data class PendingSpokenLessonEnd(
        val speechSequence: Long,
        val waitResponseGeneration: Long?,
    )

    private companion object {
        const val MAX_BUFFERED_CONTROLS = 32
        const val MAX_PENDING_SPEECH_COMMITS = 32
        const val MAX_RECENT_COMMITTED_ITEMS = 64
        const val MAX_PROVIDER_ITEM_ID_CHARACTERS = 191
        const val MAX_TUTOR_CONTEXT_CHARACTERS = 4_000
        const val MAX_TUTOR_CONTEXT_PARTS = 8
        const val MAX_TARGET_OFFER_CANDIDATES = 16
        const val MAX_DISCOVERY_GRAPH_NODES = 64
        const val MAX_DISCOVERY_QUERY_GROUPS = 16
        const val MAX_DISCOVERY_TREE_DEPTH = 32
        const val MAX_DISCOVERY_RESULT_CANDIDATES = 60L
        const val MAX_DISCOVERY_PAGE_LIMIT = 500L
        const val MAX_DISCOVERY_PAGE_COUNT = 60
        val INPUT_DEADLINE_POLL_INTERVAL: Duration = Duration.ofMillis(100)
        val MIN_INPUT_COMMIT_SPACING: Duration = Duration.ofMillis(250)
        const val MAX_RESPONSE_AUDIO_BYTES = 172_800_000L
        const val PCM_BYTES_PER_SECOND = 48_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val PLAYBACK_ACK_GRACE_NANOS = 5_000_000_000L
        const val PLAYBACK_ACK_MIN_NANOS = 5_000_000_000L
        const val DUPLEX_EVENT_PREFIX = "buddystudy-internal-duplex-"
        val TUTOR_TRANSCRIPT_EVENTS = setOf(
            "response.output_audio_transcript.delta",
            "response.output_audio_transcript.done",
        )
        val USER_TRANSCRIPT_EVENTS = setOf(
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.completed",
        )
        val COMPLETING_RESPONSE_STATUSES = setOf("completed", "cancelled", "incomplete")
    }
}

internal data class VoiceTutorProviderRelayDisposition(
    val persist: Boolean,
    val forwardToClient: Boolean,
) {
    companion object {
        val DROP = VoiceTutorProviderRelayDisposition(persist = false, forwardToClient = false)
        val PERSIST_ONLY = VoiceTutorProviderRelayDisposition(persist = true, forwardToClient = false)
        val FORWARD_ONLY = VoiceTutorProviderRelayDisposition(persist = false, forwardToClient = true)
        val FORWARD_AND_PERSIST = VoiceTutorProviderRelayDisposition(persist = true, forwardToClient = true)
    }
}

internal class VoiceTutorProviderResponseTimeoutException : RuntimeException(
    "Voice Tutor provider response timed out.",
)

internal class VoiceTutorProviderPlayoutTimeoutException : RuntimeException(
    "Voice Tutor provider playout completion timed out.",
)

internal class VoiceTutorProviderInputCommitTimeoutException : RuntimeException(
    "Voice Tutor provider input commit acknowledgement timed out.",
)

internal class VoiceTutorProviderInputCorrelationException : RuntimeException(
    "Voice Tutor provider input events could not be correlated.",
)

internal class VoiceTutorPendingInputCommitOverflowException : RuntimeException(
    "Voice Tutor pending input commit limit exceeded.",
)

internal class VoiceTutorProviderOutputBufferClearedException : RuntimeException(
    "Voice Tutor provider cleared an active output buffer.",
)

internal class VoiceTutorProviderIncompleteResponseException : RuntimeException(
    "Voice Tutor provider returned an incomplete realtime response.",
)

internal class VoiceTutorProviderDrainState(
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = JsonMapperProvider.mapper,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var bufferedAudio = false
    private var unidentifiedTranscriptionPending = false
    private val pendingTranscriptionItems = linkedSetOf<String>()
    private var unidentifiedResponseActive = false
    private val activeResponseIds = linkedSetOf<String>()
    private var drainStarted = false
    private var lastRelevantEventNanos = nanoTime()

    @Synchronized
    fun observeClientEvent(raw: String) {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "input_audio_buffer.append" -> {
                bufferedAudio = true
                touch()
            }
            "input_audio_buffer.commit" -> {
                bufferedAudio = false
                unidentifiedTranscriptionPending = true
                touch()
            }
            "response.create" -> {
                unidentifiedResponseActive = true
                touch()
            }
        }
    }

    @Synchronized
    fun observeProviderEvent(raw: String) {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "input_audio_buffer.speech_stopped", "input_audio_buffer.committed" -> {
                bufferedAudio = false
                unidentifiedTranscriptionPending = false
                node.path("item_id").asText().takeIf(String::isNotBlank)?.let(pendingTranscriptionItems::add)
                touch()
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed",
            -> {
                unidentifiedTranscriptionPending = false
                node.path("item_id").asText().takeIf(String::isNotBlank)?.let(pendingTranscriptionItems::remove)
                touch()
            }
            "response.created" -> {
                val responseId = node.path("response").path("id").asText()
                unidentifiedResponseActive = false
                if (responseId.isBlank()) {
                    unidentifiedResponseActive = true
                } else {
                    activeResponseIds.add(responseId)
                }
                touch()
            }
            "response.done" -> {
                val responseId = node.path("response").path("id").asText()
                unidentifiedResponseActive = false
                if (responseId.isBlank()) {
                    activeResponseIds.clear()
                } else {
                    activeResponseIds.remove(responseId)
                }
                touch()
            }
        }
    }

    @Synchronized
    fun beginDrain(): String? {
        if (drainStarted) return null
        drainStarted = true
        touch()
        if (!bufferedAudio) return null
        bufferedAudio = false
        unidentifiedTranscriptionPending = true
        return mapper.writeValueAsString(
            linkedMapOf(
                "event_id" to "$INTERNAL_CONTROL_EVENT_PREFIX-drain-${UUID.randomUUID()}",
                "type" to "input_audio_buffer.commit",
            ),
        )
    }

    fun awaitDrain(maximum: Duration): Mono<Void> = Flux.interval(Duration.ZERO, DRAIN_POLL_INTERVAL)
        .filter { isSettled() }
        .next()
        .then()
        .timeout(maximum)
        .onErrorResume(TimeoutException::class.java) { Mono.empty() }

    @Synchronized
    internal fun isSettled(): Boolean = drainStarted &&
        !bufferedAudio &&
        !unidentifiedTranscriptionPending &&
        pendingTranscriptionItems.isEmpty() &&
        !unidentifiedResponseActive &&
        activeResponseIds.isEmpty() &&
        nanoTime() - lastRelevantEventNanos >= DRAIN_QUIET_NANOS

    private fun touch() {
        lastRelevantEventNanos = nanoTime()
    }

    private companion object {
        const val INTERNAL_CONTROL_EVENT_PREFIX = "buddystudy-internal"
        const val DRAIN_QUIET_NANOS = 150_000_000L
        val DRAIN_POLL_INTERVAL: Duration = Duration.ofMillis(25)
    }
}
