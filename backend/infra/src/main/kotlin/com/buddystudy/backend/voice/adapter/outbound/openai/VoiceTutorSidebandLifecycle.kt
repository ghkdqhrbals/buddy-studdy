package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

internal fun voiceTutorCallReference(callId: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(callId.toByteArray(StandardCharsets.UTF_8)),
).take(16)

internal enum class VoiceTutorSidebandBranch { RECEIVE, SEND, READY, CANCEL }

internal enum class VoiceTutorSidebandSignal { COMPLETE, ERROR, CANCEL }

internal data class VoiceTutorSidebandTermination(
    val callRef: String,
    val branch: VoiceTutorSidebandBranch,
    val signal: VoiceTutorSidebandSignal,
    val closeCode: Int?,
    val expectedTerminal: Boolean,
    val cancelActiveResponse: Boolean?,
    val elapsedMs: Long,
    val providerEventCounts: Map<String, Long>,
    val clientEventCounts: Map<String, Long>,
    val errorType: String?,
)

/** Records only bounded event-type counters, never event payloads or close reasons. */
internal class VoiceTutorSidebandDiagnostics(
    callId: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onTermination: (VoiceTutorSidebandTermination) -> Unit = ::logSidebandTermination,
) {
    private val callRef = voiceTutorCallReference(callId)
    private val startedAt = nanoTime()
    private val recorded = AtomicBoolean()
    private var closeCode: Int? = null
    private var localTerminal: VoiceTutorRelayTermination? = null
    private val providerEventCounts = linkedMapOf<String, Long>()
    private val clientEventCounts = linkedMapOf<String, Long>()

    @Synchronized
    fun observeProviderEvent(raw: String) {
        countEvent(raw, PROVIDER_EVENT_TYPES, providerEventCounts)
    }

    @Synchronized
    fun observeClientEvent(raw: String) {
        countEvent(raw, CLIENT_EVENT_TYPES, clientEventCounts)
    }

    @Synchronized
    fun markLocalTerminal(termination: VoiceTutorRelayTermination) {
        if (localTerminal == null) localTerminal = termination
    }

    @Synchronized
    internal fun observeCloseStatus(status: CloseStatus) {
        if (closeCode == null) closeCode = status.code
    }

    @Synchronized
    internal fun snapshot(
        branch: VoiceTutorSidebandBranch,
        signal: VoiceTutorSidebandSignal,
        error: Throwable? = null,
    ): VoiceTutorSidebandTermination = VoiceTutorSidebandTermination(
        callRef = callRef,
        branch = branch,
        signal = signal,
        closeCode = closeCode,
        expectedTerminal = localTerminal != null,
        cancelActiveResponse = localTerminal?.cancelActiveResponse,
        elapsedMs = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0),
        providerEventCounts = providerEventCounts.toMap(),
        clientEventCounts = clientEventCounts.toMap(),
        errorType = error?.javaClass?.simpleName,
    )

    internal fun record(termination: VoiceTutorSidebandTermination) {
        if (recorded.compareAndSet(false, true)) onTermination(termination)
    }

    private fun countEvent(raw: String, allowed: Set<String>, counts: MutableMap<String, Long>) {
        val type = runCatching { JsonMapperProvider.mapper.readTree(raw).path("type").asText() }
            .getOrNull()
            ?.takeIf(allowed::contains)
            ?: "other"
        val count = counts[type] ?: 0
        if (count < Long.MAX_VALUE) counts[type] = count + 1
    }

    private companion object {
        val PROVIDER_EVENT_TYPES = setOf(
            "session.created", "session.updated",
            "response.created", "response.done",
            "response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done",
            "response.output_audio.delta", "response.output_audio.done",
            "response.output_audio_transcript.delta", "response.output_audio_transcript.done",
            "output_audio_buffer.started", "output_audio_buffer.stopped", "output_audio_buffer.cleared",
            "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped", "input_audio_buffer.committed",
            "conversation.item.created", "conversation.item.added", "conversation.item.done",
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed",
            "rate_limits.updated", "error",
        )
        val CLIENT_EVENT_TYPES = setOf(
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT,
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT,
            "buddystudy.voice.heartbeat", "buddystudy.voice.session.end",
        )
    }
}

internal class VoiceTutorUnexpectedSidebandCloseException(
    val branch: VoiceTutorSidebandBranch,
    val closeCode: Int?,
) : RuntimeException("Voice Tutor sideband closed before a local end request.")

/**
 * A remote Close frame (even code 1000) and outbound-only completion are not
 * lesson completion. Only a local terminal request permits a successful relay.
 */
internal fun webRtcSidebandLifecycle(
    receive: Mono<Void>,
    send: Mono<Void>,
    ready: Mono<Void>,
    diagnostics: VoiceTutorSidebandDiagnostics,
    closeStatus: Mono<CloseStatus> = Mono.empty(),
): Mono<Void> {
    // Observe status before subscribing either relay direction. Snapshot it at
    // the winning signal, before cancellation of the losing receive can create
    // a local 1006. Never wait for a close handshake or subscribe receive twice.
    val observeClose = closeStatus
        .doOnNext(diagnostics::observeCloseStatus)
        .onErrorComplete()
        .then(Mono.never<SidebandOutcome>())
    fun outcome(source: Mono<Void>, branch: VoiceTutorSidebandBranch): Mono<SidebandOutcome> = source
        .materialize()
        .map { signal ->
            SidebandOutcome(
                termination = diagnostics.snapshot(
                    branch,
                    if (signal.isOnError) VoiceTutorSidebandSignal.ERROR else VoiceTutorSidebandSignal.COMPLETE,
                    signal.throwable,
                ),
                error = signal.throwable,
            )
        }

    // A successful readiness callback must leave both provider directions alive.
    val readyAfterRelaySubscription = ready.then(Mono.never<SidebandOutcome>())
        .onErrorResume { error ->
            Mono.just(
                SidebandOutcome(
                    diagnostics.snapshot(VoiceTutorSidebandBranch.READY, VoiceTutorSidebandSignal.ERROR, error),
                    error,
                ),
            )
        }
    return Mono.firstWithSignal(
        observeClose,
        outcome(receive, VoiceTutorSidebandBranch.RECEIVE),
        outcome(send, VoiceTutorSidebandBranch.SEND),
        readyAfterRelaySubscription,
    ).flatMap { outcome ->
        val termination = outcome.termination
        diagnostics.record(termination)
        when {
            outcome.error != null -> Mono.error<Void>(outcome.error)
            !termination.expectedTerminal -> Mono.error<Void>(
                VoiceTutorUnexpectedSidebandCloseException(termination.branch, termination.closeCode),
            )
            else -> Mono.empty<Void>()
        }
    }.doOnCancel {
        diagnostics.record(diagnostics.snapshot(VoiceTutorSidebandBranch.CANCEL, VoiceTutorSidebandSignal.CANCEL))
    }
}

private data class SidebandOutcome(
    val termination: VoiceTutorSidebandTermination,
    val error: Throwable?,
)

private val sidebandLogger = LoggerFactory.getLogger(
    "com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorSidebandLifecycle",
)

private fun logSidebandTermination(termination: VoiceTutorSidebandTermination) {
    val format = "voice_tutor_sideband_terminated callRef={} branch={} signal={} closeCode={} expectedTerminal={} " +
        "cancelActiveResponse={} elapsedMs={} providerEventCounts={} clientEventCounts={} errorType={}"
    val fields = arrayOf(
        termination.callRef,
        termination.branch,
        termination.signal,
        termination.closeCode ?: "none",
        termination.expectedTerminal,
        termination.cancelActiveResponse ?: "none",
        termination.elapsedMs,
        JsonMapperProvider.mapper.writeValueAsString(termination.providerEventCounts),
        JsonMapperProvider.mapper.writeValueAsString(termination.clientEventCounts),
        termination.errorType ?: "none",
    )
    if (termination.signal == VoiceTutorSidebandSignal.ERROR ||
        (!termination.expectedTerminal && termination.signal == VoiceTutorSidebandSignal.COMPLETE)
    ) {
        sidebandLogger.warn(format, *fields)
    } else {
        sidebandLogger.info(format, *fields)
    }
}
