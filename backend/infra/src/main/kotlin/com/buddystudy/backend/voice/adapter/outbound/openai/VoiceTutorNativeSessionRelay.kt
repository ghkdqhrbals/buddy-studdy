package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorUserInputContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorSpokenTerminationNotice
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuotaExhaustionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/** No input/question/feedback assessor is accepted by this runtime's dependency boundary. */
internal fun relayVoiceTutorNativeSession(
    providerSession: WebSocketSession,
    context: VoiceTutorWebRtcControlContext,
    clientEvents: Flow<String>,
    terminalEvents: Flow<VoiceTutorRelayTermination>,
    mcp: VoiceTutorMcpToolPort,
    connectTimeout: Duration,
    responseTimeout: Duration,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> {
    val diagnostics = VoiceTutorSidebandDiagnostics(context.callId)
    val controller = VoiceTutorNativeConversationController(responseTimeout, context.initialLessonRevision, context.session.language,
        onProviderTurnFailure = diagnostics::observeProviderTurnFailure, initialStudyId = context.session.studyId,
        sessionId = context.session.id, userInputEnabled = context.userInputEnabled)
    val handshake = VoiceTutorWebRtcSessionHandshake(
        context.callId, connectTimeout,
        expectedTools = voiceTutorRealtimeFunctionTools(nativeVoiceTutorDefinitions(mcp, context.userInputEnabled)),
        transcriptionLanguage = context.session.language,
        realtimeNative = true,
    )
    val release = terminalEvents.asFlux().next().flatMap { terminal ->
        diagnostics.markLocalTerminal(terminal)
        val notBefore = terminal.notBefore?.let { Duration.between(Instant.now(), it).coerceAtLeast(Duration.ZERO) }
            ?: Duration.ZERO
        if (terminal.spokenNotice == VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED) {
            controller.requestQuotaNotice()
            Mono.whenDelayError(
                controller.quotaCompletion().then(waitForNativeDrain(controller, Duration.ofSeconds(5))),
                Mono.delay(notBefore),
            ).timeout(notBefore.plusSeconds(VoiceTutorQuotaExhaustionPolicy.NOTICE_GRACE_SECONDS))
                .onErrorResume { error ->
                    if (error is VoiceTutorTranscriptIntegrityException) Mono.error(error) else Mono.empty()
                }
        } else {
            controller.beginDrain(terminal.cancelActiveResponse, terminal.preserveInterruptedTutor)
            waitForNativeDrain(controller, Duration.ofSeconds(5))
        }
    }.cache()
    val send = providerSession.send(Flux.concat(
        handshake.initialProviderEvents(), controller.providerEvents(),
    ).takeUntilOther(release).map(providerSession::textMessage))

    val receive = providerSession.receive().filter { it.type == WebSocketMessage.Type.TEXT }
        .map { it.payloadAsText }
        .concatMap { raw ->
            diagnostics.observeProviderEvent(raw)
            if (handshake.observeProviderEvent(raw)) Mono.empty<Void>() else {
                val forward = controller.observeProviderEvent(raw)
                // Preserve UI event order separately from storage and never await callbacks here.
                if (forward) controller.forwardClientEvent(raw)
                Mono.empty<Void>()
            }
        }.takeUntilOther(release).then(Mono.defer {
            controller.beginDrain(cancelActive = false)
            waitForNativeDrain(controller, Duration.ofSeconds(5))
        })
    val clientControls = clientEvents.asFlux().doOnNext {
        diagnostics.observeClientEvent(it)
        controller.observeClientEvent(it)
    }.takeUntilOther(release).then()
    val clock = Flux.interval(Duration.ofMillis(100)).doOnNext { controller.tick() }.takeUntilOther(release).then()
    val persistence = controller.persistenceEvents().concatMap { transcript ->
        mono {
            // A failed audit write cannot stall speech; mutations separately require exact durable rows.
            var persisted = false
            try {
                persisted = if (transcript.itemId.startsWith(VoiceTutorTranscriptMetadata.STRUCTURED_ITEM_PREFIX)) {
                    withTimeoutOrNull(5_000) { onProviderEvent(transcript.raw, true, false) } == true
                } else onProviderEvent(transcript.raw, true, false)
                if (!persisted) controller.markTranscriptIncomplete()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                LoggerFactory.getLogger("VoiceTutorNativeTranscript").warn(
                    "voice_tutor_native_transcript_persistence_failed kind={}", error.javaClass.simpleName,
                )
                controller.markTranscriptIncomplete()
            }
            // Cancellation is not an acknowledgement. Keep the row pending so
            // the terminal error fence detects a write cancelled by another worker.
            controller.transcriptCompleted(transcript.itemId, successful = persisted)
        }.then()
    }.takeUntilOther(release).then()
    val tools = nativeVoiceTutorToolRelay(controller, context, mcp).takeUntilOther(release)
    val userInputs = nativeVoiceTutorUserInputRelay(controller, context, mcp).takeUntilOther(release)
    val learningPolls = nativeVoiceTutorLearningProgressRelay(controller, context, mcp).takeUntilOther(release)
    val clientOutput = controller.clientEvents().concatMap { raw -> mono { onProviderEvent(raw, false, true) }.then() }
        .takeUntilOther(release).then()
    val lifecycle = controller.lifecycleEvents().concatMap { raw -> mono {
        val integrity = JsonMapperProvider.mapper.readTree(raw).path("type").asText() == VoiceTutorTranscriptMetadata.INCOMPLETE_EVENT
        try {
            val recorded = onProviderEvent(raw, false, false)
            if (integrity) {
                if (!recorded) throw VoiceTutorTranscriptIntegrityException()
                controller.transcriptIntegrityRecorded()
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (integrity) throw VoiceTutorTranscriptIntegrityException() else throw error }
    }.then() }
        .takeUntilOther(release).then()
    val ready = handshake.awaitConfirmation().then(mono {
        onProviderEvent(JsonMapperProvider.mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT)), false, true)
        controller.start()
    }.then())
    val work = Mono.firstWithSignal(
        receive, release, controller.failure(),
        // A completed control source must not cancel an in-flight transcript/tool write.
        clientControls.then(Mono.never<Void>()), clock.then(Mono.never<Void>()),
        persistence.then(Mono.never<Void>()), tools.then(Mono.never<Void>()), userInputs.then(Mono.never<Void>()), learningPolls.then(Mono.never<Void>()),
        clientOutput.then(Mono.never<Void>()), lifecycle.then(Mono.never<Void>()),
    ).doFinally { controller.close() }
    return Mono.`when`(send, ready, work).onErrorMap { error ->
        // firstWithSignal cancels losing workers before delivering its error.
        // The controller retains unresolved source state through that cancellation;
        // the outer handler then persists INCOMPLETE_TRANSCRIPT before finalization.
        if (error is VoiceTutorTranscriptIntegrityException || !controller.hasUnsettledTranscriptEvidence()) error
        else VoiceTutorTranscriptIntegrityException()
    }.doFinally { controller.close() }
}

internal fun nativeVoiceTutorDefinitions(mcp: VoiceTutorMcpToolPort, userInputEnabled: Boolean = false) =
    mcp.realtimeDefinitions() + VoiceTutorNativeConversationController.endCallDefinition +
        if (userInputEnabled) listOf(VoiceTutorUserInputContract.definition) else emptyList()

internal fun nativeVoiceTutorToolRelay(
    controller: VoiceTutorNativeConversationController,
    context: VoiceTutorWebRtcControlContext,
    mcp: VoiceTutorMcpToolPort,
): Mono<Void> = controller.toolActions().concatMap { call ->
    mono {
        if (!controller.beginTool(call.callId)) return@mono
        if (call.name == VoiceTutorUserInputContract.TOOL) {
            if (!context.userInputEnabled) {
                controller.completeTool(call.callId, nativeToolError("USER_INPUT_UNSUPPORTED", "This app supports voice only. Ask conversationally instead."))
                return@mono
            }
            // Waiting for a person's explicit action has no 15-second tool deadline.
            // The controller holds this exact call without blocking the serial worker.
            val proposalNode = call.arguments?.let { JsonMapperProvider.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(it) }
                ?.get("studyTopicProposal")
            if (proposalNode == null) controller.requestUserInput(call)
            else {
                val args = VoiceTutorUserInputContract.studyTopics(proposalNode)
                val proposal = try {
                    withTimeout(15_000) {
                        if (args == null || !controller.toolCanExecute(call.callId)) null
                        else mcp.prepareStudyTopicUserInput(context.copy(realtimeModelTools = true,
                            initialLessonRevision = controller.toolRevision(call.callId), dialogueBoundary = controller.toolBoundary(call.callId),
                        operationStillCurrent = { controller.toolCanExecute(call.callId) }),
                            args.parentStudyId, args.topics, args.difficultyLevel)
                    }
                } catch (_: TimeoutCancellationException) { null }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                if (proposal == null) controller.completeTool(call.callId, nativeToolError("USER_INPUT_UNAVAILABLE",
                    "The exact parent and proposed subtopics could not be verified; no topic was created."))
                else controller.requestUserInput(call, proposal)
            }
            return@mono
        }
        val reviewedAnswer = controller.reviewedAnswer(call.callId)
        val mutating = call.name in setOf("prepare_voice_study_mutation", "confirm_voice_study_mutation", "select_voice_study", "advance_voice_study", "request_question", "skip_question", "submit_answer")
        val result = try {
            withTimeout(15_000) {
                if (mutating && reviewedAnswer == null && call.name != "submit_answer") {
                    // Only an actual tool execution awaits its source audit rows. No reclassification.
                    withTimeout(5_000) {
                        while (controller.toolCanExecute(call.callId) && !controller.toolTranscriptReady(call.callId)) delay(25)
                    }
                }
                if (!controller.toolCanExecute(call.callId)) nativeToolError("STALE_TURN", "Only this follow-up was superseded by newer speech. Previously accepted answers remain saved and grading continues. Listen to the latest turn; do not describe this as a failed answer submission.")
                else if (call.arguments == null) nativeToolError("INVALID_ARGUMENTS", "Use the documented tool arguments.")
                else if (reviewedAnswer != null) {
                    val executionContext = context.copy(realtimeModelTools = true, initialLessonRevision = reviewedAnswer.lessonRevision)
                    if (call.name == "skip_question") mcp.skipReviewedQuestion(executionContext, reviewedAnswer)
                    else mcp.submitReviewedAnswer(executionContext, reviewedAnswer)
                }
                else if (call.name == "submit_answer") nativeToolError("USER_CONFIRMATION_REQUIRED", "The learner must finish, review and explicitly submit the answer in the app.")
                else if (call.name == VoiceTutorNativeConversationController.END_CALL_TOOL) {
                    controller.requestSpokenEnd()
                    VoiceTutorMcpToolResult("{\"ending\":true}", false)
                } else {
                    val executionContext = context.copy(realtimeModelTools = true,
                        initialLessonRevision = controller.toolRevision(call.callId),
                        dialogueBoundary = controller.toolBoundary(call.callId))
                    var result = mcp.execute(executionContext, call.name, call.arguments)
                    val retryDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
                    // This named error is emitted before any write/lease consumption.
                    // Never replay timeouts, unknown results, or generic mutation errors.
                    while (mutating && result.isError &&
                        JsonMapperProvider.mapper.readTree(result.output).path("error").path("code").asText() == "INPUT_PERSISTENCE_PENDING" &&
                        controller.toolCanExecute(call.callId) && System.nanoTime() < retryDeadline
                    ) {
                        delay(50)
                        result = mcp.execute(executionContext, call.name, call.arguments)
                    }
                    result
                }
            }
        } catch (_: TimeoutCancellationException) {
            nativeToolError("TOOL_TIMEOUT", "Result unconfirmed. Read the saved state before attempting another write; do not ask the learner to repeat an exact phrase.")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { nativeToolError("TOOL_UNAVAILABLE", "Could not confirm the result. Explain briefly without claiming success or asking for magic wording.") }
        controller.completeTool(call.callId, result)
    }.then()
}.then()

internal fun nativeVoiceTutorUserInputRelay(controller: VoiceTutorNativeConversationController,
    context: VoiceTutorWebRtcControlContext, mcp: VoiceTutorMcpToolPort): Mono<Void> =
    controller.userInputActions().concatMap { submission -> mono {
        if (!controller.userInputCanExecute(submission.id)) return@mono
        val result = try {
            withTimeout(15_000) {
                val current = context.copy(realtimeModelTools = true, initialLessonRevision = submission.revision,
                    dialogueBoundary = submission.boundary ?: context.dialogueBoundary,
                    operationStillCurrent = { controller.userInputCanExecute(submission.id) })
                if (submission.curriculum) mcp.submitCurriculumUserInput(current, submission.proposalId,
                    submission.selectedIndices.singleOrNull(), submission.text)
                else mcp.submitStudyTopicUserInput(current, submission.proposalId, submission.selectedIndices)
            }
        } catch (_: TimeoutCancellationException) { nativeToolError("ACTION_FAILED", "Read saved topics before retrying the same selection.") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { nativeToolError("ACTION_FAILED", "The exact selected topics could not be confirmed.") }
        controller.completeUserInputMutation(submission.id, result)
    }.then() }.then()

/** One cancellable event subscription per accepted operation; no synthetic MCP polling turns. */
internal fun nativeVoiceTutorLearningProgressRelay(
    controller: VoiceTutorNativeConversationController,
    context: VoiceTutorWebRtcControlContext,
    mcp: VoiceTutorMcpToolPort,
    timeoutMillis: Long = Duration.between(Instant.now(), context.session.hardEndsAt).toMillis().coerceIn(1, 120_000),
): Mono<Void> = controller.learningPollEvents().switchMap { command ->
    val watch = command.watch ?: return@switchMap Mono.empty<Void>()
    mono {
        try {
            withTimeout(timeoutMillis) {
                mcp.observeLearningProgress(context.copy(realtimeModelTools = true,
                    initialLessonRevision = watch.revision, dialogueBoundary = watch.boundary), watch.progress).collect { result ->
                    if (result.isError) controller.failLearningWatch(watch)
                    else controller.completeLearningPoll(watch, result)
                }
                // An unexpectedly completed stream is not a completed generation.
                controller.failLearningWatch(watch)
            }
        } catch (_: TimeoutCancellationException) {
            controller.failLearningWatch(watch)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { controller.failLearningWatch(watch) }
    }.then()
}.then()

internal fun nativeToolError(code: String, message: String) = VoiceTutorMcpToolResult(
    JsonMapperProvider.mapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), true,
)

private fun waitForNativeDrain(controller: VoiceTutorNativeConversationController, timeout: Duration): Mono<Void> =
    Flux.interval(Duration.ZERO, Duration.ofMillis(50)).filter { controller.isDrained() }.next().then()
        .timeout(timeout).onErrorResume(java.util.concurrent.TimeoutException::class.java) {
            controller.markTranscriptIncomplete()
            Flux.interval(Duration.ZERO, Duration.ofMillis(25)).filter { controller.isIntegritySettled() }
                .next().then().timeout(Duration.ofSeconds(3))
                .onErrorMap { VoiceTutorTranscriptIntegrityException() }
        }

internal class VoiceTutorTranscriptIntegrityException : IllegalStateException("Voice transcript integrity could not be recorded.")
