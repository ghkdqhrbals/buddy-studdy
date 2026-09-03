package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorizationPurpose
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorizationScope
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateTargetProof
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationAuthorization
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
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
    initialStudyMutationSnapshot: com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot? = null,
    private val onProviderTurnFailure: (VoiceTutorProviderTurnFailureDiagnostic) -> Unit = {},
) {
    init {
        require(initialLessonRevision >= 0) { "Voice Tutor lesson revision was invalid." }
        require(initialStudyMutationSnapshot?.isValid() != false) {
            "Voice Tutor initial study mutation snapshot was invalid."
        }
    }

    private var currentLessonRevision = initialLessonRevision
    /** Consumed when the first final learner item receives a meaningful dual-semantic verdict. */
    private var initialStudyMutationSnapshot = initialStudyMutationSnapshot
    /** The sole meaningful item allowed to retain an already-frozen initial snapshot copy. */
    private var initialStudyMutationWinnerItemId: String? = null
    /** Pending items whose initial context was revoked after their assessment DTO was already emitted. */
    private val revokedInitialStudyMutationItemIds = linkedSetOf<String>()
    private var activeResponseLessonRevision = initialLessonRevision
    private var activeSpeechLessonRevision: Long? = null
    private val responseLessonRevisions = linkedMapOf<String, Long>()
    private val inputLessonBindings = linkedMapOf<String, InputLessonBinding>()
    private val legacyInputSequences = linkedMapOf<String, Long>()
    private var activeSpeechStartedOrder = 0L
    private var activeSpeechPrecedingTutorStopOrder = 0L
    private var activeSpeechPrecedingSpokenGeneration = 0L
    private var activeSpeechPrecedingTutorProviderItemId: String? = null
    private var activeSpeechPrecedingTutorStudyQuestionEligible = false
    private var activeSpeechPrecedingTutorContinuationEligible = false
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
    private var activeResponseHadCandidateNavigation = false
    private var activeTargetOffer: VoiceTutorStudyTargetOffer? = null
    /**
     * A successful update-only write gets one spoken, revised-name offer. It is
     * deliberately separate from discovery: the old name/revision can never be
     * reused and a later semantic turn can only select, never replay the write.
     */
    private var pendingStudyUpdateSelectionOffer: CandidateOfferPool? = null
    /** Noise may not consume the update follow-up; its next meaningful item does. */
    private var reusableStudyUpdateTargetOfferId: Long? = null
    private var activeTargetOfferExchangeEvidence: TargetOfferExchangeEvidence? = null
    private var activeSpeechTargetOffer: VoiceTutorStudyTargetOffer? = null
    private val toolDiscoveryFences = linkedMapOf<String, ToolDiscoveryFence>()
    private var lastTutorSpeechStoppedOrder = 0L
    private var lastSpokenResponseGeneration = 0L
    private var lastSpokenTutorProviderItemId: String? = null
    private var lastSpokenTutorProviderItemGeneration = 0L
    private var lastSpokenStudyQuestion: SpokenTutorPurposeEvidence? = null
    private var lastSpokenContinuationAnchor: SpokenTutorPurposeEvidence? = null
    private var confirmedStudyFocus: ConfirmedStudyFocus? = null
    private var pendingStudyQuestionPurpose: StudyQuestionPurpose? = null
    private var lastSpokenStudyAnswer: StudyAnswerIdentity? = null
    private var completedStudyAnswerFeedback: StudyAnswerFeedbackEvidence? = null
    private var pendingNavigationFeedback: StudyAnswerFeedbackEvidence? = null
    private var activeResponseRespondsToStudyAnswer = false
    private var activeResponseStudyAnswer: StudyAnswerIdentity? = null
    private var activeResponseRespondsToLearnerQuestion = false
    private var activeResponseStudyQuestionPurpose: StudyQuestionPurpose? = null
    private var activeResponseTutorContext = ""
    private var activeResponseTutorContextForAssessment = ""
    private val controls = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(MAX_BUFFERED_CONTROLS).get())
    private val inputWork = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<VoiceTutorInputTurnCoordinator.Action>(MAX_BUFFERED_CONTROLS).get())
    private val spokenQuestionAssessmentWork = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<VoiceTutorSpokenQuestionAssessmentAction>(1).get())
    private val spokenFeedbackAssessmentWork = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<VoiceTutorSpokenFeedbackAssessmentAction>(1).get())
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
    /**
     * Exact calls dispatched while learner speech still lacks a semantic verdict.
     * The call id remains the coordinator-owned execution fence; only dispatch is delayed.
     */
    private val heldToolActions = linkedMapOf<String, VoiceTutorMcpCall>()
    /** Tool calls emitted to the serial worker but not yet linearized by begin. */
    private val dispatchedToolActions = linkedMapOf<String, VoiceTutorMcpCall>()
    /** Dispatch-time owner proof; its authorization object is invalidated if meaningful speech wins. */
    private val toolDispatchBoundaries = linkedMapOf<String, VoiceTutorDialogueBoundary>()
    /** Exact dialogue proof frozen at the controller begin/claim linearization point. */
    private val toolExecutionBoundaries = linkedMapOf<String, VoiceTutorDialogueBoundary>()
    /** Bounded tombstones prevent post-completion boundary lookups from re-exposing a permit. */
    private val finishedToolCallIds = linkedSetOf<String>()
    private var toolAcknowledgementTimer: Disposable? = null
    private val terminalInputFailure = Sinks.one<Throwable>()
    /** Assessment evidence is frozen here; an async persistence receipt carries only item identity. */
    private val pendingInputPublications = linkedMapOf<String, VoiceTutorInputTurnCoordinator.Action.Publish>()
    /**
     * A checkpoint is durable context only. Its semantic answer contribution is
     * cached by speech sequence and promoted only by the exact final publication.
     */
    private val persistedStudyAnswerSequences = linkedMapOf<Long, PersistedStudyAnswerSequence>()
    private val pendingStudyAnswerGroups = linkedMapOf<String, PendingStudyAnswerGroup>()
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
    private val activeTutorFinalTranscriptEvents = linkedMapOf<String, StagedTutorTranscript>()
    private val activeTutorTranscriptItemIds = linkedSetOf<String>()
    private var activeTutorTranscriptOverflow = false
    private var inputCheckpointTimer: Disposable? = null
    private var inputCheckpointTimerGeneration = 0L
    private var userSpeaking = false
    private var responseActive = false
    private var activeResponseGeneration = 0L
    private var activeResponseCreateEventId: String? = null
    private var activeResponseId: String? = null
    private var activeResponseAllowsTools = false
    private var activeResponseInstructionOverride: String? = null
    private var activeResponseAudioBytes = 0L
    private var earliestResponsePlaybackEndNanos: Long? = null
    private var providerResponseDone = false
    private var playbackCompleted = false
    private var providerOutputBufferStopped = false
    private var providerOutputBufferStarted = false
    private var providerAudioObserved = false
    private var toolOnlyResponse = false
    private var activeResponseAcceptedToolCalls = false
    private var activeResponseToolNames: Set<String> = emptySet()
    private val eligibleFocusToolCallIds = linkedSetOf<String>()
    private var activeResponseRetryAttempt = 0
    private var activeResponseBoundaryCheckpoint: TutorBoundaryCheckpoint? = null
    private var pendingProviderResponseRetry: PendingProviderResponseRetry? = null
    private var rootStudyCreationFollowupPending = false
    /** Monotonic fencing identity for the newest persisted root-create pipeline. */
    private var rootStudyPipelineGeneration = 0L
    private var currentRootStudyPipelineOwner: RootStudyPipelineOwner? = null
    /** Frozen learner-authorized tuple for each exact server-owned root write. */
    private val rootStudyCreationCalls = linkedMapOf<String, RootStudyCreationExpectation>()
    /** Exact server-owned get_study calls required before a root acknowledgement may speak. */
    private val rootStudyReadbackCalls = linkedMapOf<String, RootStudyReadbackExpectation>()
    /** Exact server-owned focus calls authorized only after the matching root readback. */
    private val rootStudyAutoFocusCalls = linkedMapOf<String, RootStudyAutoFocusExpectation>()
    /** Monotonic identity for the newest persisted update-and-start pipeline. */
    private var studyUpdatePipelineGeneration = 0L
    private var currentStudyUpdatePipelineOwner: StudyUpdatePipelineOwner? = null
    /** Exact server-owned update calls and their independently attested optional start permit. */
    private val studyUpdateCalls = linkedMapOf<String, StudyUpdateExpectation>()
    /** Exact server-owned focus calls scheduled only from a confirmed update snapshot. */
    private val studyUpdateAutoFocusCalls = linkedMapOf<String, StudyUpdateAutoFocusExpectation>()
    /** Aggregates create/readback uncertainty without ever replaying the write. */
    private var rootStudyReadbackFailed = false
    /** The root was verified, but its independently attested immediate lesson start was not. */
    private var rootStudyAutoFocusFailed = false
    /** A stale focus committed and was reconciled, but its automatic question was superseded. */
    private var rootStudyCommittedFocusSuperseded = false
    /** A current saved-study action failed closed before its write could execute. */
    private var studyMutationFailureFollowupPending = false
    /** The update committed, but its separately attested immediate lesson focus was not confirmed. */
    private var studyUpdateAutoFocusFailedFollowupPending = false
    /** The revised focus committed, but newer learner speech superseded its automatic first question. */
    private var studyUpdateCommittedFocusSupersededFollowupPending = false
    /** A stale focus result is ACKed to the provider but must not move the client-visible focus. */
    private val suppressedLessonFocusEventCallIds = linkedSetOf<String>()
    private var pendingPostRelayBoundary: PendingPostRelayBoundary? = null
    private var nextPostRelayBoundaryToken = 1L
    private val recentFailedResponseCreateEventIds = LinkedHashSet<String>()
    private val recentFailedResponseIds = LinkedHashSet<String>()
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

    fun spokenQuestionAssessmentActions(): Flux<VoiceTutorSpokenQuestionAssessmentAction> =
        spokenQuestionAssessmentWork.asFlux().filter { !closed }

    fun spokenFeedbackAssessmentActions(): Flux<VoiceTutorSpokenFeedbackAssessmentAction> =
        spokenFeedbackAssessmentWork.asFlux().filter { !closed }

    fun clientEvents(): Flux<String> = clientControls.asFlux().filter { !closed }

    fun serverLifecycleEvents(): Flux<String> = serverLifecycle.asFlux().filter { !closed }

    fun toolActions(): Flux<VoiceTutorMcpCall> = toolWork.asFlux().filter { !closed }

    @Synchronized
    fun mutationDialogueBoundary(): VoiceTutorDialogueBoundary = mutationDialogueBoundaryFor(
        serverOwnedToolName = null,
        exposeUnboundWriteLeases = true,
    )

    /**
     * Write leases are released only to the exact server-owned call which was
     * scheduled from the persisted learner turn. A queued model call with the
     * same arguments must not be able to race that call and consume its lease.
     */
    @Synchronized
    fun mutationDialogueBoundary(callId: String): VoiceTutorDialogueBoundary =
        toolExecutionBoundaries[callId] ?: mutationDialogueBoundaryFor(
            serverOwnedToolName = null,
            exposeUnboundWriteLeases = false,
        ).copy(
            focusAuthorization = null,
            rootStudyCreationAuthorization = null,
            childStudyCreationAuthorization = null,
            studyUpdateAuthorization = null,
        )

    /** Consumed by the relay after result ACK creation; stale root focus must not reach the UI. */
    @Synchronized
    fun shouldRelayLessonFocusEvent(callId: String): Boolean =
        !suppressedLessonFocusEventCallIds.remove(callId)

    private fun mutationDialogueBoundaryFor(
        serverOwnedCallId: String? = null,
        serverOwnedToolName: String?,
        exposeUnboundWriteLeases: Boolean,
    ): VoiceTutorDialogueBoundary {
        val binding = latestFocusIntentBinding
        val createdRootFocus = serverOwnedCallId?.let(rootStudyAutoFocusCalls::get)?.takeIf {
            serverOwnedToolName == SELECT_VOICE_STUDY_TOOL && it.authorization.isActive() &&
                it.authorization.isBoundToServerCall(serverOwnedCallId) &&
                ownsCurrentRootStudyPipeline(it.owner) &&
                binding?.focusAuthorization === it.authorization &&
                binding.providerItemId == it.learnerProviderItemId &&
                binding.lessonRevision == it.lessonRevision
        }
        val updatedStudyFocus = serverOwnedCallId?.let(studyUpdateAutoFocusCalls::get)?.takeIf {
            serverOwnedToolName == SELECT_VOICE_STUDY_TOOL && it.authorization.isActive() &&
                it.authorization.isBoundToServerCall(serverOwnedCallId) &&
                currentStudyUpdatePipelineOwner == it.owner &&
                currentLessonRevision == it.expectedCurrentRevision
        }
        val exposedFocusAuthorization = when {
            createdRootFocus != null -> createdRootFocus.authorization
            updatedStudyFocus != null -> updatedStudyFocus.authorization
            serverOwnedCallId == null &&
                binding?.focusAuthorization?.purpose ==
                VoiceTutorFocusAuthorizationPurpose.SPOKEN_SAVED_TOPIC_CHOICE ->
                binding.focusAuthorization.takeIf { it.isActive() }
            exposeUnboundWriteLeases -> binding?.focusAuthorization?.takeIf { it.isActive() }
            else -> null
        }
        return VoiceTutorDialogueBoundary(
        responseGeneration = activeResponseGeneration,
        latestAcceptedLearnerSpeechStartedOrder = latestAcceptedInputBinding?.speechStartedOrder ?: 0,
        precedingTutorSpeechStoppedOrder = latestAcceptedInputBinding?.precedingTutorSpeechStoppedOrder ?: 0,
        precedingSpokenResponseGeneration = latestAcceptedInputBinding?.precedingSpokenResponseGeneration ?: 0,
        precedingTutorProviderItemId = latestAcceptedInputBinding?.precedingTutorProviderItemId,
        latestAcceptedLearnerProviderItemId = updatedStudyFocus?.owner?.learnerProviderItemId ?:
            latestFocusIntentBinding?.providerItemId,
        latestAcceptedLearnerLessonRevision = updatedStudyFocus?.owner?.sourceLessonRevision ?:
            latestFocusIntentBinding?.lessonRevision ?: -1,
        latestAcceptedLearnerIntent = if (updatedStudyFocus != null) VoiceTutorInputIntent.UPDATE_STUDY else
            latestFocusIntentBinding?.inputIntent ?: VoiceTutorInputIntent.NONE,
        precedingTutorFeedbackForStudyAnswer =
            latestFocusIntentBinding?.precedingTutorFeedbackForStudyAnswer ?: false,
        precedingQuestionProviderItemId = latestFocusIntentBinding?.precedingQuestionProviderItemId,
        precedingAnswerProviderItemId = latestFocusIntentBinding?.precedingAnswerProviderItemId,
        precedingTutorFeedbackProviderItemId = latestFocusIntentBinding?.precedingTutorFeedbackProviderItemId,
        precedingTutorNavigationOfferProviderItemId =
            latestFocusIntentBinding?.precedingTutorNavigationOfferProviderItemId,
        latestAcceptedLearnerTargetStudyId = createdRootFocus?.studyId ?: updatedStudyFocus?.candidate?.studyId ?:
            latestFocusIntentBinding?.targetStudyId,
        latestAcceptedLearnerTargetOfferId = latestFocusIntentBinding?.targetOfferId,
        latestAcceptedLearnerTargetCandidate = createdRootFocus?.candidate ?: updatedStudyFocus?.candidate ?:
            latestFocusIntentBinding?.let { targetBinding ->
            targetBinding.targetStudyId?.let { target ->
                targetBinding.targetOffer?.candidates?.singleOrNull { it.studyId == target }
            }
        },
        latestAcceptedLearnerTargetTraversal = createdRootFocus?.traversal ?: updatedStudyFocus?.traversal ?:
            latestFocusIntentBinding?.let {
                targetBinding ->
            targetBinding.targetStudyId?.let { target -> targetBinding.targetOffer?.candidateTraversals?.get(target) }
        },
        focusAuthorization = exposedFocusAuthorization,
        rootStudyCreationAuthorization = latestFocusIntentBinding
            ?.rootStudyCreationAuthorization?.takeIf {
                it.isActive() && (exposeUnboundWriteLeases ||
                    serverOwnedToolName == CREATE_ROOT_STUDY_TOOL && serverOwnedCallId != null &&
                    it.isBoundToServerCall(serverOwnedCallId))
            },
        childStudyCreationAuthorization = latestFocusIntentBinding
            ?.childStudyCreationAuthorization?.takeIf {
                it.isActive() && (exposeUnboundWriteLeases ||
                    serverOwnedToolName == CREATE_STUDY_TOPIC_TOOL && serverOwnedCallId != null &&
                    it.isBoundToServerCall(serverOwnedCallId))
            },
        studyUpdateAuthorization = latestFocusIntentBinding
            ?.studyUpdateAuthorization?.takeIf {
                it.isActive() && (exposeUnboundWriteLeases ||
                    serverOwnedToolName == UPDATE_STUDY_TOOL && serverOwnedCallId != null &&
                    it.isBoundToServerCall(serverOwnedCallId))
            },
        focusExpectedCurrentLessonRevision = updatedStudyFocus?.expectedCurrentRevision,
        )
    }

    fun beginToolExecution(callId: String): Boolean = claimToolExecution(callId) != null

    /**
     * Atomic execution linearization for the serial relay. The returned boundary
     * is the only proof the adapter may use; a later learner publication cannot
     * swap it for a different owner between begin and adapter consumption.
     */
    @Synchronized
    internal fun claimToolExecution(callId: String): VoiceTutorDialogueBoundary? {
        if (closed || callId in heldToolActions) return null
        val coordinator = toolCoordinator ?: return null
        val call = dispatchedToolActions[callId] ?: return null
        val dispatchBoundary = toolDispatchBoundaries[callId] ?: run {
            terminate(VoiceTutorMcpProtocolException())
            return null
        }
        if (semanticLearnerInputPending() &&
            dispatchBoundary.hasClaimableAuthorizationFor(call.name)
        ) {
            dispatchedToolActions.remove(callId)
            if (heldToolActions.putIfAbsent(callId, call) != null) {
                terminate(VoiceTutorMcpProtocolException())
            }
            return null
        }
        if (!coordinator.beginExecution(callId)) return null
        val toolName = coordinator.startedToolName(callId) ?: return null
        if (toolName != call.name) {
            terminate(VoiceTutorMcpProtocolException())
            return null
        }
        dispatchedToolActions.remove(callId)
        toolDispatchBoundaries.remove(callId)
        val claimed = when (toolName) {
            CREATE_ROOT_STUDY_TOOL -> dispatchBoundary.rootStudyCreationAuthorization
                ?.claimExecution(callId) == true
            CREATE_STUDY_TOPIC_TOOL -> dispatchBoundary.childStudyCreationAuthorization
                ?.claimExecution(callId) == true
            UPDATE_STUDY_TOOL -> dispatchBoundary.studyUpdateAuthorization
                ?.claimExecution(callId) == true
            in STUDY_FOCUS_TOOLS -> dispatchBoundary.focusAuthorization
                ?.claimExecution(callId) == true
            else -> false
        }
        val requiresClaim = toolName == CREATE_ROOT_STUDY_TOOL ||
            toolName == CREATE_STUDY_TOPIC_TOOL || toolName == UPDATE_STUDY_TOOL ||
            toolName in STUDY_FOCUS_TOOLS
        val executionBoundary = dispatchBoundary.takeIf { !requiresClaim || claimed }
            ?: dispatchBoundary.copy(
                focusAuthorization = null,
                rootStudyCreationAuthorization = null,
                childStudyCreationAuthorization = null,
                studyUpdateAuthorization = null,
            )
        toolExecutionBoundaries[callId] = executionBoundary
        candidateReadKind(toolName)?.let {
            toolDiscoveryFences[callId] = ToolDiscoveryFence(
                toolName = toolName,
                lessonRevision = currentLessonRevision,
                learnerSpeechSequence = lastClientSpeechSequence,
                responseGeneration = activeResponseGeneration,
            )
        }
        return executionBoundary
    }

    private fun semanticLearnerInputPending(): Boolean = inputCoordinator != null && (
        userSpeaking || activeClientSpeechSequence != null || pendingSpeechCommitCount > 0 ||
            pendingInputCommits.isNotEmpty() || delayedStopCommit != null || inputCoordinator.hasPending
        )

    /**
     * Semantic input fences only one-shot study mutations. Read-only and otherwise
     * unauthorized calls may execute while input is assessed; the response gate
     * still prevents their output ACK from speaking before persistence or deletion.
     */
    private fun VoiceTutorDialogueBoundary.hasClaimableAuthorizationFor(toolName: String): Boolean = when (toolName) {
        CREATE_ROOT_STUDY_TOOL -> rootStudyCreationAuthorization?.isActive() == true
        CREATE_STUDY_TOPIC_TOOL -> childStudyCreationAuthorization?.isActive() == true
        UPDATE_STUDY_TOOL -> studyUpdateAuthorization?.isActive() == true
        in STUDY_FOCUS_TOOLS -> focusAuthorization?.isActive() == true
        else -> false
    }

    private fun freezeToolDispatchBoundary(
        call: VoiceTutorMcpCall,
        serverOwned: Boolean,
    ): VoiceTutorDialogueBoundary {
        val raw = mutationDialogueBoundaryFor(
            serverOwnedCallId = call.callId.takeIf { serverOwned },
            serverOwnedToolName = call.name.takeIf { serverOwned },
            exposeUnboundWriteLeases = false,
        )
        return raw.copy(
            focusAuthorization = raw.focusAuthorization.takeIf { call.name in STUDY_FOCUS_TOOLS },
            rootStudyCreationAuthorization = raw.rootStudyCreationAuthorization
                .takeIf { call.name == CREATE_ROOT_STUDY_TOOL },
            childStudyCreationAuthorization = raw.childStudyCreationAuthorization
                .takeIf { call.name == CREATE_STUDY_TOPIC_TOOL },
            studyUpdateAuthorization = raw.studyUpdateAuthorization
                .takeIf { call.name == UPDATE_STUDY_TOOL },
        )
    }

    private fun dispatchToolAction(call: VoiceTutorMcpCall) {
        if (dispatchedToolActions.putIfAbsent(call.callId, call) != null) {
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        val emitted = toolWork.tryEmitNext(call)
        if (emitted.isFailure) {
            dispatchedToolActions.remove(call.callId)
            toolDispatchBoundaries.remove(call.callId)
            if (!closed) terminate(VoiceTutorMcpProtocolException())
        }
    }

    private fun dispatchOrHoldToolAction(call: VoiceTutorMcpCall, serverOwned: Boolean) {
        if (call.callId in toolDispatchBoundaries || call.callId in heldToolActions ||
            call.callId in dispatchedToolActions || call.callId in toolExecutionBoundaries ||
            call.callId in finishedToolCallIds
        ) {
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        val dispatchBoundary = freezeToolDispatchBoundary(call, serverOwned)
        toolDispatchBoundaries[call.callId] = dispatchBoundary
        if (semanticLearnerInputPending() &&
            dispatchBoundary.hasClaimableAuthorizationFor(call.name)
        ) {
            heldToolActions[call.callId] = call
        } else {
            dispatchToolAction(call)
        }
    }

    private fun releaseHeldToolActionsIfReady() {
        if (closed || semanticLearnerInputPending() || heldToolActions.isEmpty()) return
        val ready = heldToolActions.values.toList()
        heldToolActions.clear()
        ready.forEach(::dispatchToolAction)
    }

    @Synchronized
    fun completeToolExecution(callId: String, result: VoiceTutorMcpToolResult): Boolean {
        if (closed) return false
        val startedToolName = toolCoordinator?.startedToolName(callId) ?: return false
        val serverOwnedToolName = toolCoordinator.startedServerToolName(callId)
        val eligibleFocusCall = eligibleFocusToolCallIds.remove(callId)
        val completedRootCreation = rootStudyCreationCalls.remove(callId).takeIf {
            serverOwnedToolName == CREATE_ROOT_STUDY_TOOL && startedToolName == CREATE_ROOT_STUDY_TOOL
        }
        val completedRootReadback = rootStudyReadbackCalls.remove(callId).takeIf {
            serverOwnedToolName == GET_STUDY_TOOL && startedToolName == GET_STUDY_TOOL
        }
        val completedRootAutoFocus = rootStudyAutoFocusCalls.remove(callId).takeIf {
            serverOwnedToolName == SELECT_VOICE_STUDY_TOOL && startedToolName == SELECT_VOICE_STUDY_TOOL
        }
        val completedStudyUpdate = studyUpdateCalls.remove(callId).takeIf {
            serverOwnedToolName == UPDATE_STUDY_TOOL && startedToolName == UPDATE_STUDY_TOOL
        }
        val completedStudyUpdateAutoFocus = studyUpdateAutoFocusCalls.remove(callId).takeIf {
            serverOwnedToolName == SELECT_VOICE_STUDY_TOOL && startedToolName == SELECT_VOICE_STUDY_TOOL
        }
        val completedStudyUpdateOwner = completedStudyUpdate?.owner ?: completedStudyUpdateAutoFocus?.owner
        val ownsCurrentStudyUpdatePipeline = completedStudyUpdateOwner != null &&
            currentStudyUpdatePipelineOwner == completedStudyUpdateOwner
        val trustedUpdatedSnapshot = completedStudyUpdate?.let { expected ->
            trustedUpdatedStudySnapshot(result, expected)
        }
        val staleCommittedStudyUpdateStart = completedStudyUpdate?.startLessonAuthorization != null &&
            !ownsCurrentStudyUpdatePipeline && trustedUpdatedSnapshot != null
        val trustedSupersededStudyUpdateFocus = completedStudyUpdateAutoFocus
            ?.takeIf { !ownsCurrentStudyUpdatePipeline }
            ?.let { expected -> trustedSupersededStudyUpdateFocus(result, expected) }
        val completedRootOwner = completedRootCreation?.owner ?: completedRootReadback?.owner ?:
            completedRootAutoFocus?.owner
        val staleRootPipelineCompletion = completedRootOwner?.let { owner ->
            serverOwnedToolName == startedToolName &&
                serverOwnedToolName in ROOT_STUDY_PIPELINE_TOOLS &&
                !ownsCurrentRootStudyPipeline(owner)
        } ?: false
        val providerResult = when {
            staleCommittedStudyUpdateStart -> normalizeStaleStudyUpdateStartResult(result)
            trustedSupersededStudyUpdateFocus != null ->
                normalizeSupersededStudyUpdateFocusResult(result)
            else -> result
        }
        val event = try {
            toolCoordinator?.complete(callId, providerResult, nanoTime()) ?: return false
        } catch (error: Exception) {
            terminate(error)
            return false
        }
        toolExecutionBoundaries.remove(callId)
        finishedToolCallIds += callId
        val discoveryFence = toolDiscoveryFences.remove(callId)
        if (staleCommittedStudyUpdateStart) {
            // The provider sees the normalized result below and must make a fresh
            // selection. Keep the compact iOS focus event on the same boundary:
            // a typed focus accidentally returned with the superseded write must
            // not resurrect the retired update-and-start turn in the UI.
            suppressedLessonFocusEventCallIds += callId
        }
        if (staleRootPipelineCompletion) {
            val committedStaleFocus = completedRootAutoFocus?.let { expected ->
                result.lessonFocus?.takeIf { focus ->
                    !result.isError && focus.studyId == expected.studyId &&
                        focus.revision > currentLessonRevision &&
                        focus.snapshot.studyId == expected.studyId &&
                        focus.snapshot.parentStudyId == null &&
                        focus.snapshot.topic == expected.topic &&
                        focus.snapshot.difficulty == expected.difficulty &&
                        focus.snapshot.revision == focus.revision &&
                        result.lessonRevision == focus.revision
                }
            }
            completedRootCreation?.creationAuthorization?.invalidate()
            completedRootCreation?.startLessonAuthorization?.invalidate()
            completedRootReadback?.startLessonAuthorization?.invalidate()
            completedRootAutoFocus?.authorization?.invalidate()
            if (committedStaleFocus == null) {
                suppressedLessonFocusEventCallIds += callId
            } else {
                // The old owner lost conversational authority while its persistence
                // transaction was in flight, but this exact typed result proves that
                // transaction committed. Reconcile the controller and UI to the
                // authoritative revision, revoke every newer stale-revision pipeline,
                // and keep question authority closed until a fresh learner choice.
                latestFocusIntentBinding?.focusAuthorization?.invalidate()
                latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
                latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
                latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
                latestFocusIntentBinding = null
                rootStudyCreationCalls.values.forEach { expectation ->
                    expectation.creationAuthorization.invalidate()
                    expectation.startLessonAuthorization?.invalidate()
                }
                rootStudyReadbackCalls.values.forEach { it.startLessonAuthorization?.invalidate() }
                rootStudyAutoFocusCalls.values.forEach { it.authorization.invalidate() }
                eligibleFocusToolCallIds.removeAll(rootStudyAutoFocusCalls.keys)
                currentRootStudyPipelineOwner = null
                persistedStudyAnswerSequences.clear()
                pendingStudyAnswerGroups.clear()
                clearTargetDiscovery()
                currentLessonRevision = committedStaleFocus.revision
                confirmedStudyFocus = ConfirmedStudyFocus(
                    committedStaleFocus.studyId,
                    committedStaleFocus.revision,
                    committedStaleFocus.snapshot.parentStudyId,
                    committedStaleFocus.topic,
                    committedStaleFocus.difficulty,
                )
                discardStudyQuestionPurpose()
                rootStudyCreationFollowupPending = true
                rootStudyReadbackFailed = false
                rootStudyAutoFocusFailed = false
                rootStudyCommittedFocusSuperseded = true
            }
            emit(event)
            ensureToolAcknowledgementTimer()
            return !closed
        }
        if (trustedSupersededStudyUpdateFocus != null) {
            completedStudyUpdateAutoFocus?.authorization?.invalidate()
            reconcileSupersededStudyUpdateFocus(trustedSupersededStudyUpdateFocus)
            emit(event)
            ensureToolAcknowledgementTimer()
            return !closed
        }
        var scheduledStudyUpdateAutoFocus: VoiceTutorScheduledMcpCall? = null
        if (completedStudyUpdate != null) {
            completedStudyUpdate.updateAuthorization.invalidate()
            val startAuthorization = completedStudyUpdate.startLessonAuthorization
            when {
                trustedUpdatedSnapshot == null -> {
                    startAuthorization?.invalidate()
                    if (ownsCurrentStudyUpdatePipeline) {
                        currentStudyUpdatePipelineOwner = null
                        if (startAuthorization != null && !result.isError && result.studyTreeChanged) {
                            studyUpdateAutoFocusFailedFollowupPending = true
                            studyUpdateCommittedFocusSupersededFollowupPending = false
                        } else if (result.isError) {
                            studyMutationFailureFollowupPending = true
                        }
                        discardStudyQuestionPurpose()
                    }
                }
                staleCommittedStudyUpdateStart -> {
                    // The write linearized before a newer persisted learner turn
                    // retired its conversational owner. Preserve the committed
                    // metadata, but close the now-unexecutable start lease and
                    // replace AUTO_FOCUS_PENDING with one terminal no-tools
                    // follow-up. The write is never replayed and no stale focus is
                    // scheduled from the superseded turn.
                    startAuthorization?.invalidate()
                    studyUpdateAutoFocusFailedFollowupPending = true
                    studyUpdateCommittedFocusSupersededFollowupPending = false
                    discardStudyQuestionPurpose()
                }
                startAuthorization != null && ownsCurrentStudyUpdatePipeline &&
                    currentLessonRevision == completedStudyUpdate.owner.sourceLessonRevision -> {
                    advanceLessonRevisionForUpdatedStudyStart(
                        trustedUpdatedSnapshot.revision,
                        startAuthorization,
                    )
                    scheduledStudyUpdateAutoFocus = scheduleServerStudyUpdateAutoFocus(
                        completedStudyUpdate,
                        trustedUpdatedSnapshot,
                    )
                    if (scheduledStudyUpdateAutoFocus == null) {
                        startAuthorization.invalidate()
                        currentStudyUpdatePipelineOwner = null
                        studyUpdateAutoFocusFailedFollowupPending = true
                        studyUpdateCommittedFocusSupersededFollowupPending = false
                        discardStudyQuestionPurpose()
                    }
                }
                else -> {
                    startAuthorization?.invalidate()
                    if (ownsCurrentStudyUpdatePipeline) currentStudyUpdatePipelineOwner = null
                }
            }
        }
        result.candidateDiscovery?.takeIf { discovery ->
            !result.isError && discoveryFence != null &&
                candidateReadKind(discoveryFence.toolName) == discovery.source &&
                discovery.lessonRevision == discoveryFence.lessonRevision &&
                discovery.lessonRevision == currentLessonRevision &&
                discoveryFence.learnerSpeechSequence == lastClientSpeechSequence &&
                activeClientSpeechSequence == null &&
                discoveryFence.responseGeneration == activeResponseGeneration
        }?.let(::mergePendingCandidateDiscovery)
        val confirmedFocus = result.lessonFocus?.takeIf { focus ->
            !result.isError && (startedToolName in STUDY_FOCUS_TOOLS ||
                startedToolName == UPDATE_STUDY_TOOL && serverOwnedToolName == UPDATE_STUDY_TOOL) &&
                !(completedStudyUpdate?.startLessonAuthorization != null &&
                    startedToolName == UPDATE_STUDY_TOOL) &&
                focus.studyId > 0 && focus.revision > 0 &&
                focus.snapshot.studyId == focus.studyId && result.lessonRevision == focus.revision &&
                focus.snapshot.revision == focus.revision &&
                focus.snapshot.topic.isNotBlank() && focus.snapshot.topic.length <= 255 &&
                focus.snapshot.difficulty in 1..10 &&
                focus.snapshot.parentStudyId?.let { it > 0 } != false
        }?.takeIf { focus ->
            completedRootAutoFocus == null ||
                focus.studyId == completedRootAutoFocus.studyId &&
                focus.snapshot.parentStudyId == null &&
                focus.snapshot.topic == completedRootAutoFocus.topic &&
                focus.snapshot.difficulty == completedRootAutoFocus.difficulty
        }?.takeIf { focus ->
            completedStudyUpdateAutoFocus == null ||
                focus.revision >= completedStudyUpdateAutoFocus.expectedCurrentRevision &&
                focus.studyId == completedStudyUpdateAutoFocus.candidate.studyId &&
                focus.snapshot.parentStudyId == completedStudyUpdateAutoFocus.candidate.parentStudyId &&
                focus.snapshot.topic == completedStudyUpdateAutoFocus.candidate.topic &&
                focus.snapshot.difficulty == completedStudyUpdateAutoFocus.candidate.difficulty
        }
        if (!result.isError && (completedRootAutoFocus == null || confirmedFocus != null)) {
            // Only a matched, server-executed result advances future responses.
            // Existing response IDs and speech/commit slots keep their old epoch.
            result.lessonRevision?.takeIf { it >= 0 }?.let { revision ->
                if (revision != currentLessonRevision) {
                    latestFocusIntentBinding?.focusAuthorization?.invalidate()
                    latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
                    latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
                    latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
                    latestFocusIntentBinding = null
                    persistedStudyAnswerSequences.clear()
                    pendingStudyAnswerGroups.clear()
                    clearTargetDiscovery()
                }
                currentLessonRevision = maxOf(currentLessonRevision, revision)
            }
        }
        val completedServerOwnedUpdate = !result.isError &&
            startedToolName == UPDATE_STUDY_TOOL && serverOwnedToolName == UPDATE_STUDY_TOOL
        when {
            !result.isError && result.lessonFocusCleared && result.studyTreeChanged &&
                result.changeKind == com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind.DELETED &&
                result.changedStudyId in result.deletedStudyIds -> {
                confirmedStudyFocus = null
                discardStudyQuestionPurpose()
            }
            completedServerOwnedUpdate && confirmedFocus == null -> {
                // The metadata write committed, but the new revision/focus could not be
                // frozen. Keeping the old snapshot would let the tutor ask at a stale
                // title or level, so require an explicit saved-topic re-selection.
                confirmedStudyFocus = null
                discardStudyQuestionPurpose()
            }
            completedRootAutoFocus != null && confirmedFocus == null -> {
                confirmedStudyFocus = null
                discardStudyQuestionPurpose()
            }
            confirmedFocus != null -> {
                confirmedStudyFocus = ConfirmedStudyFocus(
                    confirmedFocus.studyId,
                    confirmedFocus.revision,
                    confirmedFocus.snapshot.parentStudyId,
                    confirmedFocus.topic,
                    confirmedFocus.difficulty,
                )
                pendingStudyQuestionPurpose = StudyQuestionPurpose(
                    confirmedFocus.revision,
                    StudyQuestionPurposeSource.FOCUS_TOOL,
                ).takeIf { eligibleFocusCall }
            }
            result.isError || startedToolName !in SAFE_PURPOSE_PRESERVING_READ_TOOLS ->
                discardStudyQuestionPurpose()
        }
        if (completedRootAutoFocus != null) {
            if (confirmedFocus == null) {
                completedRootAutoFocus.authorization.invalidate()
                suppressedLessonFocusEventCallIds += callId
                rootStudyCreationFollowupPending = true
                rootStudyAutoFocusFailed = true
                rootStudyCommittedFocusSuperseded = false
            } else {
                rootStudyCreationFollowupPending = false
                rootStudyAutoFocusFailed = false
                rootStudyCommittedFocusSuperseded = false
            }
        }
        if (completedStudyUpdateAutoFocus != null) {
            completedStudyUpdateAutoFocus.authorization.invalidate()
            if (confirmedFocus == null) {
                suppressedLessonFocusEventCallIds += callId
                if (ownsCurrentStudyUpdatePipeline) {
                    currentStudyUpdatePipelineOwner = null
                    studyUpdateAutoFocusFailedFollowupPending = true
                    studyUpdateCommittedFocusSupersededFollowupPending = false
                    discardStudyQuestionPurpose()
                }
            } else if (ownsCurrentStudyUpdatePipeline) {
                currentStudyUpdatePipelineOwner = null
                studyUpdateAutoFocusFailedFollowupPending = false
                studyUpdateCommittedFocusSupersededFollowupPending = false
            }
        }
        if (completedStudyUpdate != null && completedStudyUpdate.startLessonAuthorization == null &&
            ownsCurrentStudyUpdatePipeline && trustedUpdatedSnapshot != null &&
            trustedUpdatedSnapshot.revision == currentLessonRevision &&
            completedStudyUpdate.updateAuthorization.scope !=
                VoiceTutorStudyUpdateAuthorizationScope.CONFIRMED_FOCUS_TREE
        ) {
            val revisedCandidate = VoiceTutorStudyTargetCandidate(
                trustedUpdatedSnapshot.studyId,
                trustedUpdatedSnapshot.parentStudyId,
                trustedUpdatedSnapshot.topic,
                trustedUpdatedSnapshot.difficulty,
            )
            val traversal = completedStudyUpdate.traversal
            pendingStudyUpdateSelectionOffer = CandidateOfferPool(
                lessonRevision = trustedUpdatedSnapshot.revision,
                currentFocusStudyId = null,
                candidates = listOf(revisedCandidate),
                candidateTraversals = mapOf(revisedCandidate.studyId to traversal),
                purpose = CandidateOfferPurpose.UPDATED_STUDY_SELECTION,
            ).takeIf { traversal.isValidFor(revisedCandidate, MAX_DISCOVERY_TREE_DEPTH) }
        }
        var scheduledRootReadback: VoiceTutorScheduledMcpCall? = null
        var scheduledRootAutoFocus: VoiceTutorScheduledMcpCall? = null
        if (serverOwnedToolName == CREATE_ROOT_STUDY_TOOL && startedToolName == CREATE_ROOT_STUDY_TOOL) {
            // Creation authority is already consumed before the write starts.
            // A successful result owns exactly one independent readback; an
            // uncertain result can only produce a non-confirming spoken status.
            rootStudyCreationFollowupPending = true
            rootStudyCommittedFocusSuperseded = false
            val readback = completedRootCreation?.let { trustedRootStudyReadback(result, it) }
            if (readback == null) {
                completedRootCreation?.startLessonAuthorization?.invalidate()
                rootStudyReadbackFailed = true
            } else {
                scheduledRootReadback = try {
                    requireNotNull(toolCoordinator).scheduleServerCall(
                        name = GET_STUDY_TOOL,
                        arguments = linkedMapOf("study_id" to readback.studyId),
                        nowNanos = nanoTime(),
                    ).also { scheduled ->
                        rootStudyReadbackCalls[scheduled.callId] = readback
                    }
                } catch (_: VoiceTutorMcpProtocolException) {
                    // The create may already have committed. Never turn a
                    // scheduling failure into permission to retry that write.
                    readback.startLessonAuthorization?.invalidate()
                    rootStudyReadbackFailed = true
                    null
                }
            }
        }
        if (serverOwnedToolName == GET_STUDY_TOOL && startedToolName == GET_STUDY_TOOL) {
            completedRootReadback?.let { expected ->
                if (!confirmsExactRootStudyReadback(result, expected)) {
                    expected.startLessonAuthorization?.invalidate()
                    rootStudyReadbackFailed = true
                } else if (expected.startLessonRequested) {
                    scheduledRootAutoFocus = scheduleServerRootAutoFocus(expected)
                    if (scheduledRootAutoFocus == null) {
                        expected.startLessonAuthorization?.invalidate()
                        rootStudyAutoFocusFailed = true
                        rootStudyCommittedFocusSuperseded = false
                    } else {
                        rootStudyCreationFollowupPending = false
                        rootStudyCommittedFocusSuperseded = false
                    }
                }
            }
        }
        emit(event)
        scheduledRootReadback?.let { emit(it.providerEvent) }
        scheduledRootAutoFocus?.let { emit(it.providerEvent) }
        scheduledStudyUpdateAutoFocus?.let { emit(it.providerEvent) }
        ensureToolAcknowledgementTimer()
        return !closed
    }

    private fun trustedUpdatedStudySnapshot(
        result: VoiceTutorMcpToolResult,
        expected: StudyUpdateExpectation,
    ): VoiceTutorStudySnapshot? {
        if (result.isError || !result.studyTreeChanged ||
            result.changeKind !=
            com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind.UPDATED ||
            result.changedStudyId != expected.targetProof.studyId
        ) return null
        val snapshot = result.updatedStudySnapshot ?: return null
        if (result.lessonRevision != snapshot.revision ||
            snapshot.revision <= expected.owner.sourceLessonRevision ||
            snapshot.studyId != expected.targetProof.studyId ||
            snapshot.parentStudyId != expected.targetProof.parentStudyId ||
            snapshot.topic != (expected.topic ?: expected.targetProof.topic) ||
            snapshot.difficulty !in 1..10 ||
            expected.difficulty?.let { it != snapshot.difficulty } == true ||
            (expected.difficulty == null && expected.targetProof.difficulty?.let {
                it != snapshot.difficulty
            } == true)
        ) return null
        return snapshot
    }

    /**
     * AUTO_FOCUS_PENDING is a promise that the controller will emit an exact
     * server-owned focus call. Once newer persisted speech retires that owner,
     * expose a terminal re-selection state instead of leaving an impossible
     * promise in provider conversation context.
     */
    private fun normalizeStaleStudyUpdateStartResult(
        result: VoiceTutorMcpToolResult,
    ): VoiceTutorMcpToolResult {
        val output = runCatching { mapper.readTree(result.output) }
            .getOrNull()
            ?.takeIf { it.isObject }
            ?.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            ?: return result
        output.put("voiceLessonChangeApplies", "REQUIRES_SELECTION")
        output.put("voiceLessonContextReady", false)
        output.remove("voiceLessonFocus")
        output.put(
            "notice",
            "The exact saved node was updated, but its superseded immediate-start request can no longer be focused. " +
                "Do not retry the write or claim that teaching started; wait for a fresh topic choice.",
        )
        return result.copy(
            output = mapper.writeValueAsString(output),
            lessonFocus = null,
        )
    }

    private fun trustedSupersededStudyUpdateFocus(
        result: VoiceTutorMcpToolResult,
        expected: StudyUpdateAutoFocusExpectation,
    ): VoiceTutorLessonFocusSelection? {
        val focus = result.lessonFocus ?: return null
        if (result.isError || result.lessonRevision != focus.revision ||
            focus.revision < currentLessonRevision ||
            focus.revision < expected.expectedCurrentRevision ||
            focus.snapshot.revision != focus.revision ||
            focus.studyId != expected.candidate.studyId ||
            focus.snapshot.studyId != expected.candidate.studyId ||
            focus.snapshot.parentStudyId != expected.candidate.parentStudyId ||
            focus.snapshot.topic != expected.candidate.topic ||
            focus.snapshot.difficulty != expected.candidate.difficulty
        ) return null
        return focus
    }

    /** Keep the committed focus visible while withdrawing only its stale automatic-question authority. */
    private fun normalizeSupersededStudyUpdateFocusResult(
        result: VoiceTutorMcpToolResult,
    ): VoiceTutorMcpToolResult {
        val output = runCatching { mapper.readTree(result.output) }
            .getOrNull()
            ?.takeIf { it.isObject }
            ?.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            ?: return result
        output.put("voiceLessonFocusChange", "FOCUS_COMMITTED_QUESTION_SUPERSEDED")
        output.put(
            "notice",
            "The revised saved-study focus committed, but newer learner speech superseded its automatic first question. " +
                "Do not ask that question or retry the update or selection; wait for the server's no-tools follow-up.",
        )
        return result.copy(output = mapper.writeValueAsString(output))
    }

    private fun reconcileSupersededStudyUpdateFocus(focus: VoiceTutorLessonFocusSelection) {
        latestFocusIntentBinding?.focusAuthorization?.invalidate()
        latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
        latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
        latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
        latestFocusIntentBinding = null
        rootStudyCreationCalls.values.forEach { expectation ->
            expectation.creationAuthorization.invalidate()
            expectation.startLessonAuthorization?.invalidate()
        }
        rootStudyReadbackCalls.values.forEach { it.startLessonAuthorization?.invalidate() }
        rootStudyAutoFocusCalls.values.forEach { it.authorization.invalidate() }
        eligibleFocusToolCallIds.removeAll(rootStudyAutoFocusCalls.keys)
        currentRootStudyPipelineOwner = null
        studyUpdateCalls.values.forEach { expectation ->
            expectation.updateAuthorization.invalidate()
            expectation.startLessonAuthorization?.invalidate()
        }
        studyUpdateAutoFocusCalls.values.forEach { it.authorization.invalidate() }
        eligibleFocusToolCallIds.removeAll(studyUpdateAutoFocusCalls.keys)
        currentStudyUpdatePipelineOwner = null
        persistedStudyAnswerSequences.clear()
        pendingStudyAnswerGroups.clear()
        clearTargetDiscovery()
        currentLessonRevision = focus.revision
        confirmedStudyFocus = ConfirmedStudyFocus(
            focus.studyId,
            focus.revision,
            focus.snapshot.parentStudyId,
            focus.topic,
            focus.difficulty,
        )
        discardStudyQuestionPurpose()
        rootStudyCreationFollowupPending = false
        rootStudyReadbackFailed = false
        rootStudyAutoFocusFailed = false
        rootStudyCommittedFocusSuperseded = false
        studyMutationFailureFollowupPending = false
        studyUpdateAutoFocusFailedFollowupPending = false
        studyUpdateCommittedFocusSupersededFollowupPending = true
    }

    /**
     * The update revision is authoritative, but the independently attested
     * start lease must survive until its exact server-owned focus call is bound.
     */
    private fun advanceLessonRevisionForUpdatedStudyStart(
        revision: Long,
        startAuthorization: VoiceTutorFocusAuthorization,
    ) {
        if (revision <= currentLessonRevision ||
            latestFocusIntentBinding?.focusAuthorization !== startAuthorization
        ) return
        latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
        latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
        latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
        persistedStudyAnswerSequences.clear()
        pendingStudyAnswerGroups.clear()
        clearTargetDiscovery()
        confirmedStudyFocus = null
        currentLessonRevision = revision
        discardStudyQuestionPurpose()
    }

    private fun scheduleServerStudyUpdateAutoFocus(
        expected: StudyUpdateExpectation,
        snapshot: VoiceTutorStudySnapshot,
    ): VoiceTutorScheduledMcpCall? {
        val authorization = expected.startLessonAuthorization?.takeIf {
            it.isActive() && it.purpose == VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START
        } ?: return null
        if (currentStudyUpdatePipelineOwner != expected.owner ||
            currentLessonRevision != snapshot.revision ||
            snapshot.revision <= expected.owner.sourceLessonRevision ||
            latestFocusIntentBinding?.providerItemId != expected.owner.learnerProviderItemId ||
            latestFocusIntentBinding?.lessonRevision != expected.owner.sourceLessonRevision ||
            latestFocusIntentBinding?.focusAuthorization !== authorization
        ) return null
        val candidate = VoiceTutorStudyTargetCandidate(
            snapshot.studyId,
            snapshot.parentStudyId,
            snapshot.topic,
            snapshot.difficulty,
        )
        if (!expected.traversal.isValidFor(candidate, MAX_DISCOVERY_TREE_DEPTH)) return null
        val coordinator = toolCoordinator ?: return null
        val scheduled = try {
            coordinator.scheduleServerCall(
                name = SELECT_VOICE_STUDY_TOOL,
                arguments = linkedMapOf("study_id" to snapshot.studyId),
                nowNanos = nanoTime(),
            )
        } catch (_: VoiceTutorMcpProtocolException) {
            return null
        }
        if (!authorization.bindToServerCall(scheduled.callId)) {
            authorization.invalidate()
            terminate(VoiceTutorMcpProtocolException())
            return null
        }
        studyUpdateAutoFocusCalls[scheduled.callId] = StudyUpdateAutoFocusExpectation(
            owner = expected.owner,
            candidate = candidate,
            traversal = expected.traversal,
            expectedCurrentRevision = snapshot.revision,
            authorization = authorization,
        )
        eligibleFocusToolCallIds += scheduled.callId
        return scheduled
    }

    private fun scheduleServerRootAutoFocus(
        expected: RootStudyReadbackExpectation,
    ): VoiceTutorScheduledMcpCall? {
        val authorization = expected.startLessonAuthorization?.takeIf { it.isActive() } ?: return null
        val binding = latestFocusIntentBinding?.takeIf {
            it.inputIntent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
                it.providerItemId?.isNotBlank() == true &&
                it.providerItemId.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS &&
                it.lessonRevision == currentLessonRevision &&
                it.focusAuthorization === authorization
        } ?: return null
        val coordinator = toolCoordinator ?: return null
        val scheduled = try {
            coordinator.scheduleServerCall(
                name = SELECT_VOICE_STUDY_TOOL,
                arguments = linkedMapOf("study_id" to expected.studyId),
                nowNanos = nanoTime(),
            )
        } catch (_: VoiceTutorMcpProtocolException) {
            return null
        }
        if (!authorization.bindToServerCall(scheduled.callId)) {
            authorization.invalidate()
            terminate(VoiceTutorMcpProtocolException())
            return null
        }
        rootStudyAutoFocusCalls[scheduled.callId] = RootStudyAutoFocusExpectation(
            owner = expected.owner,
            studyId = expected.studyId,
            topic = expected.topic,
            difficulty = expected.difficulty,
            learnerProviderItemId = requireNotNull(binding.providerItemId),
            lessonRevision = binding.lessonRevision,
            authorization = authorization,
        )
        eligibleFocusToolCallIds += scheduled.callId
        return scheduled
    }

    private fun ownsCurrentRootStudyPipeline(owner: RootStudyPipelineOwner): Boolean {
        val binding = latestFocusIntentBinding ?: return false
        return currentRootStudyPipelineOwner == owner && currentLessonRevision == owner.lessonRevision &&
            binding.inputIntent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
            binding.providerItemId == owner.learnerProviderItemId &&
            binding.lessonRevision == owner.lessonRevision
    }

    private fun hasPendingCurrentRootStudyReadback(): Boolean {
        val owner = currentRootStudyPipelineOwner ?: return false
        return rootStudyReadbackCalls.values.any { it.owner == owner }
    }

    private fun trustedRootStudyReadback(
        result: VoiceTutorMcpToolResult,
        expected: RootStudyCreationExpectation,
    ): RootStudyReadbackExpectation? {
        if (result.isError) return null
        val id = result.rootStudyReadbackId?.takeIf { it > 0 } ?: return null
        val payload = runCatching { mapper.readTree(result.output) }.getOrNull()
            ?.takeIf { it.isObject } ?: return null
        val created = payload.path("created").takeIf { it.isBoolean }?.booleanValue() ?: return null
        val payloadId = payload.path("id").takeIf {
            it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0
        }?.longValue() ?: return null
        val topic = payload.path("topic").takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 255 } ?: return null
        val difficulty = payload.path("difficultyLevel").takeIf {
            it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 1..10
        }?.intValue() ?: return null
        if (payloadId != id || !payload.path("parentStudyId").isNull) return null
        if (created) {
            if (topic != expected.topic || difficulty != expected.difficulty) return null
        } else if (normalizedStudyTopicIdentity(topic) != normalizedStudyTopicIdentity(expected.topic)) {
            return null
        }
        if (result.createdStudyId?.let { it != id } == true || result.changedStudyId?.let { it != id } == true) {
            return null
        }
        if (result.studyTreeChanged &&
            (result.createdStudyId != id || result.changedStudyId != id ||
                result.changeKind != com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind.CREATED)
        ) return null
        if (expected.startLessonAuthorization != null && difficulty != expected.difficulty) {
            expected.startLessonAuthorization.invalidate()
        }
        return RootStudyReadbackExpectation(
            owner = expected.owner,
            studyId = id,
            topic = topic,
            difficulty = difficulty,
            startLessonRequested = expected.startLessonAuthorization != null,
            startLessonAuthorization = expected.startLessonAuthorization?.takeIf {
                difficulty == expected.difficulty
            },
        )
    }

    private fun confirmsExactRootStudyReadback(
        result: VoiceTutorMcpToolResult,
        expected: RootStudyReadbackExpectation,
    ): Boolean {
        if (result.isError) return false
        val discovery = result.candidateDiscovery ?: return false
        val scope = discovery.scope as? VoiceTutorCandidateDiscoveryScope.ExactStudy ?: return false
        val candidate = discovery.candidates.singleOrNull() ?: return false
        val payload = runCatching { mapper.readTree(result.output) }.getOrNull() ?: return false
        return payload.isObject && payload.path("id").takeIf {
            it.isIntegralNumber && it.canConvertToLong()
        }?.longValue() == expected.studyId && payload.path("parentStudyId").isNull &&
            payload.path("topic").takeIf { it.isTextual }?.textValue() == expected.topic &&
            payload.path("difficultyLevel").takeIf {
                it.isIntegralNumber && it.canConvertToInt()
            }?.intValue() == expected.difficulty &&
            discovery.source == VoiceTutorCandidateReadKind.GET_STUDY &&
            discovery.lessonRevision == expected.owner.lessonRevision &&
            discovery.lessonRevision == currentLessonRevision &&
            scope.requestedStudyId == expected.studyId && candidate.studyId == expected.studyId &&
            candidate.parentStudyId == null && candidate.topic == expected.topic
    }

    private fun normalizedStudyTopicIdentity(value: String): String = buildString(value.length) {
        var pendingSpace = false
        for (character in value.trim().lowercase()) {
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }

    private fun ensureToolAcknowledgementTimer() {
        if (!closed && toolAcknowledgementTimer == null) {
            toolAcknowledgementTimer = Flux.interval(INPUT_DEADLINE_POLL_INTERVAL)
                .subscribe { expireToolAcknowledgements() }
        }
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
                    candidate.topic.isBlank() || candidate.topic.length > 255 ||
                    candidate.difficulty?.let { it !in 1..10 } == true
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
        pendingStudyUpdateSelectionOffer = null
        reusableStudyUpdateTargetOfferId = null
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
    fun providerEventForRelay(
        raw: String,
        verifiedStudyAnswer: Boolean = false,
        verifiedLearnerQuestion: Boolean = false,
        speechSequence: Long? = null,
        checkpoint: Boolean = false,
        currentTranscriptAnswersStudyQuestion: Boolean = false,
    ): String {
        val node = mapper.readTree(raw)
        val eventType = node.path("type").asText()
        val revision = when (eventType) {
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
            .remove(VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID)
        node.remove(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION)
        node.remove(VoiceTutorTranscriptMetadata.IS_STUDY_QUESTION)
        node.remove(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID)
        node.remove(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS)
        val providerItemId = node.path("item_id").takeIf { it.isTextual }?.textValue()
        providerItemId?.let(pendingStudyAnswerGroups::remove)
        if (eventType == "conversation.item.input_audio_transcription.completed" &&
            verifiedStudyAnswer && !checkpoint && speechSequence != null
        ) {
            completedStudyAnswerGroup(
                speechSequence = speechSequence,
                providerItemId = providerItemId,
                binding = providerItemId?.let(inputLessonBindings::get),
                currentTranscript = node.path("transcript").takeIf { it.isTextual }?.textValue(),
                currentTranscriptAnswersStudyQuestion = currentTranscriptAnswersStudyQuestion,
            )?.let { group ->
                node.put(
                    VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID,
                    group.questionProviderItemId,
                )
                node.putArray(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS).also { ids ->
                    group.answerParts.forEach { ids.add(it.providerItemId) }
                }
                rememberBoundedBinding(pendingStudyAnswerGroups, requireNotNull(providerItemId), group)
            }
        }
        if (eventType == "conversation.item.input_audio_transcription.completed" && !checkpoint) {
            speechSequence?.let(persistedStudyAnswerSequences::remove)
        }
        if (eventType == "conversation.item.input_audio_transcription.completed" && verifiedLearnerQuestion) {
            node.put(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION, true)
        }
        return mapper.writeValueAsString(node)
    }

    private fun completedStudyAnswerGroup(
        speechSequence: Long,
        providerItemId: String?,
        binding: InputLessonBinding?,
        currentTranscript: String?,
        currentTranscriptAnswersStudyQuestion: Boolean,
    ): PendingStudyAnswerGroup? {
        if (speechSequence <= 0 || speechSequence != lastClientSpeechSequence ||
            providerItemId.isNullOrBlank() || providerItemId.length > MAX_PROVIDER_ITEM_ID_CHARACTERS ||
            binding == null || !binding.precedingTutorStudyQuestionEligible
        ) return null
        val questionProviderItemId = binding.precedingTutorProviderItemId
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            ?: return null
        val cached = persistedStudyAnswerSequences[speechSequence]
        if (cached?.invalid == true || cached?.lessonRevision?.let { it != binding.lessonRevision } == true ||
            cached?.questionProviderItemId?.let { it != questionProviderItemId } == true
        ) return null
        val parts = cached?.answerParts.orEmpty().toMutableList()
        if (currentTranscriptAnswersStudyQuestion) {
            val exactCurrent = currentTranscript?.trim()?.takeIf(String::isNotBlank) ?: return null
            if (parts.any { it.providerItemId == providerItemId } ||
                parts.size == VoiceTutorTranscriptMetadata.MAX_STUDY_ANSWER_PARTS
            ) return null
            parts += PersistedStudyAnswerPart(providerItemId, exactCurrent)
        }
        if (parts.isEmpty() || parts.size > VoiceTutorTranscriptMetadata.MAX_STUDY_ANSWER_PARTS ||
            parts.map(PersistedStudyAnswerPart::providerItemId).distinct().size != parts.size
        ) return null
        return PendingStudyAnswerGroup(
            lessonRevision = binding.lessonRevision,
            questionProviderItemId = questionProviderItemId,
            answerParts = parts.toList(),
        )
    }

    private fun rememberPersistedStudyAnswerCheckpoint(
        publication: VoiceTutorInputTurnCoordinator.Action.Publish,
        binding: InputLessonBinding?,
        persisted: Boolean,
    ) {
        if (!publication.checkpoint || !publication.currentTranscriptAnswersStudyQuestion ||
            publication.sequence <= 0 || publication.sequence != lastClientSpeechSequence
        ) return
        val questionProviderItemId = binding?.precedingTutorProviderItemId
            ?.takeIf {
                binding.precedingTutorStudyQuestionEligible && it.isNotBlank() &&
                    it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS
            }
        val transcript = runCatching {
            mapper.readTree(publication.rawEvent).path("transcript")
                .takeIf { it.isTextual }?.textValue()?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val valid = persisted && binding != null && questionProviderItemId != null && transcript != null
        val state = persistedStudyAnswerSequences[publication.sequence]
        if (!valid || state?.lessonRevision?.let { it != binding?.lessonRevision } == true ||
            state?.questionProviderItemId?.let { it != questionProviderItemId } == true
        ) {
            persistedStudyAnswerSequences[publication.sequence] = PersistedStudyAnswerSequence(
                lessonRevision = binding?.lessonRevision ?: -1,
                questionProviderItemId = questionProviderItemId.orEmpty(),
                invalid = true,
            )
            return
        }
        val accepted = state ?: PersistedStudyAnswerSequence(
            lessonRevision = binding.lessonRevision,
            questionProviderItemId = questionProviderItemId,
        )
        if (accepted.invalid || accepted.answerParts.any { it.providerItemId == publication.itemId } ||
            accepted.answerParts.size == VoiceTutorTranscriptMetadata.MAX_STUDY_ANSWER_PARTS
        ) {
            accepted.answerParts.clear()
            accepted.invalid = true
        } else {
            accepted.answerParts += PersistedStudyAnswerPart(publication.itemId, transcript)
        }
        persistedStudyAnswerSequences[publication.sequence] = accepted
        while (persistedStudyAnswerSequences.size > MAX_PERSISTED_STUDY_ANSWER_SEQUENCES) {
            persistedStudyAnswerSequences.remove(persistedStudyAnswerSequences.keys.first())
        }
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
        val studyAnswerGroup = pendingStudyAnswerGroups.remove(itemId)
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
            confirmPublished(itemId, nanoTime(), persisted).also { actions ->
                val ready = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Ready>().singleOrNull()
                if (ready != null) {
                    if (ready.checkpoint) {
                        rememberPersistedStudyAnswerCheckpoint(publication, binding, persisted)
                        return@also
                    }
                    // Consume a handled input exactly once even if storage
                    // is full or already contains it; this must not disconnect
                    // or re-assess/re-answer the same item. Mutation consent,
                    // however, requires a newly persisted exact USER item.
                    // Persistence can complete after a newer acoustic generation has
                    // started. The immutable publication sequence, not callback arrival
                    // order, decides whether this final semantic turn may retire the old
                    // owner and mint a new focus authority. Keep older accepted dialogue
                    // evidence for confirmation flows, but never resurrect a stale lease.
                    val focusPublicationCurrent = publication.sequence == lastClientSpeechSequence &&
                        activeClientSpeechSequence == null
                    val currentBinding = binding?.takeIf { it.lessonRevision == currentLessonRevision }
                    val focusTargetValid = focusPublicationCurrent && currentBinding != null &&
                        validFocusTargetBinding(
                            currentBinding, publication.intent, publication.targetStudyId, publication.targetOfferId,
                        )
                    val rootCreationIntentCurrent = focusPublicationCurrent && currentBinding != null &&
                        publication.intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
                        publication.rootStudyCreationRequest?.isValid() == true
                    val rootStartLessonCurrent = rootCreationIntentCurrent &&
                        publication.rootStudyCreationRequest?.startLessonAfterCreate == true
                    val childCreationIntentCurrent = focusPublicationCurrent && currentBinding != null &&
                        publication.intent == VoiceTutorInputIntent.CREATE_STUDY_TOPIC &&
                        publication.childStudyCreationRequest?.let { request ->
                            currentBinding.studyMutationContext?.takeIf {
                                it.lessonRevision == currentBinding.lessonRevision && it.isValid()
                            }?.candidates?.any { it.studyId == request.parentStudyId } == true
                        } == true
                    val studyUpdateIntentCurrent = focusPublicationCurrent && currentBinding != null &&
                        publication.intent == VoiceTutorInputIntent.UPDATE_STUDY &&
                        publication.studyUpdateRequest?.let { request ->
                            currentBinding.studyMutationContext?.takeIf {
                                it.lessonRevision == currentBinding.lessonRevision && it.isValid()
                            }?.candidates?.any { it.studyId == request.studyId } == true
                        } == true
                    val studyUpdateStartCurrent = studyUpdateIntentCurrent &&
                        publication.studyUpdateRequest?.startLessonAfterUpdate == true
                    val studyActionAtStaleRevision = persisted && focusPublicationCurrent && binding != null &&
                        currentBinding == null && publication.intent in setOf(
                            VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
                            VoiceTutorInputIntent.CONTINUE_TREE,
                            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                            VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
                            VoiceTutorInputIntent.UPDATE_STUDY,
                        )
                    val retiresPreviousMutationOwner = persisted && focusPublicationCurrent && binding != null
                    if (retiresPreviousMutationOwner) {
                        currentRootStudyPipelineOwner = null
                        currentStudyUpdatePipelineOwner = null
                        studyUpdateCalls.values.forEach { it.startLessonAuthorization?.invalidate() }
                        studyUpdateAutoFocusCalls.values.forEach { it.authorization.invalidate() }
                        eligibleFocusToolCallIds.removeAll(studyUpdateAutoFocusCalls.keys)
                        rootStudyCreationFollowupPending = false
                        rootStudyReadbackFailed = false
                        rootStudyAutoFocusFailed = false
                        rootStudyCommittedFocusSuperseded = false
                        studyMutationFailureFollowupPending = studyActionAtStaleRevision
                        studyUpdateAutoFocusFailedFollowupPending = false
                        studyUpdateCommittedFocusSupersededFollowupPending = false
                        if (studyActionAtStaleRevision) discardStudyQuestionPurpose()
                        latestFocusIntentBinding?.focusAuthorization?.invalidate()
                        latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
                        latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
                        latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
                    }
                    val focusAuthorization = when {
                        persisted && !ready.checkpoint && focusTargetValid -> VoiceTutorFocusAuthorization()
                        persisted && !ready.checkpoint && rootStartLessonCurrent -> VoiceTutorFocusAuthorization(
                            VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START,
                        )
                        persisted && !ready.checkpoint && studyUpdateStartCurrent -> VoiceTutorFocusAuthorization(
                            VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START,
                        )
                        else -> null
                    }
                    val rootStudyCreationAuthorization = publication.rootStudyCreationRequest
                        ?.takeIf {
                            persisted && !ready.checkpoint && rootCreationIntentCurrent
                        }
                        ?.let {
                            VoiceTutorRootStudyCreationAuthorization(
                                it.topic,
                                it.difficulty,
                                startLessonAfterCreate = rootStartLessonCurrent,
                            )
                        }
                    val childStudyCreationAuthorization = publication.childStudyCreationRequest
                        ?.takeIf { persisted && !ready.checkpoint && childCreationIntentCurrent }
                        ?.let {
                            VoiceTutorChildStudyCreationAuthorization(it.parentStudyId, it.topic, it.difficulty)
                        }
                    val studyUpdateAuthorization = publication.studyUpdateRequest
                        ?.takeIf { persisted && !ready.checkpoint && studyUpdateIntentCurrent }
                        ?.let { request ->
                            val mutationContext = requireNotNull(currentBinding?.studyMutationContext)
                            val target = requireNotNull(
                                mutationContext.candidates.singleOrNull { it.studyId == request.studyId },
                            )
                            VoiceTutorStudyUpdateAuthorization(
                                request.studyId,
                                request.topic,
                                request.difficulty,
                                scope = when {
                                    mutationContext.source ==
                                        com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT ->
                                        VoiceTutorStudyUpdateAuthorizationScope.INITIAL_OWNER_SNAPSHOT
                                    mutationContext.currentFocusStudyId == request.studyId ->
                                        VoiceTutorStudyUpdateAuthorizationScope.CONFIRMED_FOCUS_TREE
                                    else -> VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE
                                },
                                targetProof = VoiceTutorStudyUpdateTargetProof.from(target),
                                startLessonAfterUpdate = studyUpdateStartCurrent,
                            )
                        }
                    if (rootStudyCreationAuthorization != null || childStudyCreationAuthorization != null ||
                        studyUpdateAuthorization != null
                    ) {
                        clearPersistedLearnerContext()
                    }
                    val exactAnswerTranscript = studyAnswerGroup
                        ?.takeIf {
                            persisted && publication.intent ==
                                VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION &&
                                binding?.lessonRevision == it.lessonRevision &&
                                binding.precedingTutorProviderItemId == it.questionProviderItemId
                        }
                        ?.answerParts
                        ?.joinToString("\n", transform = PersistedStudyAnswerPart::transcript)
                    val exactQuestionTranscript = lastSpokenStudyQuestion?.takeIf { question ->
                        exactAnswerTranscript != null &&
                            binding?.precedingTutorStudyQuestionEligible == true &&
                            binding.precedingTutorProviderItemId == question.providerItemId &&
                            binding.lessonRevision == question.lessonRevision
                    }?.transcript
                    latestAcceptedInputBinding = if (persisted && !ready.checkpoint && binding != null) {
                        binding.copy(
                            providerItemId = itemId,
                            inputIntent = publication.intent,
                            targetStudyId = publication.targetStudyId.takeIf { focusTargetValid },
                            targetOfferId = publication.targetOfferId.takeIf { focusTargetValid },
                            focusAuthorization = focusAuthorization,
                            rootStudyCreationAuthorization = rootStudyCreationAuthorization,
                            childStudyCreationAuthorization = childStudyCreationAuthorization,
                            studyUpdateAuthorization = studyUpdateAuthorization,
                            studyQuestionTranscript = exactQuestionTranscript,
                            inputTranscript = exactAnswerTranscript,
                            studyAnswerProviderItemId = studyAnswerGroup
                                ?.answerParts?.lastOrNull()?.providerItemId
                                ?.takeIf { exactAnswerTranscript != null },
                        )
                    } else {
                        null
                    }
                    if (persisted && !ready.checkpoint && binding != null) {
                        val focus = confirmedStudyFocus
                        pendingStudyQuestionPurpose = if (
                            publication.intent == VoiceTutorInputIntent.CONTINUE_STUDY &&
                            binding.precedingTutorContinuationEligible &&
                            binding.precedingTutorProviderItemId != null &&
                            focus != null && focus.revision == currentLessonRevision &&
                            binding.lessonRevision == focus.revision
                        ) {
                            StudyQuestionPurpose(focus.revision, StudyQuestionPurposeSource.CONTINUE_STUDY)
                        } else {
                            null
                        }
                        if (publication.intent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION &&
                            studyAnswerGroup != null && exactAnswerTranscript != null &&
                            binding.precedingTutorStudyQuestionEligible &&
                            binding.precedingTutorProviderItemId == lastSpokenStudyQuestion?.providerItemId
                        ) {
                            lastSpokenStudyQuestion = null
                        }
                        // A final meaningful learner turn consumes the exact preceding
                        // feedback/learner-question-answer continuation anchor whether
                        // or not its semantics authorize another study question.
                        if (binding.precedingTutorContinuationEligible) {
                            lastSpokenContinuationAnchor = null
                        }
                    }
                    // Keep only the newest persisted tool-mutation intent. Saved-topic focus
                    // retains its one-shot candidate lease; direct root creation carries a
                    // separately one-shot authorization bound to assessor-extracted fields.
                    if (retiresPreviousMutationOwner) {
                        latestFocusIntentBinding = latestAcceptedInputBinding?.takeIf {
                            (focusTargetValid && (it.inputIntent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                                it.inputIntent == VoiceTutorInputIntent.CONTINUE_TREE)) || rootCreationIntentCurrent ||
                                childCreationIntentCurrent || studyUpdateIntentCurrent
                        }
                    }
                    rootStudyCreationAuthorization?.let { authorization ->
                        scheduleServerRootStudyCreation(
                            authorization,
                            focusAuthorization?.takeIf {
                                it.purpose == VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START
                            },
                        )
                    }
                    childStudyCreationAuthorization?.let(::scheduleServerChildStudyCreation)
                    studyUpdateAuthorization?.let { authorization ->
                        scheduleServerStudyUpdate(
                            authorization,
                            focusAuthorization?.takeIf {
                                it.purpose == VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START
                            },
                        )
                    }
                }
            }
        }
        if (acceptedLessonEnd && !closed) {
            // The exact meaningful USER turn is durably accepted and is still
            // the newest learner generation. Never create another response. If
            // the tutor is already speaking, however, keep the call alive until
            // that exact response reaches response.done + output buffer stopped.
            if (!responseActive && pendingPostRelayBoundary == null) {
                emitSpokenLessonEndLifecycle()
            }
        }
    }

    /**
     * A durable assessed CREATE_ROOT_STUDY choice is executable input, not a
     * suggestion for the model. Insert the exact server-owned call into provider
     * context now; no response can be created until both call and result ACKs.
     */
    private fun scheduleServerRootStudyCreation(
        authorization: VoiceTutorRootStudyCreationAuthorization,
        startLessonAuthorization: VoiceTutorFocusAuthorization?,
    ) {
        val binding = latestFocusIntentBinding?.takeIf {
            it.inputIntent == VoiceTutorInputIntent.CREATE_ROOT_STUDY &&
                it.rootStudyCreationAuthorization === authorization &&
                it.providerItemId?.isNotBlank() == true &&
                it.providerItemId.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS &&
                it.lessonRevision == currentLessonRevision
        }
        if (binding == null || rootStudyPipelineGeneration == Long.MAX_VALUE) {
            authorization.invalidate()
            startLessonAuthorization?.invalidate()
            studyMutationFailureFollowupPending = true
            discardStudyQuestionPurpose()
            return
        }
        val coordinator = toolCoordinator
        if (coordinator == null) {
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        val scheduled = try {
            coordinator.scheduleServerCall(
                name = CREATE_ROOT_STUDY_TOOL,
                arguments = linkedMapOf(
                    "topic" to authorization.topic,
                    "difficulty_level" to authorization.difficulty,
                ),
                nowNanos = nanoTime(),
            )
        } catch (error: VoiceTutorMcpProtocolException) {
            terminate(error)
            return
        }
        if (!authorization.bindToServerCall(scheduled.callId)) {
            authorization.invalidate()
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        rootStudyPipelineGeneration += 1
        val owner = RootStudyPipelineOwner(
            generation = rootStudyPipelineGeneration,
            learnerProviderItemId = requireNotNull(binding.providerItemId),
            lessonRevision = binding.lessonRevision,
        )
        currentRootStudyPipelineOwner = owner
        studyMutationFailureFollowupPending = false
        rootStudyCreationFollowupPending = false
        rootStudyReadbackFailed = false
        rootStudyAutoFocusFailed = false
        rootStudyCommittedFocusSuperseded = false
        rootStudyCreationCalls[scheduled.callId] = RootStudyCreationExpectation(
            owner = owner,
            topic = authorization.topic,
            difficulty = authorization.difficulty,
            creationAuthorization = authorization,
            startLessonAuthorization = startLessonAuthorization,
        )
        emit(scheduled.providerEvent)
        ensureToolAcknowledgementTimer()
    }

    private fun scheduleServerChildStudyCreation(authorization: VoiceTutorChildStudyCreationAuthorization) {
        scheduleServerStudyMutation(
            CREATE_STUDY_TOPIC_TOOL,
            linkedMapOf(
                "parent_study_id" to authorization.parentStudyId,
                "topic" to authorization.topic,
                "difficulty_level" to authorization.difficulty,
            ),
            authorization::bindToServerCall,
            authorization::invalidate,
        )
    }

    private fun scheduleServerStudyUpdate(
        authorization: VoiceTutorStudyUpdateAuthorization,
        startLessonAuthorization: VoiceTutorFocusAuthorization?,
    ) {
        val binding = latestFocusIntentBinding?.takeIf {
            it.inputIntent == VoiceTutorInputIntent.UPDATE_STUDY &&
                it.studyUpdateAuthorization === authorization &&
                it.providerItemId?.isNotBlank() == true &&
                it.providerItemId.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS &&
                it.lessonRevision == currentLessonRevision
        }
        val proof = authorization.targetProof
        if (binding == null || proof == null || studyUpdatePipelineGeneration == Long.MAX_VALUE) {
            authorization.invalidate()
            startLessonAuthorization?.invalidate()
            studyMutationFailureFollowupPending = true
            discardStudyQuestionPurpose()
            return
        }
        val arguments = linkedMapOf<String, Any>("study_id" to authorization.studyId)
        authorization.topic?.let { arguments["topic"] = it }
        authorization.difficulty?.let { arguments["difficulty_level"] = it }
        val coordinator = toolCoordinator
        if (coordinator == null) {
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        val scheduled = try {
            coordinator.scheduleServerCall(UPDATE_STUDY_TOOL, arguments, nanoTime())
        } catch (error: VoiceTutorMcpProtocolException) {
            terminate(error)
            return
        }
        if (!authorization.bindToServerCall(scheduled.callId)) {
            authorization.invalidate()
            startLessonAuthorization?.invalidate()
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        studyUpdatePipelineGeneration += 1
        val owner = StudyUpdatePipelineOwner(
            generation = studyUpdatePipelineGeneration,
            learnerProviderItemId = requireNotNull(binding.providerItemId),
            sourceLessonRevision = binding.lessonRevision,
        )
        currentStudyUpdatePipelineOwner = owner
        studyMutationFailureFollowupPending = false
        studyUpdateAutoFocusFailedFollowupPending = false
        studyUpdateCommittedFocusSupersededFollowupPending = false
        studyUpdateCalls[scheduled.callId] = StudyUpdateExpectation(
            owner = owner,
            targetProof = proof,
            topic = authorization.topic,
            difficulty = authorization.difficulty,
            updateAuthorization = authorization,
            startLessonAuthorization = startLessonAuthorization,
            traversal = binding.targetOffer?.candidateTraversals?.get(authorization.studyId)
                ?: VoiceTutorStudyTargetTraversal(),
        )
        emit(scheduled.providerEvent)
        ensureToolAcknowledgementTimer()
    }

    private fun scheduleServerStudyMutation(
        toolName: String,
        arguments: Map<String, Any>,
        bind: (String) -> Boolean,
        invalidate: () -> Unit,
    ) {
        val coordinator = toolCoordinator
        if (coordinator == null) {
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        val scheduled = try {
            coordinator.scheduleServerCall(toolName, arguments, nanoTime())
        } catch (error: VoiceTutorMcpProtocolException) {
            terminate(error)
            return
        }
        if (!bind(scheduled.callId)) {
            invalidate()
            terminate(VoiceTutorMcpProtocolException())
            return
        }
        studyMutationFailureFollowupPending = false
        emit(scheduled.providerEvent)
        ensureToolAcknowledgementTimer()
    }

    /** Freeze only server-owned identities available at this exact learner speech boundary. */
    private fun studyMutationContext(commit: PendingInputCommit): VoiceTutorStudyMutationContext? {
        if (commit.checkpoint) return null
        val initialOwnerSnapshot = initialStudyMutationSnapshot
        val focus = confirmedStudyFocus?.takeIf {
            it.revision == currentLessonRevision && it.revision == commit.lessonRevision &&
                it.studyId > 0 && it.topic.isNotBlank() && it.topic.length <= 255
        }
        val offer = commit.targetOffer?.takeIf {
            it.lessonRevision == commit.lessonRevision &&
                it.currentFocusStudyId == focus?.studyId &&
                it.candidates.isNotEmpty() &&
                it.candidates.size <= MAX_MUTATION_TARGET_CANDIDATES
        }
        if (focus == null && offer == null) {
            return initialOwnerSnapshot?.let { snapshot ->
                VoiceTutorStudyMutationContext(
                    lessonRevision = commit.lessonRevision,
                    currentFocusStudyId = null,
                    candidates = snapshot.candidates,
                    source = com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT,
                ).takeIf(VoiceTutorStudyMutationContext::isValid)
            }
        }
        val candidates = linkedMapOf<Long, VoiceTutorStudyTargetCandidate>()
        focus?.let {
            candidates[it.studyId] = VoiceTutorStudyTargetCandidate(
                it.studyId,
                it.parentStudyId,
                it.topic,
                it.difficulty,
            )
        }
        offer?.candidates?.forEach { candidate ->
            val previous = candidates[candidate.studyId]
            if (previous == null) {
                candidates[candidate.studyId] = candidate
            } else {
                if (previous.parentStudyId != candidate.parentStudyId || previous.topic != candidate.topic ||
                    previous.difficulty != null && candidate.difficulty != null &&
                    previous.difficulty != candidate.difficulty
                ) return null
                // Discovery DTOs intentionally omit difficulty. When the same
                // selected node was also spoken, retain its richer frozen focus
                // identity rather than treating that omission as a conflict.
                candidates[candidate.studyId] = if (previous.difficulty != null) previous else candidate
            }
        }
        if (candidates.size > MAX_MUTATION_TARGET_CANDIDATES) return null
        return VoiceTutorStudyMutationContext(
            lessonRevision = commit.lessonRevision,
            currentFocusStudyId = focus?.studyId,
            candidates = candidates.values.toList(),
        ).takeIf(VoiceTutorStudyMutationContext::isValid)
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
            closed || pendingPostRelayBoundary != null || spokenLessonEndLifecycleEmitted ||
            pending.speechSequence != lastClientSpeechSequence ||
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
                    if (!action.checkpoint) queuedCommittedTurn = true
                }
                else -> {
                    val outboundAction = if (action is VoiceTutorInputTurnCoordinator.Action.Publish) {
                        val publication = claimInitialStudyMutationSnapshot(coordinator, action)
                        pendingInputPublications[publication.itemId] = publication
                        if (!publication.checkpoint && publication.targetOfferId == reusableStudyUpdateTargetOfferId) {
                            // The first semantically meaningful reply owns this
                            // revised-name offer, regardless of whether storage
                            // subsequently succeeds. Never let a later item reuse it.
                            reusableStudyUpdateTargetOfferId = null
                            activeTargetOffer = null
                        }
                        // A long-speech checkpoint is incomplete by definition:
                        // later words can negate, quote or make it hypothetical.
                        if (
                            !publication.checkpoint &&
                            publication.intent == VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON &&
                            publication.sequence == lastClientSpeechSequence
                        ) {
                            pendingLessonEndPublications[publication.itemId] = publication.sequence
                        }
                        publication
                    } else {
                        action
                    }
                    val emitted = inputWork.tryEmitNext(outboundAction)
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
            // confirmPublished retires an older mutation owner before this
            // release; confirmDeleted for NON_COMMUNICATIVE input leaves that
            // exact owner and lease intact. The held call therefore resumes on
            // the correct side of the semantic decision boundary.
            releaseHeldToolActionsIfReady()
            createNormalResponseIfReady()
        }
    }

    /**
     * The first final semantic publication is the snapshot's linearization point. Provider ASR may
     * have acknowledged several buffers already, so revoke every frozen copy except this winner.
     * The mutation lease is still minted only after this exact USER item is durably persisted.
     */
    private fun claimInitialStudyMutationSnapshot(
        coordinator: VoiceTutorInputTurnCoordinator,
        publication: VoiceTutorInputTurnCoordinator.Action.Publish,
    ): VoiceTutorInputTurnCoordinator.Action.Publish {
        if (publication.checkpoint) return publication
        val source = inputLessonBindings[publication.itemId]?.studyMutationContext?.source
        if (initialStudyMutationSnapshot != null) {
            initialStudyMutationSnapshot = null
            initialStudyMutationWinnerItemId = publication.itemId
            coordinator.consumeInitialStudyMutationSnapshot(publication.itemId)
            inputLessonBindings.forEach { (itemId, binding) ->
                if (itemId != publication.itemId && binding.studyMutationContext?.source ==
                    com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT
                ) {
                    revokedInitialStudyMutationItemIds += itemId
                }
            }
            inputLessonBindings.replaceAll { itemId, binding ->
                if (itemId == publication.itemId || binding.studyMutationContext?.source !=
                    com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT
                ) {
                    binding
                } else {
                    binding.copy(studyMutationContext = null)
                }
            }
        }
        val staleInitialUpdate = (source ==
            com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT ||
            publication.itemId in revokedInitialStudyMutationItemIds) &&
            initialStudyMutationWinnerItemId != publication.itemId &&
            publication.intent == VoiceTutorInputIntent.UPDATE_STUDY
        revokedInitialStudyMutationItemIds.remove(publication.itemId)
        return if (staleInitialUpdate) {
            publication.copy(intent = VoiceTutorInputIntent.NONE, studyUpdateRequest = null)
        } else {
            publication
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

    /** Direct/unit callers acknowledge successful media boundaries immediately. */
    @Synchronized
    fun observeProviderEvent(raw: String): VoiceTutorProviderRelayDisposition {
        val observation = observeProviderEventWithPostRelay(raw)
        observation.postRelayBoundary?.let { acknowledgePostRelayBoundary(it.token) }
        return observation.disposition
    }

    /**
     * Production sideband ownership is atomic: no client terminal can clear the
     * completed transcript batch between provider observation and batch take.
     */
    @Synchronized
    fun observeProviderEventWithPostRelay(raw: String): VoiceTutorProviderObservation {
        val disposition = observeProviderEventInternal(raw)
        val pending = pendingPostRelayBoundary?.takeIf {
            !it.claimed && it.questionAssessmentToken == null && it.feedbackAssessmentToken == null
        }
        val boundary = pending?.let {
            pendingPostRelayBoundary = it.copy(claimed = true)
            VoiceTutorPostRelayBoundary(it.token, it.tutorTranscriptEvents)
        }
        return VoiceTutorProviderObservation(disposition, boundary)
    }

    @Synchronized
    fun acknowledgePostRelayBoundary(token: Long): Boolean {
        val pending = pendingPostRelayBoundary
            ?.takeIf { it.claimed && it.token == token } ?: return false
        resumeAfterPostRelayBoundary(pending)
        return true
    }

    /** Semantic failure/refusal/timeout is an ordinary false decision, never durable question authority. */
    @Synchronized
    fun completeSpokenQuestionAssessment(
        token: Long,
        result: Result<Boolean>,
    ): VoiceTutorPostRelayBoundary? {
        val pending = pendingPostRelayBoundary?.takeIf {
            !it.claimed && it.questionAssessmentToken == token &&
                it.proposedStudyQuestionEvidence != null
        } ?: return null
        val accepted = result.getOrDefault(false)
        val transcriptEvents = if (accepted) {
            pending.tutorTranscriptEvents.map(::stampStudyQuestionTranscriptEvent)
        } else {
            pending.tutorTranscriptEvents
        }
        pendingPostRelayBoundary = pending.copy(
            tutorTranscriptEvents = transcriptEvents,
            studyQuestionEvidence = pending.proposedStudyQuestionEvidence.takeIf { accepted },
            proposedStudyQuestionEvidence = null,
            questionAssessmentToken = null,
            claimed = true,
        )
        return VoiceTutorPostRelayBoundary(pending.token, transcriptEvents)
    }

    /** Semantic failure/refusal/timeout is an ordinary false decision, never durable feedback authority. */
    @Synchronized
    fun completeSpokenFeedbackAssessment(
        token: Long,
        result: Result<Boolean>,
    ): VoiceTutorPostRelayBoundary? {
        val pending = pendingPostRelayBoundary?.takeIf {
            !it.claimed && it.feedbackAssessmentToken == token &&
                it.proposedFeedbackEvidence != null
        } ?: return null
        val accepted = result.getOrDefault(false)
        val evidence = pending.proposedFeedbackEvidence.takeIf { accepted }
        val transcriptEvents = if (evidence != null) {
            pending.tutorTranscriptEvents.map { raw ->
                stampStudyAnswerFeedbackTranscriptEvent(raw, evidence.identity.answerProviderItemId)
            }
        } else {
            pending.tutorTranscriptEvents
        }
        pendingPostRelayBoundary = pending.copy(
            tutorTranscriptEvents = transcriptEvents,
            feedbackEvidence = evidence,
            continuationAnchor = pending.proposedFeedbackContinuationAnchor.takeIf { accepted },
            feedbackNavigationOffer = pending.proposedFeedbackNavigationOffer.takeIf { accepted },
            proposedFeedbackEvidence = null,
            proposedFeedbackContinuationAnchor = null,
            proposedFeedbackNavigationOffer = null,
            feedbackAssessmentToken = null,
            claimed = true,
        )
        return VoiceTutorPostRelayBoundary(pending.token, transcriptEvents)
    }

    private fun stampStudyQuestionTranscriptEvent(raw: String): String {
        val node = mapper.readTree(raw) as? com.fasterxml.jackson.databind.node.ObjectNode
            ?: throw VoiceTutorProviderProtocolException()
        node.put(VoiceTutorTranscriptMetadata.IS_STUDY_QUESTION, true)
        return mapper.writeValueAsString(node)
    }

    private fun stampStudyAnswerFeedbackTranscriptEvent(raw: String, answerProviderItemId: String): String {
        val node = mapper.readTree(raw) as? com.fasterxml.jackson.databind.node.ObjectNode
            ?: throw VoiceTutorProviderProtocolException()
        node.put(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID, answerProviderItemId)
        return mapper.writeValueAsString(node)
    }

    private fun observeProviderEventInternal(raw: String): VoiceTutorProviderRelayDisposition {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                throw VoiceTutorProviderProtocolException()
            } else {
                return VoiceTutorProviderRelayDisposition.DROP
            }
        if (!node.isObject || node.path("type").asText().isBlank()) {
            if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                throw VoiceTutorProviderProtocolException()
            }
            return VoiceTutorProviderRelayDisposition.DROP
        }
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
                accepted(
                    matches,
                    if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                        VoiceTutorProviderRelayDisposition.FORWARD_ONLY
                    } else {
                        VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
                    },
                )
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
                val serverCall = toolCoordinator?.acknowledgeServerCall(node, nanoTime())
                if (serverCall != null) {
                    dispatchOrHoldToolAction(serverCall, serverOwned = true)
                } else if (toolCoordinator?.acknowledge(node, nanoTime()) == true) {
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
                val studyMutationContext = commit?.let(::studyMutationContext)
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
                            precedingTutorStudyQuestionEligible = commit.precedingTutorStudyQuestionEligible,
                            precedingTutorContinuationEligible = commit.precedingTutorContinuationEligible,
                            precedingTutorFeedbackForStudyAnswer = commit.precedingTutorFeedbackForStudyAnswer,
                            precedingQuestionProviderItemId = commit.precedingQuestionProviderItemId,
                            precedingAnswerProviderItemId = commit.precedingAnswerProviderItemId,
                            precedingTutorFeedbackProviderItemId = commit.precedingTutorFeedbackProviderItemId,
                            precedingTutorNavigationOfferProviderItemId =
                                commit.precedingTutorNavigationOfferProviderItemId,
                            targetOffer = commit.targetOffer,
                            studyMutationContext = studyMutationContext,
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
                            studyMutationContext,
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
            "response.done" -> observeResponseDone(node)
            "error" -> if (observeEmptyInputCommit(node)) {
                VoiceTutorProviderRelayDisposition.DROP
            } else {
                observeProviderError(node)
            }
            else -> VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
        }
    }

    /** Compatibility hook name: elapsed speech time only checkpoints transcription, never creates a response. */
    @Synchronized
    internal fun fireContinuousSpeechDeadline() {
        cancelInputCheckpointTimer()
        if (
            closed || pendingPostRelayBoundary != null ||
            pauseCoordinator?.blocksResponses == true || !openingResponseRequested || openingResponsePending ||
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
            heldToolActions.clear()
            dispatchedToolActions.clear()
            toolDispatchBoundaries.clear()
            toolExecutionBoundaries.clear()
            finishedToolCallIds.clear()
            pendingInputPublications.clear()
            persistedStudyAnswerSequences.clear()
            pendingStudyAnswerGroups.clear()
            pendingLessonEndPublications.clear()
            pendingSpokenLessonEnd = null
            pendingProviderResponseRetry = null
            rootStudyCreationFollowupPending = false
            currentRootStudyPipelineOwner = null
            rootStudyCreationCalls.clear()
            rootStudyReadbackCalls.clear()
            rootStudyAutoFocusCalls.clear()
            rootStudyReadbackFailed = false
            rootStudyAutoFocusFailed = false
            rootStudyCommittedFocusSuperseded = false
            studyUpdateCalls.values.forEach { expectation ->
                expectation.updateAuthorization.invalidate()
                expectation.startLessonAuthorization?.invalidate()
            }
            studyUpdateAutoFocusCalls.values.forEach { it.authorization.invalidate() }
            currentStudyUpdatePipelineOwner = null
            studyUpdateCalls.clear()
            studyUpdateAutoFocusCalls.clear()
            studyMutationFailureFollowupPending = false
            studyUpdateAutoFocusFailedFollowupPending = false
            studyUpdateCommittedFocusSupersededFollowupPending = false
            suppressedLessonFocusEventCallIds.clear()
            pendingPostRelayBoundary = null
            recentFailedResponseCreateEventIds.clear()
            recentFailedResponseIds.clear()
            activeTutorTranscripts.clear()
            activeTutorFinalTranscriptEvents.clear()
            activeTutorTranscriptItemIds.clear()
            latestFocusIntentBinding?.focusAuthorization?.invalidate()
            latestFocusIntentBinding?.rootStudyCreationAuthorization?.invalidate()
            latestFocusIntentBinding?.childStudyCreationAuthorization?.invalidate()
            latestFocusIntentBinding?.studyUpdateAuthorization?.invalidate()
            latestFocusIntentBinding = null
            clearTargetDiscovery()
            pendingInputCommits.clear()
            recentCommittedItemIds.clear()
            revokedInitialStudyMutationItemIds.clear()
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
            spokenQuestionAssessmentWork.tryEmitComplete()
            spokenFeedbackAssessmentWork.tryEmitComplete()
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
        pendingStudyQuestionPurpose = null
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
            precedingTutorStudyQuestionEligible = binding.precedingTutorStudyQuestionEligible,
            precedingTutorContinuationEligible = binding.precedingTutorContinuationEligible,
        )
    }

    private fun observeClientSpeechStarted(sequence: Long) {
        if (sequence <= lastClientSpeechSequence) return
        persistedStudyAnswerSequences.keys.removeAll { it < sequence }
        pendingStudyQuestionPurpose = null
        // Raw acoustic activity may be noise. Preserve the exact binding and
        // one-shot leases until semantic persistence decides whether this is a
        // replacement turn; provider-ACKed synthetic actions are held meanwhile.
        // Advance the acoustic high-water before any suppression/overlap return.
        // Otherwise a newer overlapping start can revoke an old lease, then let
        // the older stop/persistence callback look current and mint it again.
        lastClientSpeechSequence = sequence
        pendingLessonEndPublications.entries.removeAll { it.value < sequence }
        retractSpokenLessonEndBeforeLifecycle(sequence)
        if (pauseCoordinator?.acceptsSpeechEdges != true) {
            // Keep a high-water mark even for suppressed edges. Replaying an
            // old held start after resume cannot revive a discarded utterance.
            clearTargetDiscovery()
            return
        }
        if (activeClientSpeechSequence != null) {
            clearTargetDiscovery()
            return
        }
        if (pendingSpeechCommitCount >= MAX_PENDING_SPEECH_COMMITS) {
            throw VoiceTutorPendingInputCommitOverflowException()
        }
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
        val pendingTutorBoundary = pendingPostRelayBoundary
        val awaitsTutorFinalization = pendingTutorBoundary != null ||
            (responseActive && providerOutputBufferStopped && !providerResponseDone)
        speechAwaitingTutorFinalizationGeneration = when {
            pendingTutorBoundary != null -> pendingTutorBoundary.completedResponseGeneration
            awaitsTutorFinalization -> activeResponseGeneration
            else -> null
        }
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
        val precedingTutorStudyQuestionEligible = activeSpeechPrecedingTutorStudyQuestionEligible
        val precedingTutorContinuationEligible = activeSpeechPrecedingTutorContinuationEligible
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
                precedingTutorStudyQuestionEligible = precedingTutorStudyQuestionEligible,
                precedingTutorContinuationEligible = precedingTutorContinuationEligible,
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
                precedingTutorStudyQuestionEligible = precedingTutorStudyQuestionEligible,
                precedingTutorContinuationEligible = precedingTutorContinuationEligible,
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
                precedingTutorStudyQuestionEligible = precedingTutorStudyQuestionEligible,
                precedingTutorContinuationEligible = precedingTutorContinuationEligible,
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
            precedingTutorStudyQuestionEligible = pending.precedingTutorStudyQuestionEligible,
            precedingTutorContinuationEligible = pending.precedingTutorContinuationEligible,
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
        precedingTutorStudyQuestionEligible: Boolean = activeSpeechPrecedingTutorStudyQuestionEligible,
        precedingTutorContinuationEligible: Boolean = activeSpeechPrecedingTutorContinuationEligible,
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
            precedingTutorStudyQuestionEligible = precedingTutorStudyQuestionEligible,
            precedingTutorContinuationEligible = precedingTutorContinuationEligible,
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
        // Acoustic activity is not itself a meaningful replacement turn. The
        // server-owned action gate prevents a preserved lease from executing
        // until this exact speech is classified and its cleanup/persistence is ACKed.
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
        val lessonRevision = activeSpeechLessonRevision ?: currentLessonRevision
        activeSpeechPrecedingTutorStudyQuestionEligible = lastSpokenStudyQuestion?.matches(
            lessonRevision,
            activeSpeechPrecedingSpokenGeneration,
            activeSpeechPrecedingTutorStopOrder,
            activeSpeechPrecedingTutorProviderItemId,
        ) == true
        activeSpeechPrecedingTutorContinuationEligible = lastSpokenContinuationAnchor?.matches(
            lessonRevision,
            activeSpeechPrecedingSpokenGeneration,
            activeSpeechPrecedingTutorStopOrder,
            activeSpeechPrecedingTutorProviderItemId,
        ) == true
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
        if (activeSpeechTargetOffer?.offerId != reusableStudyUpdateTargetOfferId) {
            activeTargetOffer = null
        }
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
        activeSpeechPrecedingTutorStudyQuestionEligible = false
        activeSpeechPrecedingTutorContinuationEligible = false
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
            openingResponsePending || pendingPostRelayBoundary != null ||
            !userSpeaking || transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND
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
        if (createProviderResponseRetryIfReady()) return
        if (
            closed || pendingPostRelayBoundary != null ||
            pendingSpokenLessonEnd != null || spokenLessonEndRequested ||
            pauseCoordinator?.blocksResponses == true ||
            !openingResponseRequested || userSpeaking ||
            pendingSpeechCommitCount > 0 || pendingInputCommits.isNotEmpty() || delayedStopCommit != null || responseActive ||
            inputCoordinator?.hasPending == true || toolCoordinator?.hasPending == true ||
            (rootStudyCreationFollowupPending && hasPendingCurrentRootStudyReadback()) ||
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
        val studyQuestionPurposeReady = !opening && pendingStudyQuestionPurpose?.let { purpose ->
            confirmedStudyFocus?.revision == currentLessonRevision &&
                purpose.lessonRevision == currentLessonRevision
        } == true
        val rootStudyFollowupReady = !opening && rootStudyCreationFollowupPending &&
            !hasPendingCurrentRootStudyReadback()
        val studyUpdateAutoFocusFailedFollowupReady = !opening &&
            studyUpdateAutoFocusFailedFollowupPending
        val studyUpdateCommittedFocusSupersededFollowupReady = !opening &&
            studyUpdateCommittedFocusSupersededFollowupPending
        val studyUpdateSelectionFollowupReady = !opening &&
            pendingStudyUpdateSelectionOffer?.lessonRevision == currentLessonRevision
        val studyMutationFailureFollowupReady = !opening && studyMutationFailureFollowupPending
        val toolChoice = if (opening || rootStudyFollowupReady ||
            studyUpdateCommittedFocusSupersededFollowupReady ||
            studyUpdateAutoFocusFailedFollowupReady || studyUpdateSelectionFollowupReady ||
            studyMutationFailureFollowupReady
        ) {
            "none"
        } else if (studyQuestionPurposeReady) {
            // A purpose-bound response may need the saved node's learning
            // history before it can ask a well-grounded question. The purpose
            // survives only strict tool-only rounds made entirely of the
            // server allow-listed history/stat reads below.
            "auto"
        } else {
            toolCoordinator?.toolChoice ?: "none"
        }
        val instructions = when {
            opening -> OPENING_RESPONSE_INSTRUCTIONS
            rootStudyFollowupReady && rootStudyCommittedFocusSuperseded ->
                ROOT_STUDY_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS
            rootStudyFollowupReady && !rootStudyReadbackFailed && !rootStudyAutoFocusFailed ->
                ROOT_STUDY_CREATION_FOLLOWUP_INSTRUCTIONS
            rootStudyFollowupReady && rootStudyAutoFocusFailed ->
                ROOT_STUDY_CREATION_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
            rootStudyFollowupReady -> ROOT_STUDY_CREATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
            studyUpdateCommittedFocusSupersededFollowupReady ->
                STUDY_UPDATE_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS
            studyUpdateAutoFocusFailedFollowupReady ->
                STUDY_UPDATE_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
            studyUpdateSelectionFollowupReady -> STUDY_UPDATE_SELECTION_FOLLOWUP_INSTRUCTIONS
            studyMutationFailureFollowupReady -> STUDY_MUTATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
            studyQuestionPurposeReady -> STUDY_QUESTION_RESPONSE_INSTRUCTIONS
            else -> null
        }
        beginResponse(responseEventId, toolChoice, instructions)
        emitResponseCreate(responseEventId, toolChoice, instructions)
    }

    /** A failed provider response owns one retry and always remains ahead of a new tutor turn. */
    private fun createProviderResponseRetryIfReady(): Boolean {
        val retry = pendingProviderResponseRetry ?: return false
        if (closed || spokenLessonEndRequested) {
            pendingProviderResponseRetry = null
            return true
        }
        if (
            pauseCoordinator?.blocksResponses == true || userSpeaking || responseActive ||
            pendingSpeechCommitCount > 0 || pendingInputCommits.isNotEmpty() || delayedStopCommit != null ||
            toolCoordinator?.hasPending == true
        ) return true
        pendingProviderResponseRetry = null
        if (retry.lessonRevision != currentLessonRevision) {
            abandonProviderResponseTurn(abandonedResponseId = retry.abandonedResponseId)
            return true
        }
        val responseEventId = internalEventId("turn-retry")
        activateResponse(responseEventId, retry)
        pendingSpokenLessonEnd = pendingSpokenLessonEnd?.let { pending ->
            if (pending.waitResponseGeneration == retry.replacesGeneration) {
                pending.copy(waitResponseGeneration = activeResponseGeneration)
            } else {
                pending
            }
        }
        emitResponseCreate(responseEventId, retry.toolChoice, retry.instructionOverride)
        return true
    }

    @Synchronized
    internal fun advancePause() {
        val pause = pauseCoordinator ?: return
        if (closed) return
        try {
            applyPauseActions(
                pause.advance(
                    boundaryReady = !responseActive && pendingPostRelayBoundary == null &&
                        !userSpeaking && activeClientSpeechSequence == null &&
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

    private fun beginResponse(
        createEventId: String,
        toolChoice: String,
        instructionOverride: String?,
    ) {
        val studyAnswer = latestAcceptedInputBinding?.takeIf {
            it.inputIntent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION
        }?.let { binding ->
            val question = binding.precedingTutorProviderItemId
                ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            val answer = binding.studyAnswerProviderItemId
                ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            if (question != null && answer != null) {
                val questionTranscript = binding.studyQuestionTranscript
                val answerTranscript = binding.inputTranscript
                if (questionTranscript.isNullOrBlank() || answerTranscript.isNullOrBlank()) null else {
                    StudyAnswerIdentity(
                        binding.lessonRevision,
                        question,
                        answer,
                        questionTranscript,
                        answerTranscript,
                    )
                }
            } else {
                null
            }
        }
        val responseStudyAnswer = studyAnswer?.takeUnless { it == lastSpokenStudyAnswer }
        val respondsToLearnerQuestion = latestAcceptedInputBinding?.inputIntent ==
            VoiceTutorInputIntent.ASK_STUDY_QUESTION
        val studyQuestionPurpose = pendingStudyQuestionPurpose?.takeIf { purpose ->
            val focus = confirmedStudyFocus
            focus != null && focus.revision == currentLessonRevision &&
                purpose.lessonRevision == focus.revision &&
                instructionOverride == STUDY_QUESTION_RESPONSE_INSTRUCTIONS && toolChoice == "auto"
        }
        pendingStudyQuestionPurpose = null
        if (responseStudyAnswer != null) {
            // A new answer needs its own exact persisted feedback item. Tool-only
            // rounds leave it pending; an actual spoken response consumes it once.
            completedStudyAnswerFeedback = null
            pendingNavigationFeedback = null
        }
        val boundary = TutorBoundaryCheckpoint(lastTutorSpeechStoppedOrder, lastSpokenResponseGeneration)
        val revisedUpdateOffer = pendingStudyUpdateSelectionOffer?.takeIf {
            instructionOverride == STUDY_UPDATE_SELECTION_FOLLOWUP_INSTRUCTIONS &&
                it.lessonRevision == currentLessonRevision && validCandidateOfferPool(it)
        }
        if (revisedUpdateOffer != null) pendingStudyUpdateSelectionOffer = null
        activateResponse(
            createEventId,
            PendingProviderResponseRetry(
                toolChoice = toolChoice,
                lessonRevision = currentLessonRevision,
                candidateDiscovery = revisedUpdateOffer ?: candidateDiscoveryGraph?.offerPool()?.takeIf {
                    it.lessonRevision == currentLessonRevision
                },
                respondsToStudyAnswer = responseStudyAnswer != null,
                studyAnswer = responseStudyAnswer,
                respondsToLearnerQuestion = respondsToLearnerQuestion,
                studyQuestionPurpose = studyQuestionPurpose,
                retryAttempt = 0,
                replacesGeneration = activeResponseGeneration,
                boundaryCheckpoint = boundary,
                abandonedResponseId = null,
                instructionOverride = instructionOverride,
            ),
        )
    }

    private fun activateResponse(createEventId: String, state: PendingProviderResponseRetry) {
        inputCoordinator?.teacherResponseStarted()
        activeTutorTranscripts.clear()
        activeTutorFinalTranscriptEvents.clear()
        activeTutorTranscriptItemIds.clear()
        activeTutorTranscriptOverflow = false
        activeTargetOffer = null
        reusableStudyUpdateTargetOfferId = null
        activeTargetOfferExchangeEvidence = null
        activeResponseCandidateDiscovery = state.candidateDiscovery
        activeResponseHadCandidateNavigation = state.candidateDiscovery != null
        playbackTimer?.dispose()
        playbackTimer = null
        activeResponseGeneration = if (activeResponseGeneration == Long.MAX_VALUE) 1 else activeResponseGeneration + 1
        activeResponseRetryAttempt = state.retryAttempt
        activeResponseBoundaryCheckpoint = state.boundaryCheckpoint
        activeResponseStudyAnswer = state.studyAnswer
        activeResponseRespondsToStudyAnswer = state.respondsToStudyAnswer
        activeResponseRespondsToLearnerQuestion = state.respondsToLearnerQuestion
        activeResponseStudyQuestionPurpose = state.studyQuestionPurpose
        activeResponseTutorContext = ""
        activeResponseTutorContextForAssessment = ""
        responseActive = true
        activeResponseLessonRevision = state.lessonRevision
        activeResponseCreateEventId = createEventId
        activeResponseId = null
        activeResponseAllowsTools = state.toolChoice == "auto"
        activeResponseInstructionOverride = state.instructionOverride
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        providerOutputBufferStarted = false
        providerAudioObserved = false
        toolOnlyResponse = false
        activeResponseAcceptedToolCalls = false
        activeResponseToolNames = emptySet()
        responseTimer?.dispose()
        val responseGeneration = activeResponseGeneration
        responseTimer = Mono.delay(responseTimeout)
            .subscribe { fireResponseTimeout(responseGeneration, createEventId) }
    }

    private fun emitResponseCreate(
        responseEventId: String,
        toolChoice: String,
        instructionOverride: String?,
    ) {
        val response = linkedMapOf<String, Any?>(
            "tool_choice" to toolChoice,
            "metadata" to linkedMapOf(
                VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to responseEventId,
            ),
        )
        instructionOverride?.let { response["instructions"] = it }
        emit(
            linkedMapOf(
                "event_id" to responseEventId,
                "type" to "response.create",
                "response" to response,
            ),
        )
    }

    private fun observeResponseCreated(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive) return false
        if (!matchesActiveResponseToken(node.path("response"))) return false
        val responseId = node.path("response").path("id").asText()
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && !validProviderResponseId(responseId)) {
            throw VoiceTutorProviderProtocolException()
        }
        if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            responseId in recentFailedResponseIds
        ) {
            // A provider response id is the only correlation key on late clear
            // and playout events. Reusing one across attempts makes those
            // boundaries ambiguous, so fail closed instead of guessing.
            throw VoiceTutorProviderProtocolException()
        }
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

    private fun observeResponseDone(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition {
        if (!responseActive || providerResponseDone) return VoiceTutorProviderRelayDisposition.DROP
        val response = node.path("response")
        // A stale provider result cannot fail or complete a different response.
        if (!matchesActiveResponseToken(response)) return VoiceTutorProviderRelayDisposition.DROP
        val responseId = response.path("id").asText()
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && !validProviderResponseId(responseId)) {
            throw VoiceTutorProviderProtocolException()
        }
        if (!matchesActiveResponse(responseId)) return VoiceTutorProviderRelayDisposition.DROP
        val status = response.path("status").asText()
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && status != "completed") {
            val kind = when (status) {
                "cancelled" -> VoiceTutorProviderTurnFailureKind.RESPONSE_CANCELLED
                "incomplete" -> VoiceTutorProviderTurnFailureKind.RESPONSE_INCOMPLETE
                "failed" -> VoiceTutorProviderTurnFailureKind.RESPONSE_FAILED
                else -> throw VoiceTutorProviderProtocolException()
            }
            return recoverProviderResponseTurn(kind, responseId = responseId)
        }
        if (status !in COMPLETING_RESPONSE_STATUSES) return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
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
        activeResponseAcceptedToolCalls = toolCalls.isNotEmpty()
        activeResponseToolNames = toolCalls.mapTo(linkedSetOf()) { it.name }
        toolOnlyResponse = toolCalls.isNotEmpty() &&
            response.path("output").all { it.path("type").asText() == "function_call" } &&
            !providerOutputBufferStarted && !providerAudioObserved && activeTutorTranscripts.isEmpty()
        val focusCalls = toolCalls.filter { it.name in STUDY_FOCUS_TOOLS }
        if (toolOnlyResponse && focusCalls.size == 1 && toolCalls.all {
                it.name in STUDY_FOCUS_TOOLS || it.name in SAFE_PURPOSE_PRESERVING_READ_TOOLS
            }
        ) {
            eligibleFocusToolCallIds += focusCalls.single().callId
        } else {
            eligibleFocusToolCallIds.removeAll(toolCalls.mapTo(linkedSetOf()) { it.callId })
        }
        if (toolOnlyResponse) {
            activeResponseCandidateDiscovery = null
        }
        providerResponseDone = true
        responseTimer?.dispose()
        responseTimer = null
        for (call in toolCalls) {
            dispatchOrHoldToolAction(call, serverOwned = false)
            if (closed) {
                return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
        }
        activeResponseTutorContext = completedTutorContext(response)
        // Stage semantic context at response.done, before a following output
        // stop can promote/consume navigation evidence. It remains private
        // until the successful post-relay persistence ACK.
        activeResponseTutorContextForAssessment = tutorContextForAssessment(activeResponseTutorContext)
        if (transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            withInputCoordinator {
                teacherResponseCompleted(activeResponseTutorContextForAssessment, nanoTime())
            }
            promoteTargetOfferIfReady()
        }
        advancePlaybackGate()
        return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
    }

    private fun rememberTutorTranscript(node: com.fasterxml.jackson.databind.JsonNode) {
        val text = node.path("transcript").takeIf { it.isTextual }?.textValue() ?: return
        // A blank final is valid provider output, but it is not a transcript
        // row. The spoken done+stopped boundary still owns an empty batch.
        if (text.isBlank()) return
        val itemId = node.path("item_id").asText()
            .takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS } ?: return
        val contentIndex = node.path("content_index").takeIf {
            it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 0
        }?.intValue() ?: return
        val key = "$itemId:$contentIndex"
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            key !in activeTutorFinalTranscriptEvents &&
            activeTutorFinalTranscriptEvents.size >= MAX_TUTOR_CONTEXT_PARTS
        ) {
            activeTutorTranscriptOverflow = true
        }
        if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            (key in activeTutorFinalTranscriptEvents ||
                activeTutorFinalTranscriptEvents.size < MAX_TUTOR_CONTEXT_PARTS)
        ) {
            val responseId = activeResponseId ?: return
            activeTutorFinalTranscriptEvents[key] = StagedTutorTranscript(
                responseId = responseId,
                itemId = itemId,
                contentIndex = contentIndex,
                transcript = text.take(MAX_DEFERRED_TUTOR_TRANSCRIPT_CHARACTERS),
            )
        }
        if (inputCoordinator == null ||
            (key !in activeTutorTranscripts && activeTutorTranscripts.size >= MAX_TUTOR_CONTEXT_PARTS)
        ) return
        activeTutorTranscripts[key] = text.take(MAX_TUTOR_CONTEXT_CHARACTERS)
        activeTutorTranscriptItemIds += itemId
    }

    private fun completedTutorContext(response: com.fasterxml.jackson.databind.JsonNode): String {
        completedActiveTutorTranscriptContext()?.let { return it }
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

    private fun completedActiveTutorTranscriptContext(): String? {
        val transcript = if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            activeTutorFinalTranscriptEvents.isNotEmpty()
        ) {
            orderedStagedTutorTranscriptParts().joinToString("\n") { it.transcript }
        } else {
            activeTutorTranscripts.values.joinToString("\n")
        }
        return transcript.take(MAX_TUTOR_CONTEXT_CHARACTERS).takeIf { it.isNotBlank() }
    }

    private fun orderedStagedTutorTranscriptParts(): List<StagedTutorTranscript> {
        val byItem = linkedMapOf<String, MutableList<StagedTutorTranscript>>()
        activeTutorFinalTranscriptEvents.values.forEach { part ->
            byItem.getOrPut(part.itemId) { mutableListOf() } += part
        }
        return byItem.values.flatMap { parts -> parts.sortedBy { it.contentIndex } }
    }

    /**
     * Persistence is idempotent per provider item, not per audio content part.
     * Coalesce a provider item's bounded parts in index order and snapshot the
     * trusted lesson attribution before a later terminal can clear bindings.
     */
    private fun completedTutorTranscriptEventsForPersistence(isStudyQuestion: Boolean): List<String> {
        val byItem = linkedMapOf<String, MutableList<StagedTutorTranscript>>()
        orderedStagedTutorTranscriptParts().forEach { part ->
            byItem.getOrPut(part.itemId) { mutableListOf() } += part
        }
        return byItem.values.mapNotNull { parts ->
            val ordered = parts.sortedBy { it.contentIndex }
            val first = ordered.firstOrNull() ?: return@mapNotNull null
            val transcript = ordered.joinToString("\n") { it.transcript }
                .take(MAX_DEFERRED_TUTOR_TRANSCRIPT_CHARACTERS)
            if (transcript.isBlank()) return@mapNotNull null
            val event = mapper.createObjectNode()
                    .put("type", "response.output_audio_transcript.done")
                    .put("response_id", first.responseId)
                    .put("item_id", first.itemId)
                    .put("content_index", first.contentIndex)
                    .put("transcript", transcript)
                    .put(VoiceTutorTranscriptMetadata.LESSON_REVISION, activeResponseLessonRevision)
            if (isStudyQuestion) event.put(VoiceTutorTranscriptMetadata.IS_STUDY_QUESTION, true)
            mapper.writeValueAsString(event)
        }
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
        activeResponseHadCandidateNavigation = false
        if (discovery == null) return
        val tutorAudioTranscript = completedActiveTutorTranscriptContext() ?: return
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
        if (discovery.purpose == CandidateOfferPurpose.UPDATED_STUDY_SELECTION) {
            reusableStudyUpdateTargetOfferId = activeTargetOffer?.offerId
        }
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
        val responseId = node.path("response_id").asText()
        if (!validProviderResponseId(responseId)) throw VoiceTutorProviderProtocolException()
        // A late clear for the discarded attempt cannot consume the retry or
        // invalidate a newer, complete sentence.
        if (!matchesKnownActiveResponse(responseId)) {
            if (responseId in recentFailedResponseIds) return VoiceTutorProviderRelayDisposition.DROP
            throw VoiceTutorProviderProtocolException()
        }
        return recoverProviderResponseTurn(
            VoiceTutorProviderTurnFailureKind.OUTPUT_BUFFER_CLEARED,
            responseId = responseId,
        )
    }

    private fun observeProviderError(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition {
        safeRealtimeItemCreateRejection(node)?.let { rejection ->
            val rejectedCall = toolCoordinator?.tombstoneRejectedServerCall(rejection.eventId)
            if (rejectedCall != null) {
                return observeRejectedServerCall(node, rejectedCall)
            }
        }
        val disposition = classifyRealtimeProviderError(node)
        val causedEventId = safeProviderCausedEventId(node)
        val correlation = providerErrorCorrelation(causedEventId)
        if (disposition == VoiceTutorProviderErrorDisposition.PROTOCOL_INVALID) {
            throw VoiceTutorProviderProtocolException()
        }
        if (disposition == VoiceTutorProviderErrorDisposition.SESSION_FATAL) {
            onProviderTurnFailure(
                providerFailureDiagnostic(
                    VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                    node,
                    correlation,
                    (activeResponseRetryAttempt + 1).coerceAtLeast(1),
                    VoiceTutorProviderTurnFailureAction.SESSION_FATAL,
                ),
            )
            return VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
        }
        if (correlation == VoiceTutorProviderEventCorrelation.ACTIVE_RESPONSE) {
            return recoverProviderResponseTurn(
                VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                providerErrorNode = node,
                eventCorrelation = correlation,
            )
        }
        if (correlation == VoiceTutorProviderEventCorrelation.STALE_RESPONSE ||
            (correlation == VoiceTutorProviderEventCorrelation.INTERNAL_CONTROL &&
                isSafeRealtimeInternalControlError(node))
        ) {
            onProviderTurnFailure(
                providerFailureDiagnostic(
                    VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                    node,
                    correlation,
                    0,
                    VoiceTutorProviderTurnFailureAction.IGNORED,
                ),
            )
            return VoiceTutorProviderRelayDisposition.DROP
        }
        if (correlation == VoiceTutorProviderEventCorrelation.MISSING &&
            isSafeUncorrelatedRealtimeProviderError(node)
        ) {
            if (responseActive) {
                return abandonUncorrelatedProviderResponse(node, correlation)
            }
            onProviderTurnFailure(
                providerFailureDiagnostic(
                    VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                    node,
                    correlation,
                    0,
                    VoiceTutorProviderTurnFailureAction.IGNORED,
                ),
            )
            return VoiceTutorProviderRelayDisposition.DROP
        }
        // An error for an unknown/input/session/tool event cannot be safely
        // attached to a response generation. Treat the state ambiguity as a
        // protocol failure instead of replaying a possibly mutating turn.
        throw VoiceTutorProviderProtocolException()
    }

    /**
     * A validated item-create rejection proves only that this exact synthetic envelope never
     * entered provider context. Revoke its one-shot lease and continue with a no-tools status
     * response; never replay a potentially mutating command or end the otherwise healthy call.
     */
    private fun observeRejectedServerCall(
        node: com.fasterxml.jackson.databind.JsonNode,
        rejectedCall: VoiceTutorRejectedServerCall,
    ): VoiceTutorProviderRelayDisposition {
        if (!rejectedCall.newlyTombstoned) return VoiceTutorProviderRelayDisposition.DROP

        val callId = rejectedCall.callId
        heldToolActions.remove(callId)
        dispatchedToolActions.remove(callId)
        toolDispatchBoundaries.remove(callId)
        toolExecutionBoundaries.remove(callId)
        finishedToolCallIds += callId
        val rootCreation = rootStudyCreationCalls.remove(callId)
        val rootReadback = rootStudyReadbackCalls.remove(callId)
        val rootAutoFocus = rootStudyAutoFocusCalls.remove(callId)
        val studyUpdate = studyUpdateCalls.remove(callId)
        val studyUpdateAutoFocus = studyUpdateAutoFocusCalls.remove(callId)
        val currentRootOwner = rootCreation?.owner ?: rootReadback?.owner ?: rootAutoFocus?.owner
        val ownsCurrentRootPipeline = currentRootOwner?.let(::ownsCurrentRootStudyPipeline) == true
        val rejectedStudyUpdateOwner = studyUpdate?.owner ?: studyUpdateAutoFocus?.owner
        val ownsCurrentStudyUpdatePipeline = rejectedStudyUpdateOwner != null &&
            rejectedStudyUpdateOwner == currentStudyUpdatePipelineOwner

        rootCreation?.creationAuthorization?.invalidate()
        rootCreation?.startLessonAuthorization?.invalidate()
        rootReadback?.startLessonAuthorization?.invalidate()
        rootAutoFocus?.authorization?.invalidate()
        studyUpdate?.updateAuthorization?.invalidate()
        studyUpdate?.startLessonAuthorization?.invalidate()
        studyUpdateAutoFocus?.authorization?.invalidate()
        eligibleFocusToolCallIds.remove(callId)
        suppressedLessonFocusEventCallIds.remove(callId)
        toolDiscoveryFences.remove(callId)

        if (ownsCurrentRootPipeline) {
            currentRootStudyPipelineOwner = null
            rootStudyCreationFollowupPending = true
            rootStudyCommittedFocusSuperseded = false
            when (rejectedCall.toolName) {
                CREATE_ROOT_STUDY_TOOL, GET_STUDY_TOOL -> {
                    rootStudyReadbackFailed = true
                    rootStudyAutoFocusFailed = false
                }
                SELECT_VOICE_STUDY_TOOL -> {
                    rootStudyReadbackFailed = false
                    rootStudyAutoFocusFailed = true
                    discardStudyQuestionPurpose()
                }
            }
        } else if (ownsCurrentStudyUpdatePipeline) {
            currentStudyUpdatePipelineOwner = null
            when (rejectedCall.toolName) {
                UPDATE_STUDY_TOOL -> {
                    studyMutationFailureFollowupPending = true
                    studyUpdateAutoFocusFailedFollowupPending = false
                    studyUpdateCommittedFocusSupersededFollowupPending = false
                }
                SELECT_VOICE_STUDY_TOOL -> {
                    studyMutationFailureFollowupPending = false
                    studyUpdateAutoFocusFailedFollowupPending = true
                    studyUpdateCommittedFocusSupersededFollowupPending = false
                }
            }
            discardStudyQuestionPurpose()
        } else if (rejectedCall.toolName == CREATE_STUDY_TOPIC_TOOL) {
            latestFocusIntentBinding?.childStudyCreationAuthorization
                ?.takeIf { it.isBoundToServerCall(callId) }
                ?.let { authorization ->
                    authorization.invalidate()
                    studyMutationFailureFollowupPending = true
                    discardStudyQuestionPurpose()
                }
        } else if (rejectedCall.toolName == UPDATE_STUDY_TOOL) {
            latestFocusIntentBinding?.studyUpdateAuthorization
                ?.takeIf { it.isBoundToServerCall(callId) }
                ?.let { authorization ->
                    authorization.invalidate()
                    studyMutationFailureFollowupPending = true
                    discardStudyQuestionPurpose()
                }
        }

        if (toolCoordinator?.hasPending != true) {
            toolAcknowledgementTimer?.dispose()
            toolAcknowledgementTimer = null
        }
        onProviderTurnFailure(
            providerFailureDiagnostic(
                VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                node,
                VoiceTutorProviderEventCorrelation.INTERNAL_CONTROL,
                0,
                VoiceTutorProviderTurnFailureAction.SERVER_CALL_REJECTED,
            ),
        )
        createNormalResponseIfReady()
        return VoiceTutorProviderRelayDisposition.DROP
    }

    private fun abandonUncorrelatedProviderResponse(
        node: com.fasterxml.jackson.databind.JsonNode,
        correlation: VoiceTutorProviderEventCorrelation,
    ): VoiceTutorProviderRelayDisposition {
        val failedGeneration = activeResponseGeneration
        val acceptedToolCalls = activeResponseAcceptedToolCalls
        val learnerInputInFlight = learnerInputFenceActive()
        val abandonedResponseId = activeResponseId
        onProviderTurnFailure(
            providerFailureDiagnostic(
                VoiceTutorProviderTurnFailureKind.PROVIDER_ERROR,
                node,
                correlation,
                (activeResponseRetryAttempt + 1).coerceAtLeast(1),
                VoiceTutorProviderTurnFailureAction.TURN_ABANDONED,
            ),
        )
        rollbackFailedProviderResponse(failedGeneration, abandonedResponseId)
        abandonProviderResponseTurn(
            promptForFreshInput = !acceptedToolCalls && !learnerInputInFlight,
            abandonedResponseId = abandonedResponseId,
        )
        if (learnerInputInFlight) createNormalResponseIfReady()
        return VoiceTutorProviderRelayDisposition.DROP
    }

    private fun recoverProviderResponseTurn(
        kind: VoiceTutorProviderTurnFailureKind,
        responseId: String? = activeResponseId,
        providerErrorNode: com.fasterxml.jackson.databind.JsonNode? = null,
        eventCorrelation: VoiceTutorProviderEventCorrelation = VoiceTutorProviderEventCorrelation.ACTIVE_RESPONSE,
    ): VoiceTutorProviderRelayDisposition {
        if (!responseActive) return VoiceTutorProviderRelayDisposition.DROP
        val attempt = activeResponseRetryAttempt + 1
        val acceptedToolCalls = activeResponseAcceptedToolCalls
        val learnerInputInFlight = learnerInputFenceActive()
        val retryActivationBlocked = learnerInputInFlight ||
            pauseCoordinator?.blocksResponses == true ||
            pendingSpokenLessonEnd != null || spokenLessonEndRequested ||
            toolCoordinator?.hasPending == true ||
            activeResponseLessonRevision != currentLessonRevision
        // Once a tool call has been accepted its side effect belongs to this
        // exact response generation. Re-generating it could execute the same
        // mutation twice, so that turn is abandoned instead of retried. A
        // provider retry is also allowed only when it can be activated in this
        // same synchronized turn: otherwise a newer learner item could enter
        // provider context first and inherit the failed turn's attribution.
        val retryScheduled = attempt <= MAX_PROVIDER_RESPONSE_RETRIES &&
            !acceptedToolCalls && !retryActivationBlocked
        onProviderTurnFailure(
            providerFailureDiagnostic(
                kind,
                providerErrorNode,
                eventCorrelation,
                attempt,
                if (retryScheduled) {
                    VoiceTutorProviderTurnFailureAction.RETRY_SCHEDULED
                } else {
                    VoiceTutorProviderTurnFailureAction.TURN_ABANDONED
                },
            ),
        )
        val failedGeneration = activeResponseGeneration
        val retry = PendingProviderResponseRetry(
            toolChoice = if (activeResponseAllowsTools) "auto" else "none",
            lessonRevision = activeResponseLessonRevision,
            candidateDiscovery = activeResponseCandidateDiscovery,
            respondsToStudyAnswer = activeResponseRespondsToStudyAnswer,
            studyAnswer = activeResponseStudyAnswer,
            respondsToLearnerQuestion = activeResponseRespondsToLearnerQuestion,
            studyQuestionPurpose = activeResponseStudyQuestionPurpose,
            retryAttempt = attempt,
            replacesGeneration = failedGeneration,
            boundaryCheckpoint = activeResponseBoundaryCheckpoint
                ?: TutorBoundaryCheckpoint(lastTutorSpeechStoppedOrder, lastSpokenResponseGeneration),
            abandonedResponseId = responseId?.takeIf(::validProviderResponseId),
            instructionOverride = activeResponseInstructionOverride,
        )
        rollbackFailedProviderResponse(failedGeneration, responseId)
        if (retryScheduled) {
            pendingProviderResponseRetry = retry
            createNormalResponseIfReady()
        } else {
            pendingProviderResponseRetry = null
            abandonProviderResponseTurn(
                promptForFreshInput = !acceptedToolCalls && !learnerInputInFlight,
                abandonedResponseId = responseId,
            )
            if (learnerInputInFlight) createNormalResponseIfReady()
        }
        return VoiceTutorProviderRelayDisposition.DROP
    }

    private fun learnerInputFenceActive(): Boolean =
        userSpeaking || activeClientSpeechSequence != null ||
            pendingSpeechCommitCount > 0 || pendingInputCommits.isNotEmpty() ||
            delayedStopCommit != null || stopAwaitingTutorFinalization != null ||
            speechAwaitingTutorFinalizationGeneration != null ||
            queuedCommittedTurn || inputCoordinator?.hasPending == true

    private fun rollbackFailedProviderResponse(failedGeneration: Long, responseId: String?) {
        // Revoke the failed response's staged teacher generation before
        // restoring or retrying. No learner assessment may inherit context
        // from a response that never crossed the exact playout boundary.
        inputCoordinator?.teacherResponseStarted()
        activeResponseCreateEventId?.let {
            rememberBoundedId(recentFailedResponseCreateEventIds, it, MAX_RECENT_FAILED_RESPONSES)
        }
        responseId?.takeIf(::validProviderResponseId)?.let {
            rememberBoundedId(recentFailedResponseIds, it, MAX_RECENT_FAILED_RESPONSES)
        }
        activeResponseBoundaryCheckpoint?.let { boundary ->
            lastTutorSpeechStoppedOrder = boundary.tutorSpeechStoppedOrder
            lastSpokenResponseGeneration = boundary.spokenResponseGeneration
        }
        releaseSpeechAwaitingFailedTutorResponse(failedGeneration)
        playbackTimer?.dispose()
        playbackTimer = null
        responseTimer?.dispose()
        responseTimer = null
        responseActive = false
        activeResponseCreateEventId = null
        activeResponseId = null
        activeResponseAllowsTools = false
        activeResponseInstructionOverride = null
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        providerOutputBufferStarted = false
        providerAudioObserved = false
        toolOnlyResponse = false
        activeResponseAcceptedToolCalls = false
        activeResponseToolNames = emptySet()
        activeTutorTranscripts.clear()
        activeTutorFinalTranscriptEvents.clear()
        activeTutorTranscriptItemIds.clear()
        activeTutorTranscriptOverflow = false
        activeTargetOffer = null
        reusableStudyUpdateTargetOfferId = null
        activeTargetOfferExchangeEvidence = null
        activeResponseCandidateDiscovery = null
        activeResponseRespondsToStudyAnswer = false
        activeResponseStudyAnswer = null
        activeResponseRespondsToLearnerQuestion = false
        activeResponseStudyQuestionPurpose = null
        activeResponseTutorContext = ""
        activeResponseTutorContextForAssessment = ""
        activeResponseBoundaryCheckpoint = null
        activeResponseRetryAttempt = 0
    }

    private fun releaseSpeechAwaitingFailedTutorResponse(failedGeneration: Long) {
        if (speechAwaitingTutorFinalizationGeneration != failedGeneration) return
        speechAwaitingTutorFinalizationGeneration = null
        val stopped = stopAwaitingTutorFinalization
        stopAwaitingTutorFinalization = null
        clearActiveSpeechTutorBoundary()
        if (stopped == null) return
        val unbound = stopped.copy(
            precedingTutorSpeechStoppedOrder = 0,
            precedingSpokenResponseGeneration = 0,
            precedingTutorProviderItemId = null,
            precedingTutorStudyQuestionEligible = false,
            precedingTutorContinuationEligible = false,
            precedingTutorFeedbackForStudyAnswer = false,
            precedingQuestionProviderItemId = null,
            precedingAnswerProviderItemId = null,
            precedingTutorFeedbackProviderItemId = null,
            precedingTutorNavigationOfferProviderItemId = null,
            targetOffer = null,
            mixedTutorBoundary = true,
        )
        val pending = delayedStopCommit?.let { before ->
            delayedStopCommitTimer?.dispose()
            delayedStopCommitTimer = null
            delayedStopCommit = null
            mergeMixedTutorBoundaryStopCommits(before, unbound)
        } ?: unbound
        if (activeClientSpeechSequence != null) {
            delayedStopCommit = pending
        } else {
            requestPendingStopCommit(pending)
        }
    }

    private fun abandonProviderResponseTurn(
        promptForFreshInput: Boolean = true,
        abandonedResponseId: String? = null,
    ) {
        // Restore the last complete tutor context so pending/new speech can be
        // assessed without treating the partial provider response as teaching.
        withInputCoordinator { teacherResponseCompleted("", nanoTime()) }
        if (pendingSpokenLessonEnd != null) {
            emitSpokenLessonEndLifecycle()
        } else if (promptForFreshInput) {
            val payload = linkedMapOf<String, Any>("type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT)
            abandonedResponseId?.takeIf(::validProviderResponseId)?.let {
                payload[VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD] = it
            }
            val result = clientControls.tryEmitNext(
                mapper.writeValueAsString(payload),
            )
            if (result.isFailure && !closed) {
                terminate(IllegalStateException("Voice Tutor client control buffer overflowed."))
            }
        }
        // A completed MCP output already has exact-once provider context. It
        // may continue with a fresh spoken response, but never by replaying the
        // response generation that accepted the tool call.
        if (toolCoordinator?.continuationReady == true) createNormalResponseIfReady()
    }

    private fun providerErrorCorrelation(causedEventId: String?): VoiceTutorProviderEventCorrelation = when {
        causedEventId == null -> VoiceTutorProviderEventCorrelation.MISSING
        causedEventId == activeResponseCreateEventId -> VoiceTutorProviderEventCorrelation.ACTIVE_RESPONSE
        causedEventId in recentFailedResponseCreateEventIds -> VoiceTutorProviderEventCorrelation.STALE_RESPONSE
        causedEventId.startsWith("buddystudy-internal-") -> VoiceTutorProviderEventCorrelation.INTERNAL_CONTROL
        else -> VoiceTutorProviderEventCorrelation.EXTERNAL_EVENT
    }

    private fun providerFailureDiagnostic(
        kind: VoiceTutorProviderTurnFailureKind,
        providerErrorNode: com.fasterxml.jackson.databind.JsonNode?,
        correlation: VoiceTutorProviderEventCorrelation,
        attempt: Int,
        action: VoiceTutorProviderTurnFailureAction,
    ) = VoiceTutorProviderTurnFailureDiagnostic(
        kind = kind,
        providerErrorType = providerErrorNode?.let(::safeProviderErrorType) ?: "none",
        providerErrorCode = providerErrorNode?.let(::safeProviderErrorCode) ?: "none",
        eventCorrelation = correlation,
        causedEventRef = providerEventReference(providerErrorNode?.let(::safeProviderCausedEventId)),
        attempt = attempt,
        action = action,
    )

    private fun rememberBoundedId(values: LinkedHashSet<String>, value: String, maximum: Int) {
        values.remove(value)
        values.add(value)
        while (values.size > maximum) values.remove(values.first())
    }

    private fun matchesActiveResponse(responseId: String): Boolean =
        responseActive && responseId.isNotBlank() && (activeResponseId == null || responseId == activeResponseId)

    private fun validProviderResponseId(responseId: String): Boolean =
        PROVIDER_RESPONSE_ID.matches(responseId)

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
        val successfulTutorTranscriptEvents = if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && spokenResponseCompleted
        ) {
            completedTutorTranscriptEventsForPersistence(false)
        } else {
            emptyList()
        }
        // Semantic evidence must inspect the exact bounded string that will be
        // persisted. A longer row fails closed rather than assessing a prefix.
        val completedPersistedTutorTranscript = successfulTutorTranscriptEvents.singleOrNull()
            ?.let { mapper.readTree(it).path("transcript") }
            ?.takeIf { it.isTextual }
            ?.textValue()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.length <= MAX_TUTOR_QUESTION_ASSESSMENT_CHARACTERS }
        val completedRootCreationFollowup = activeResponseInstructionOverride in setOf(
            ROOT_STUDY_CREATION_FOLLOWUP_INSTRUCTIONS,
            ROOT_STUDY_CREATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS,
            ROOT_STUDY_CREATION_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS,
            ROOT_STUDY_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS,
        )
        val completedRootCreationFollowupWithoutTools = completedRootCreationFollowup &&
            !activeResponseAcceptedToolCalls && activeResponseToolNames.isEmpty()
        val completedStudyMutationFailureFollowup =
            activeResponseInstructionOverride == STUDY_MUTATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
        val completedStudyUpdateAutoFocusFailureFollowup =
            activeResponseInstructionOverride == STUDY_UPDATE_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS
        val completedStudyUpdateAutoFocusFailureFollowupWithoutTools =
            completedStudyUpdateAutoFocusFailureFollowup &&
                !activeResponseAcceptedToolCalls && activeResponseToolNames.isEmpty()
        val completedStudyUpdateCommittedFocusSupersededFollowupWithoutTools =
            activeResponseInstructionOverride ==
                STUDY_UPDATE_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS &&
                !activeResponseAcceptedToolCalls && activeResponseToolNames.isEmpty()
        val purpose = activeResponseStudyQuestionPurpose
        val focus = confirmedStudyFocus
        val proposedStudyQuestionEvidence = purpose?.takeIf {
            spokenResponseCompleted && completedTutorItemId != null && completedPersistedTutorTranscript != null &&
                !activeTutorTranscriptOverflow &&
                focus != null && focus.revision == currentLessonRevision &&
                it.lessonRevision == activeResponseLessonRevision &&
                it.lessonRevision == focus.revision &&
                !activeResponseRespondsToStudyAnswer && !activeResponseRespondsToLearnerQuestion &&
                !activeResponseAcceptedToolCalls && !activeResponseHadCandidateNavigation &&
                activeResponseToolNames.isEmpty()
        }?.let {
            SpokenTutorPurposeEvidence(
                lessonRevision = it.lessonRevision,
                responseGeneration = completedResponseGeneration,
                tutorSpeechStoppedOrder = lastTutorSpeechStoppedOrder,
                providerItemId = requireNotNull(completedTutorItemId),
                transcript = requireNotNull(completedPersistedTutorTranscript),
            )
        }
        val proposedFeedbackEvidence = activeResponseStudyAnswer?.takeIf { identity ->
            activeResponseRespondsToStudyAnswer && spokenResponseCompleted &&
                completedTutorItemId != null && completedPersistedTutorTranscript != null &&
                !activeTutorTranscriptOverflow &&
                focus != null && focus.revision == currentLessonRevision &&
                identity.lessonRevision == activeResponseLessonRevision &&
                identity.lessonRevision == focus.revision &&
                !activeResponseRespondsToLearnerQuestion && activeResponseStudyQuestionPurpose == null &&
                (!activeResponseAcceptedToolCalls || activeResponseToolNames.isNotEmpty()) &&
                activeResponseToolNames.all { it in SAFE_PURPOSE_PRESERVING_READ_TOOLS }
        }?.let { identity ->
            StudyAnswerFeedbackEvidence(
                identity = identity,
                feedbackProviderItemId = requireNotNull(completedTutorItemId),
                tutorContext = requireNotNull(completedPersistedTutorTranscript),
            )
        }
        val proposedFeedbackContinuationAnchor = proposedFeedbackEvidence?.let {
            SpokenTutorPurposeEvidence(
                lessonRevision = activeResponseLessonRevision,
                responseGeneration = completedResponseGeneration,
                tutorSpeechStoppedOrder = lastTutorSpeechStoppedOrder,
                providerItemId = requireNotNull(completedTutorItemId),
            )
        }
        val completedContinuationAnchor = if (
            spokenResponseCompleted && completedTutorItemId != null &&
            focus != null && focus.revision == activeResponseLessonRevision &&
            !activeResponseAcceptedToolCalls && activeResponseToolNames.isEmpty() &&
            activeResponseRespondsToLearnerQuestion
        ) {
            SpokenTutorPurposeEvidence(
                lessonRevision = activeResponseLessonRevision,
                responseGeneration = completedResponseGeneration,
                tutorSpeechStoppedOrder = lastTutorSpeechStoppedOrder,
                providerItemId = completedTutorItemId,
            )
        } else {
            null
        }
        val completedTutorContextForAssessment = if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && spokenResponseCompleted
        ) {
            activeResponseTutorContextForAssessment
        } else {
            ""
        }
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            // Promotion must observe the same structured, content-index ordered
            // transcript parts that back persistence and learner assessment.
            // In stopped-then-done ordering this is the first eligible promotion.
            promoteTargetOfferIfReady()
            if (!spokenResponseCompleted) {
                // A tool-only response has no playout or transcript persistence
                // boundary. response.done is its exact successful completion.
                withInputCoordinator {
                    teacherResponseCompleted(activeResponseTutorContextForAssessment, nanoTime())
                }
            }
        }
        val proposedFeedbackNavigationOffer = proposedFeedbackEvidence?.let {
            activeTargetOffer?.takeIf { offer ->
                completedTutorItemId != null &&
                    offer.tutorResponseGeneration == completedResponseGeneration &&
                    offer.lessonRevision == activeResponseLessonRevision
            }?.let { offer ->
                PendingFeedbackNavigationOffer(offer.offerId, requireNotNull(completedTutorItemId))
            }
        }
        activeTutorFinalTranscriptEvents.clear()
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
                // Any spoken answer response consumes the answer-to-feedback slot. Semantic
                // approval and durable linkage happen only at the post-playout boundary below.
                lastSpokenStudyAnswer = identity
            }
        }
        if (spokenResponseCompleted) {
            val offer = activeTargetOffer?.takeIf {
                it.tutorResponseGeneration == completedResponseGeneration &&
                    it.lessonRevision == activeResponseLessonRevision
            }
            if (offer != null) {
                val feedback = pendingNavigationFeedback?.takeIf {
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
        playbackTimer?.dispose()
        playbackTimer = null
        responseTimer?.dispose()
        responseTimer = null
        responseActive = false
        activeResponseCreateEventId = null
        activeResponseId = null
        activeResponseAllowsTools = false
        activeResponseInstructionOverride = null
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        if (!spokenResponseCompleted) {
            pendingStudyQuestionPurpose = purpose?.takeIf {
                toolOnlyResponse && activeResponseToolNames.isNotEmpty() &&
                    activeResponseToolNames.all { name -> name in SAFE_PURPOSE_PRESERVING_READ_TOOLS } &&
                    it.lessonRevision == currentLessonRevision
            }
        }
        activeResponseAcceptedToolCalls = false
        activeResponseToolNames = emptySet()
        activeResponseCandidateDiscovery = null
        activeResponseHadCandidateNavigation = false
        activeResponseRespondsToStudyAnswer = false
        activeResponseStudyAnswer = null
        activeResponseRespondsToLearnerQuestion = false
        activeResponseStudyQuestionPurpose = null
        activeResponseTutorContext = ""
        activeResponseTutorContextForAssessment = ""
        if (spokenResponseCompleted && completedRootCreationFollowupWithoutTools) {
            rootStudyCreationFollowupPending = false
            rootStudyReadbackFailed = false
            rootStudyAutoFocusFailed = false
            rootStudyCommittedFocusSuperseded = false
        }
        if (spokenResponseCompleted && completedStudyMutationFailureFollowup &&
            !activeResponseAcceptedToolCalls && activeResponseToolNames.isEmpty()
        ) {
            studyMutationFailureFollowupPending = false
        }
        if (spokenResponseCompleted && completedStudyUpdateAutoFocusFailureFollowupWithoutTools) {
            studyUpdateAutoFocusFailedFollowupPending = false
        }
        if (spokenResponseCompleted && completedStudyUpdateCommittedFocusSupersededFollowupWithoutTools) {
            studyUpdateCommittedFocusSupersededFollowupPending = false
        }
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND && spokenResponseCompleted) {
            if (pendingPostRelayBoundary != null) {
                terminate(VoiceTutorProviderProtocolException())
                return
            }
            cancelInputCheckpointTimer()
            val token = nextPostRelayBoundaryToken
            nextPostRelayBoundaryToken = if (token == Long.MAX_VALUE) 1 else token + 1
            pendingPostRelayBoundary = PendingPostRelayBoundary(
                token = token,
                completedResponseGeneration = completedResponseGeneration,
                completedTutorItemId = completedTutorItemId,
                tutorTranscriptEvents = successfulTutorTranscriptEvents,
                tutorContextForAssessment = completedTutorContextForAssessment,
                studyQuestionEvidence = null,
                continuationAnchor = completedContinuationAnchor,
                proposedStudyQuestionEvidence = proposedStudyQuestionEvidence,
                questionAssessmentToken = token.takeIf { proposedStudyQuestionEvidence != null },
                feedbackEvidence = null,
                proposedFeedbackEvidence = proposedFeedbackEvidence,
                proposedFeedbackContinuationAnchor = proposedFeedbackContinuationAnchor,
                proposedFeedbackNavigationOffer = proposedFeedbackNavigationOffer,
                feedbackAssessmentToken = token.takeIf { proposedFeedbackEvidence != null },
            )
            if (proposedStudyQuestionEvidence != null && focus != null && completedPersistedTutorTranscript != null) {
                val emitted = spokenQuestionAssessmentWork.tryEmitNext(
                    VoiceTutorSpokenQuestionAssessmentAction(
                        token = token,
                        focusTopic = focus.topic,
                        focusDifficulty = focus.difficulty,
                        transcript = completedPersistedTutorTranscript,
                    ),
                )
                if (emitted.isFailure) terminate(VoiceTutorProviderProtocolException())
            }
            if (proposedFeedbackEvidence != null && focus != null && completedPersistedTutorTranscript != null) {
                val identity = proposedFeedbackEvidence.identity
                val emitted = spokenFeedbackAssessmentWork.tryEmitNext(
                    VoiceTutorSpokenFeedbackAssessmentAction(
                        token = token,
                        focusTopic = focus.topic,
                        focusDifficulty = focus.difficulty,
                        questionTranscript = identity.questionTranscript,
                        answerTranscript = identity.answerTranscript,
                        feedbackTranscript = completedPersistedTutorTranscript,
                        allowsNavigationOffer = proposedFeedbackNavigationOffer != null,
                    ),
                )
                if (emitted.isFailure) terminate(VoiceTutorProviderProtocolException())
            }
            return
        }
        resumeCompletedResponse(completedResponseGeneration, completedTutorItemId)
    }

    private fun resumeAfterPostRelayBoundary(pending: PendingPostRelayBoundary) {
        // Tutor persistence has succeeded. Release semantic learner work only
        // now, while the fence still prevents a nested next response/lifecycle.
        withInputCoordinator {
            teacherResponseCompleted(pending.tutorContextForAssessment, nanoTime())
        }
        if (closed) return
        lastSpokenStudyQuestion = pending.studyQuestionEvidence
        lastSpokenContinuationAnchor = pending.continuationAnchor
        pending.feedbackEvidence?.let { feedback ->
            completedStudyAnswerFeedback = feedback
            pendingNavigationFeedback = feedback
            pending.feedbackNavigationOffer?.let { navigation ->
                if (activeTargetOffer?.offerId == navigation.offerId) {
                    activeTargetOfferExchangeEvidence = TargetOfferExchangeEvidence(
                        navigation.offerId,
                        feedback,
                        navigation.providerItemId,
                    )
                }
                // A combined response gets exactly this one opportunity to
                // bind its semantically approved feedback to its own offer.
                pendingNavigationFeedback = null
            }
        }
        finalizeSpeechAwaitingTutorResponse(
            pending.completedResponseGeneration,
            pending.completedTutorItemId,
        )
        pendingPostRelayBoundary = null
        continueAfterCompletedResponse(pending.completedResponseGeneration)
    }

    private fun resumeCompletedResponse(
        completedResponseGeneration: Long,
        completedTutorItemId: String?,
    ) {
        lastSpokenStudyQuestion = null
        lastSpokenContinuationAnchor = null
        finalizeSpeechAwaitingTutorResponse(completedResponseGeneration, completedTutorItemId)
        continueAfterCompletedResponse(completedResponseGeneration)
    }

    private fun continueAfterCompletedResponse(completedResponseGeneration: Long) {
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
                precedingTutorStudyQuestionEligible = activeSpeechPrecedingTutorStudyQuestionEligible,
                precedingTutorContinuationEligible = activeSpeechPrecedingTutorContinuationEligible,
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
            precedingTutorStudyQuestionEligible = false,
            precedingTutorContinuationEligible = false,
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
            precedingTutorStudyQuestionEligible = pending.precedingTutorStudyQuestionEligible,
            precedingTutorContinuationEligible = pending.precedingTutorContinuationEligible,
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
        activeSpeechPrecedingTutorStudyQuestionEligible = false
        activeSpeechPrecedingTutorContinuationEligible = false
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
        in TUTOR_TRANSCRIPT_EVENTS -> if (
            transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            matchesKnownActiveResponse(node.path("response_id").asText())
        ) {
            VoiceTutorProviderRelayDisposition.PERSIST_ONLY
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }
        else -> VoiceTutorProviderRelayDisposition.DROP
    }

    private fun discardStudyQuestionPurpose() {
        pendingStudyQuestionPurpose = null
    }

    private data class ConfirmedStudyFocus(
        val studyId: Long,
        val revision: Long,
        val parentStudyId: Long?,
        val topic: String,
        val difficulty: Int,
    )

    private enum class StudyQuestionPurposeSource { FOCUS_TOOL, CONTINUE_STUDY }

    private data class StudyQuestionPurpose(
        val lessonRevision: Long,
        val source: StudyQuestionPurposeSource,
    )

    private data class SpokenTutorPurposeEvidence(
        val lessonRevision: Long,
        val responseGeneration: Long,
        val tutorSpeechStoppedOrder: Long,
        val providerItemId: String,
        val transcript: String = "",
    ) {
        fun matches(
            revision: Long,
            generation: Long,
            stoppedOrder: Long,
            itemId: String?,
        ): Boolean = lessonRevision == revision && responseGeneration == generation &&
            tutorSpeechStoppedOrder > 0 && tutorSpeechStoppedOrder == stoppedOrder &&
            providerItemId == itemId
    }

    private data class StudyAnswerIdentity(
        val lessonRevision: Long,
        val questionProviderItemId: String,
        val answerProviderItemId: String,
        val questionTranscript: String,
        val answerTranscript: String,
    )

    private data class PersistedStudyAnswerPart(
        val providerItemId: String,
        val transcript: String,
    )

    private data class PersistedStudyAnswerSequence(
        val lessonRevision: Long,
        val questionProviderItemId: String,
        val answerParts: MutableList<PersistedStudyAnswerPart> = mutableListOf(),
        var invalid: Boolean = false,
    )

    private data class PendingStudyAnswerGroup(
        val lessonRevision: Long,
        val questionProviderItemId: String,
        val answerParts: List<PersistedStudyAnswerPart>,
    )

    private data class StudyAnswerFeedbackEvidence(
        val identity: StudyAnswerIdentity,
        val feedbackProviderItemId: String,
        val tutorContext: String,
    )

    /** The last fully spoken tutor boundary before one provider attempt began. */
    private data class TutorBoundaryCheckpoint(
        val tutorSpeechStoppedOrder: Long,
        val spokenResponseGeneration: Long,
    )

    private data class RootStudyPipelineOwner(
        val generation: Long,
        val learnerProviderItemId: String,
        val lessonRevision: Long,
    )

    private data class RootStudyCreationExpectation(
        val owner: RootStudyPipelineOwner,
        val topic: String,
        val difficulty: Int,
        val creationAuthorization: VoiceTutorRootStudyCreationAuthorization,
        val startLessonAuthorization: VoiceTutorFocusAuthorization?,
    )

    private data class RootStudyReadbackExpectation(
        val owner: RootStudyPipelineOwner,
        val studyId: Long,
        val topic: String,
        val difficulty: Int,
        val startLessonRequested: Boolean,
        val startLessonAuthorization: VoiceTutorFocusAuthorization?,
    )

    private data class RootStudyAutoFocusExpectation(
        val owner: RootStudyPipelineOwner,
        val studyId: Long,
        val topic: String,
        val difficulty: Int,
        val learnerProviderItemId: String,
        val lessonRevision: Long,
        val authorization: VoiceTutorFocusAuthorization,
    ) {
        val candidate = VoiceTutorStudyTargetCandidate(studyId, null, topic, difficulty)
        val traversal = VoiceTutorStudyTargetTraversal()
    }

    private data class StudyUpdatePipelineOwner(
        val generation: Long,
        val learnerProviderItemId: String,
        /** Revision at which the learner's exact update-and-optional-start intent was persisted. */
        val sourceLessonRevision: Long,
    )

    private data class StudyUpdateExpectation(
        val owner: StudyUpdatePipelineOwner,
        val targetProof: VoiceTutorStudyUpdateTargetProof,
        val topic: String?,
        val difficulty: Int?,
        val updateAuthorization: VoiceTutorStudyUpdateAuthorization,
        val startLessonAuthorization: VoiceTutorFocusAuthorization?,
        val traversal: VoiceTutorStudyTargetTraversal,
    )

    private data class StudyUpdateAutoFocusExpectation(
        val owner: StudyUpdatePipelineOwner,
        val candidate: VoiceTutorStudyTargetCandidate,
        val traversal: VoiceTutorStudyTargetTraversal,
        val expectedCurrentRevision: Long,
        val authorization: VoiceTutorFocusAuthorization,
    )

    /** Logical response state retained across one provider-local regeneration. */
    private data class PendingProviderResponseRetry(
        val toolChoice: String,
        val lessonRevision: Long,
        val candidateDiscovery: CandidateOfferPool?,
        val respondsToStudyAnswer: Boolean,
        val studyAnswer: StudyAnswerIdentity?,
        val respondsToLearnerQuestion: Boolean,
        val studyQuestionPurpose: StudyQuestionPurpose?,
        val retryAttempt: Int,
        val replacesGeneration: Long,
        val boundaryCheckpoint: TutorBoundaryCheckpoint,
        val abandonedResponseId: String?,
        val instructionOverride: String?,
    )

    private data class PendingPostRelayBoundary(
        val token: Long,
        val completedResponseGeneration: Long,
        val completedTutorItemId: String?,
        val tutorTranscriptEvents: List<String>,
        val tutorContextForAssessment: String,
        val studyQuestionEvidence: SpokenTutorPurposeEvidence?,
        val continuationAnchor: SpokenTutorPurposeEvidence?,
        val proposedStudyQuestionEvidence: SpokenTutorPurposeEvidence? = null,
        val questionAssessmentToken: Long? = null,
        val feedbackEvidence: StudyAnswerFeedbackEvidence? = null,
        val feedbackNavigationOffer: PendingFeedbackNavigationOffer? = null,
        val proposedFeedbackEvidence: StudyAnswerFeedbackEvidence? = null,
        val proposedFeedbackContinuationAnchor: SpokenTutorPurposeEvidence? = null,
        val proposedFeedbackNavigationOffer: PendingFeedbackNavigationOffer? = null,
        val feedbackAssessmentToken: Long? = null,
        val claimed: Boolean = false,
    )

    private data class StagedTutorTranscript(
        val responseId: String,
        val itemId: String,
        val contentIndex: Int,
        val transcript: String,
    )

    private data class TargetOfferExchangeEvidence(
        val offerId: Long,
        val feedback: StudyAnswerFeedbackEvidence,
        val navigationOfferProviderItemId: String,
    )

    private data class PendingFeedbackNavigationOffer(
        val offerId: Long,
        val providerItemId: String,
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
        val precedingTutorStudyQuestionEligible: Boolean = false,
        val precedingTutorContinuationEligible: Boolean = false,
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
        val precedingTutorStudyQuestionEligible: Boolean = false,
        val precedingTutorContinuationEligible: Boolean = false,
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
        val studyMutationContext: VoiceTutorStudyMutationContext? = null,
        val providerItemId: String? = null,
        val inputIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        val targetStudyId: Long? = null,
        val targetOfferId: Long? = null,
        val focusAuthorization: VoiceTutorFocusAuthorization? = null,
        val rootStudyCreationAuthorization: VoiceTutorRootStudyCreationAuthorization? = null,
        val childStudyCreationAuthorization: VoiceTutorChildStudyCreationAuthorization? = null,
        val studyUpdateAuthorization: VoiceTutorStudyUpdateAuthorization? = null,
        val studyQuestionTranscript: String? = null,
        val inputTranscript: String? = null,
        val studyAnswerProviderItemId: String? = null,
        val precedingTutorStudyQuestionEligible: Boolean = false,
        val precedingTutorContinuationEligible: Boolean = false,
    )

    private data class CandidateOfferPool(
        val lessonRevision: Long,
        val currentFocusStudyId: Long?,
        val candidates: List<VoiceTutorStudyTargetCandidate>,
        val candidateTraversals: Map<Long, VoiceTutorStudyTargetTraversal>,
        val purpose: CandidateOfferPurpose = CandidateOfferPurpose.DISCOVERY,
    )

    private enum class CandidateOfferPurpose { DISCOVERY, UPDATED_STUDY_SELECTION }

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
        const val MAX_RECENT_FAILED_RESPONSES = 16
        const val MAX_PROVIDER_RESPONSE_RETRIES = 1
        const val MAX_PROVIDER_ITEM_ID_CHARACTERS = 191
        const val MAX_PERSISTED_STUDY_ANSWER_SEQUENCES = 16
        const val MAX_TUTOR_CONTEXT_CHARACTERS = 4_000
        const val MAX_TUTOR_QUESTION_ASSESSMENT_CHARACTERS = 4_000
        const val MAX_DEFERRED_TUTOR_TRANSCRIPT_CHARACTERS = 32_000
        const val MAX_TUTOR_CONTEXT_PARTS = 8
        const val MAX_TARGET_OFFER_CANDIDATES = 16
        const val MAX_MUTATION_TARGET_CANDIDATES = 17
        const val MAX_DISCOVERY_GRAPH_NODES = 64
        const val MAX_DISCOVERY_QUERY_GROUPS = 16
        const val MAX_DISCOVERY_TREE_DEPTH = 32
        const val MAX_DISCOVERY_RESULT_CANDIDATES = 60L
        const val MAX_DISCOVERY_PAGE_LIMIT = 500L
        const val MAX_DISCOVERY_PAGE_COUNT = 60
        const val CREATE_ROOT_STUDY_TOOL = "create_root_study"
        const val CREATE_STUDY_TOPIC_TOOL = "create_study_topic"
        const val UPDATE_STUDY_TOOL = "update_study"
        const val GET_STUDY_TOOL = "get_study"
        const val SELECT_VOICE_STUDY_TOOL = "select_voice_study"
        val ROOT_STUDY_PIPELINE_TOOLS = setOf(
            CREATE_ROOT_STUDY_TOOL,
            GET_STUDY_TOOL,
            SELECT_VOICE_STUDY_TOOL,
        )
        const val OPENING_RESPONSE_INSTRUCTIONS =
            "Ask only one short direct question about which topic the learner wants to discuss. " +
                "Do not greet the learner, use a lead-in, introduce or name yourself, describe your role, say that you are an AI/tutor/teacher, mention readiness, or call any tool. " +
                "Use the configured session language; only when it is Korean, say exactly: 어떤 주제로 이야기해 볼까요?; otherwise ask the same direct question in that configured language."
        val STUDY_FOCUS_TOOLS = setOf(SELECT_VOICE_STUDY_TOOL, "advance_voice_study")
        val SAFE_PURPOSE_PRESERVING_READ_TOOLS = setOf(
            "list_records", "get_record", "list_study_learning_records", "get_voice_learning_record",
            "get_topic_stats", "get_study_growth",
        )
        const val ROOT_STUDY_CREATION_FOLLOWUP_INSTRUCTIONS =
            "The server has already executed the exact assessed create_root_study choice and then completed an exact get_study readback for its persisted id. " +
                "Never call any tool in this response, never call create_root_study again for that learner choice, and never ask whether to create it. " +
                "Using only the confirmed create and get_study results already in conversation context, briefly speak the exact saved root topic and level, then ask only whether the learner wants to start learning it. " +
                "That question is new lesson-start consent, not creation confirmation; creation alone never selects a lesson. " +
                "Do not begin teaching, ask a study question, summarize learning, or claim the lesson has started."
        const val ROOT_STUDY_CREATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS =
            "The server-owned create_root_study command has already been attempted, but its create result or exact get_study readback was not confirmed. " +
                "Never call any tool in this response, never retry create_root_study or get_study, and do not claim that the root was created, already existed, or was read back. " +
                "Briefly tell the learner that the saved result could not be verified and that they should inspect their study tree or make a fresh direct choice later. " +
                "Do not ask creation confirmation, start a lesson, ask a study question, or produce a learning summary."
        const val ROOT_STUDY_CREATION_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS =
            "The server completed the assessed root-study write and exact get_study readback, but could not safely confirm the independently requested immediate lesson focus. " +
                "Never call any tool in this response, never retry create_root_study, get_study, or select_voice_study, and do not claim that teaching started. " +
                "Briefly state that the saved root exists but this call could not enter it, then ask the learner to make a fresh topic choice. " +
                "Do not ask creation confirmation, ask a study question, or produce a learning summary."
        const val ROOT_STUDY_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS =
            "The exact created-root focus committed and the selected topic shown to the client is authoritative, but overlapping newer learner speech superseded permission to ask its automatic first question. " +
                "Never call any tool in this response and do not claim that focus failed, that the call could not enter the topic, or that teaching already continued. " +
                "Briefly say the saved topic is selected but its automatic first question was paused, then ask for one fresh learning direction. " +
                "Do not ask creation confirmation, ask a study question, or produce a learning summary."
        const val STUDY_MUTATION_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS =
            "The server could not safely execute the learner's current saved-study action, and that action was not retried. " +
                "Never call any tool in this response, never retry or restate the mutation as completed, and do not ask the learner to repeat the same command more clearly. " +
                "Briefly say that this study-tree change could not be processed in this call and invite a fresh choice. " +
                "Do not start a lesson, ask a study question, or produce a learning summary."
        const val STUDY_UPDATE_START_UNCONFIRMED_FOLLOWUP_INSTRUCTIONS =
            "The exact saved-study update committed, but this call could not safely enter the revised topic for the separately attested immediate lesson start. " +
                "Never call or retry update_study or select_voice_study in this response, and do not ask the learner to repeat the same request more clearly. " +
                "Briefly say that the change was saved but the call did not enter that topic, then invite one fresh topic choice. " +
                "Do not claim that teaching started, ask a study question, or produce a learning summary."
        const val STUDY_UPDATE_COMMITTED_FOCUS_SUPERSEDED_FOLLOWUP_INSTRUCTIONS =
            "The exact revised saved-study focus committed and the selected topic shown to the client is authoritative, but overlapping newer learner speech superseded permission to ask its automatic first question. " +
                "Never call or retry update_study or select_voice_study in this response, and do not claim that focus failed or that teaching already continued. " +
                "Briefly say that the revised topic is selected but its automatic first question was paused, then invite one fresh learning direction. " +
                "Do not ask a study question, request readiness, or produce a learning summary."
        const val STUDY_UPDATE_SELECTION_FOLLOWUP_INSTRUCTIONS =
            "The exact update_study write has already committed once. Never call or retry update_study in this response. " +
                "Briefly say that the change was saved, speak the exact revised saved-topic name from the tool result, and ask only whether the learner wants to start that topic now. " +
                "This is one direct lesson-start offer for the revised node, not another update confirmation. " +
                "Do not begin teaching, call any tool, ask a study question, or produce a learning summary."
        const val STUDY_QUESTION_RESPONSE_INSTRUCTIONS =
            "The server has authorized exactly one substantive study question for the current confirmed saved focus and level. " +
                "If prior node learning history is needed, first emit a strict tool-only response using only list_records, get_record, list_study_learning_records, get_voice_learning_record, get_topic_stats, or get_study_growth; never mix audio with that tool response. " +
                "After any such read result, ask the question in the next clean audio-only response. " +
                "Ask that one knowledge, understanding, reasoning, recall, comparison, or application question now, then wait for the learner's answer. " +
                "Do not greet, acknowledge, promise to begin, ask readiness or permission, discuss setup/settings/navigation, offer topics, give feedback, answer a learner question, call any other tool, or include a second question."
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
        val PROVIDER_RESPONSE_ID = Regex("[A-Za-z0-9_-]{1,191}")
    }
}

internal data class VoiceTutorProviderObservation(
    val disposition: VoiceTutorProviderRelayDisposition,
    val postRelayBoundary: VoiceTutorPostRelayBoundary?,
)

internal data class VoiceTutorSpokenQuestionAssessmentAction(
    val token: Long,
    val focusTopic: String,
    val focusDifficulty: Int,
    val transcript: String,
)

internal data class VoiceTutorSpokenFeedbackAssessmentAction(
    val token: Long,
    val focusTopic: String,
    val focusDifficulty: Int,
    val questionTranscript: String,
    val answerTranscript: String,
    val feedbackTranscript: String,
    val allowsNavigationOffer: Boolean,
)

internal data class VoiceTutorPostRelayBoundary(
    val token: Long,
    val tutorTranscriptEvents: List<String>,
)

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

internal class VoiceTutorProviderProtocolException : RuntimeException(
    "Voice Tutor provider event violated the realtime protocol.",
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
