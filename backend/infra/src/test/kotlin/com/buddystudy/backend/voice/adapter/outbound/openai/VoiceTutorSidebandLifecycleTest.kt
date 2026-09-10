package com.buddystudy.backend.voice.adapter.outbound.openai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.Connection
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import reactor.test.StepVerifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real WebSocket transport, but only ephemeral IPv4 loopback: no provider, Docker, or database. */
class VoiceTutorSidebandLifecycleTest {
    @TestFactory
    fun `a remote close frame is a failed relay even when its websocket code is normal`() =
        listOf(PeerAction.CLOSE_NORMAL, PeerAction.CLOSE_ERROR).map { action ->
            dynamicTest("remote close ${action.closeCode}") {
                withLoopback(action) { uri, releasePeer ->
                    val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
                    val diagnostics = diagnostics(records)
                    val relay = client().execute(uri) { session ->
                        webRtcSidebandLifecycle(
                            receive = session.receive().then(),
                            send = session.send(Flux.never<WebSocketMessage>()),
                            ready = Mono.fromRunnable<Void> { releasePeer() },
                            diagnostics = diagnostics,
                            closeStatus = session.closeStatus(),
                        )
                    }

                    StepVerifier.create(relay)
                        .expectErrorSatisfies { failure ->
                            assertThat(failure).isInstanceOf(VoiceTutorUnexpectedSidebandCloseException::class.java)
                            assertThat(failure.message).doesNotContain(PRIVATE_CLOSE_REASON, CALL_ID)
                        }
                        .verify(TIMEOUT)

                    val record = onlyRecord(records)
                    assertThat(record.branch).isIn(VoiceTutorSidebandBranch.RECEIVE, VoiceTutorSidebandBranch.SEND)
                    assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.COMPLETE)
                    assertThat(record.closeCode).isEqualTo(action.closeCode)
                    assertThat(record.expectedTerminal).isFalse()
                    assertThat(record.cancelActiveResponse).isNull()
                    assertThat(record.toString()).doesNotContain(PRIVATE_CLOSE_REASON, CALL_ID)
                }
            }
        }

    @Test
    fun `short tcp eof is not reported as a successful voice lesson`() {
        withLoopback(PeerAction.TCP_EOF) { uri, releasePeer ->
            val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
            val diagnostics = diagnostics(records)
            val relay = client().execute(uri) { session ->
                webRtcSidebandLifecycle(
                    receive = session.receive().then(),
                    send = session.send(Flux.never<WebSocketMessage>()),
                    ready = Mono.fromRunnable<Void> { releasePeer() },
                    diagnostics = diagnostics,
                    closeStatus = session.closeStatus(),
                )
            }

            val observedFailure = AtomicReference<Throwable>()
            StepVerifier.create(relay)
                .expectErrorSatisfies(observedFailure::set)
                .verify(TIMEOUT)

            val record = onlyRecord(records)
            assertThat(record.expectedTerminal).isFalse()
            assertThat(record.branch).isIn(VoiceTutorSidebandBranch.RECEIVE, VoiceTutorSidebandBranch.SEND)
            // Depending on which Netty terminal signal wins, EOF has no close
            // status or an already-observed synthetic abnormal-closure status.
            assertThat(record.closeCode == null || record.closeCode == 1006)
                .withFailMessage("Unexpected raw TCP EOF termination: %s", record)
                .isTrue()
            if (record.signal == VoiceTutorSidebandSignal.COMPLETE) {
                assertThat(observedFailure.get()).isInstanceOf(VoiceTutorUnexpectedSidebandCloseException::class.java)
            } else {
                assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.ERROR)
                assertThat(record.errorType).isEqualTo(observedFailure.get().javaClass.simpleName)
            }
        }
    }

    @Test
    fun `send completion fails while the real loopback receive side is still open`() {
        withLoopback(PeerAction.KEEP_OPEN) { uri, _ ->
            val receiveSubscribed = AtomicBoolean()
            val receiveCompleted = AtomicBoolean()
            val receiveWasOpenAtTermination = AtomicBoolean()
            val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
            val diagnostics = VoiceTutorSidebandDiagnostics(CALL_ID, onTermination = { record ->
                receiveWasOpenAtTermination.set(receiveSubscribed.get() && !receiveCompleted.get())
                records += record
            })
            val relay = client().execute(uri) { session ->
                webRtcSidebandLifecycle(
                    receive = session.receive()
                        .doOnSubscribe { receiveSubscribed.set(true) }
                        .doOnComplete { receiveCompleted.set(true) }
                        .then(),
                    send = session.send(Flux.empty<WebSocketMessage>()),
                    ready = Mono.empty(),
                    diagnostics = diagnostics,
                    closeStatus = session.closeStatus(),
                )
            }

            StepVerifier.create(relay)
                .expectError(VoiceTutorUnexpectedSidebandCloseException::class.java)
                .verify(TIMEOUT)

            val record = onlyRecord(records)
            assertThat(record.branch).isEqualTo(VoiceTutorSidebandBranch.SEND)
            assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.COMPLETE)
            assertThat(record.closeCode).isNull()
            assertThat(record.expectedTerminal).isFalse()
            assertThat(receiveWasOpenAtTermination).isTrue()
        }
    }

    @Test
    fun `a local terminal signal makes the ensuing peer close expected`() {
        withLoopback(PeerAction.CLOSE_NORMAL) { uri, releasePeer ->
            val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
            val diagnostics = diagnostics(records)
            val relay = client().execute(uri) { session ->
                webRtcSidebandLifecycle(
                    receive = session.receive().then(),
                    send = session.send(Flux.never<WebSocketMessage>()),
                    ready = Mono.fromRunnable<Void> {
                        diagnostics.markLocalTerminal(VoiceTutorRelayTermination(cancelActiveResponse = true))
                        releasePeer()
                    },
                    diagnostics = diagnostics,
                    closeStatus = session.closeStatus(),
                )
            }

            StepVerifier.create(relay).expectComplete().verify(TIMEOUT)

            val record = onlyRecord(records)
            assertThat(record.expectedTerminal).isTrue()
            assertThat(record.cancelActiveResponse).isTrue()
            assertThat(record.closeCode).isEqualTo(1000)
            assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.COMPLETE)
        }
    }

    @Test
    fun `a graceful local terminal permits send completion without cancelling a response`() {
        val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
        val diagnostics = diagnostics(records)
        val relay = webRtcSidebandLifecycle(
            receive = Mono.never(),
            send = Mono.fromRunnable<Void> {
                diagnostics.markLocalTerminal(VoiceTutorRelayTermination(cancelActiveResponse = false))
            },
            ready = Mono.never(),
            diagnostics = diagnostics,
        )

        StepVerifier.create(relay).expectComplete().verify(TIMEOUT)

        val record = onlyRecord(records)
        assertThat(record.branch).isEqualTo(VoiceTutorSidebandBranch.SEND)
        assertThat(record.expectedTerminal).isTrue()
        assertThat(record.cancelActiveResponse).isFalse()
    }

    @Test
    fun `successful readiness stays live and downstream cancellation has only one terminal record`() {
        val subscriptions = mutableListOf<String>()
        val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
        val diagnostics = diagnostics(records)
        val relay = webRtcSidebandLifecycle(
            receive = Mono.never<Void>().doOnSubscribe { subscriptions += "receive" },
            send = Mono.never<Void>().doOnSubscribe { subscriptions += "send" },
            ready = Mono.fromRunnable<Void> {
                assertThat(subscriptions).containsExactly("close-status", "receive", "send")
                subscriptions += "ready"
            },
            diagnostics = diagnostics,
            closeStatus = Mono.never<CloseStatus>().doOnSubscribe { subscriptions += "close-status" },
        )

        StepVerifier.create(relay)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(25))
            .then { assertThat(records).isEmpty() }
            .thenCancel()
            .verify(TIMEOUT)

        assertThat(subscriptions).containsExactly("close-status", "receive", "send", "ready")
        val record = onlyRecord(records)
        assertThat(record.branch).isEqualTo(VoiceTutorSidebandBranch.CANCEL)
        assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.CANCEL)
        assertThat(record.expectedTerminal).isFalse()
    }

    @Test
    fun `winning send snapshot cannot be overwritten by a loser cancellation close status`() {
        val closeStatus = Sinks.one<CloseStatus>()
        val cancelledReceive = AtomicBoolean()
        val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
        val relay = webRtcSidebandLifecycle(
            receive = Mono.never<Void>().doOnCancel {
                cancelledReceive.set(true)
                closeStatus.tryEmitValue(CloseStatus(1006, PRIVATE_CLOSE_REASON))
            },
            send = Mono.empty(),
            ready = Mono.never(),
            diagnostics = diagnostics(records),
            closeStatus = closeStatus.asMono(),
        )

        StepVerifier.create(relay)
            .expectError(VoiceTutorUnexpectedSidebandCloseException::class.java)
            .verify(TIMEOUT)

        assertThat(cancelledReceive).isTrue()
        val record = onlyRecord(records)
        assertThat(record.branch).isEqualTo(VoiceTutorSidebandBranch.SEND)
        assertThat(record.closeCode).isNull()
        assertThat(record.toString()).doesNotContain(PRIVATE_CLOSE_REASON)
    }

    @TestFactory
    fun `branch errors propagate unchanged without leaking their messages into diagnostics`() =
        listOf(VoiceTutorSidebandBranch.RECEIVE, VoiceTutorSidebandBranch.SEND, VoiceTutorSidebandBranch.READY).map { branch ->
            dynamicTest(branch.name) {
                val original = IllegalStateException(PRIVATE_ERROR_MESSAGE)
                val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
                fun branchSource(candidate: VoiceTutorSidebandBranch): Mono<Void> =
                    if (candidate == branch) Mono.error(original) else Mono.never()

                StepVerifier.create(
                    webRtcSidebandLifecycle(
                        receive = branchSource(VoiceTutorSidebandBranch.RECEIVE),
                        send = branchSource(VoiceTutorSidebandBranch.SEND),
                        ready = branchSource(VoiceTutorSidebandBranch.READY),
                        diagnostics = diagnostics(records),
                    ),
                ).expectErrorSatisfies { assertThat(it).isSameAs(original) }.verify(TIMEOUT)

                val record = onlyRecord(records)
                assertThat(record.branch).isEqualTo(branch)
                assertThat(record.signal).isEqualTo(VoiceTutorSidebandSignal.ERROR)
                assertThat(record.errorType).isEqualTo(IllegalStateException::class.java.simpleName)
                assertThat(record.toString()).doesNotContain(PRIVATE_ERROR_MESSAGE)
            }
        }

    @Test
    fun `diagnostics retain bounded event types and a hashed reference rather than payloads`() {
        val clock = AtomicLong(1_000_000)
        val records = CopyOnWriteArrayList<VoiceTutorSidebandTermination>()
        val diagnostics = VoiceTutorSidebandDiagnostics(CALL_ID, nanoTime = clock::get, onTermination = { records += it })
        populateSensitiveEvents(diagnostics)
        clock.addAndGet(42_000_000)

        StepVerifier.create(
            webRtcSidebandLifecycle(
                receive = Mono.empty(), send = Mono.never(), ready = Mono.never(), diagnostics = diagnostics,
            ),
        ).expectError(VoiceTutorUnexpectedSidebandCloseException::class.java).verify(TIMEOUT)

        val record = onlyRecord(records)
        val independentlyHashedReference = MessageDigest.getInstance("SHA-256")
            .digest(CALL_ID.toByteArray(StandardCharsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertThat(voiceTutorCallReference(CALL_ID)).isEqualTo(independentlyHashedReference)
        assertThat(record.callRef).isEqualTo(independentlyHashedReference).matches("[0-9a-f]{16}")
        assertThat(record.elapsedMs).isEqualTo(42L)
        assertThat(record.providerEventCounts).isEqualTo(
            mapOf("session.created" to 2L, "response.output_audio.delta" to 1L, "other" to UNKNOWN_EVENT_COUNT.toLong()),
        )
        assertThat(record.clientEventCounts).isEqualTo(
            mapOf("buddystudy.voice.playout.drained" to 1L, "other" to UNKNOWN_EVENT_COUNT.toLong()),
        )
        assertThat(record.toString()).doesNotContain(CALL_ID, PRIVATE_PAYLOAD, PRIVATE_EVENT_TYPE)
        diagnostics.observeProviderEvent("""{"type":"session.created"}""")
        assertThat(record.providerEventCounts["session.created"]).isEqualTo(2L)
    }

    @Test
    fun `manual speech and provider truncation diagnostics keep only event counts`() {
        val diagnostics = VoiceTutorSidebandDiagnostics(CALL_ID)
        diagnostics.observeClientEvent("""{"type":"buddystudy.voice.input.speech.started","sequence":123,"private":"$PRIVATE_PAYLOAD"}""")
        diagnostics.observeClientEvent("""{"type":"buddystudy.voice.input.speech.stopped","sequence":123,"private":"$PRIVATE_PAYLOAD"}""")
        diagnostics.observeProviderEvent("""{"type":"conversation.item.truncated","item_id":"$PRIVATE_PAYLOAD"}""")
        val snapshot = diagnostics.snapshot(VoiceTutorSidebandBranch.RECEIVE, VoiceTutorSidebandSignal.COMPLETE)
        assertThat(snapshot.clientEventCounts).isEqualTo(mapOf(
            "buddystudy.voice.input.speech.started" to 1L, "buddystudy.voice.input.speech.stopped" to 1L,
        ))
        assertThat(snapshot.providerEventCounts).isEqualTo(mapOf("conversation.item.truncated" to 1L))
        assertThat(snapshot.toString()).doesNotContain(PRIVATE_PAYLOAD, CALL_ID, "sequence=")
    }

    @ParameterizedTest
    @EnumSource(value = VoiceTutorProviderTurnFailureKind::class, names = ["PROVIDER_ERROR", "RESPONSE_MISSING_AUDIO", "RESPONSE_UNEXPECTED_TOOL"])
    internal fun `provider turn failure diagnostics keep bounded subtype code and hashed correlation only`(kind: VoiceTutorProviderTurnFailureKind) {
        val logger = LoggerFactory.getLogger(
            "com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorSidebandLifecycle",
        ) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val rawEventId = "buddystudy-internal-duplex-turn-response-private"
            val diagnostics = VoiceTutorSidebandDiagnostics(CALL_ID)
            diagnostics.observeProviderTurnFailure(
                VoiceTutorProviderTurnFailureDiagnostic(
                    kind = kind,
                    providerErrorType = "server_error",
                    providerErrorCode = "rate_limit_exceeded",
                    eventCorrelation = VoiceTutorProviderEventCorrelation.ACTIVE_RESPONSE,
                    causedEventRef = providerEventReference(rawEventId),
                    attempt = 1,
                    action = VoiceTutorProviderTurnFailureAction.RETRY_SCHEDULED,
                ),
            )

            val snapshot = diagnostics.snapshot(VoiceTutorSidebandBranch.RECEIVE, VoiceTutorSidebandSignal.COMPLETE)
            assertThat(snapshot.providerTurnFailureCounts).isEqualTo(
                mapOf("${kind.diagnosticValue}:server_error:rate_limit_exceeded:active-response:retry" to 1L),
            )
            assertThat(snapshot.toString()).doesNotContain(rawEventId, PRIVATE_PAYLOAD, CALL_ID)
            assertThat(appender.list).hasSize(1)
            assertThat(appender.list.single().formattedMessage)
                .contains(kind.diagnosticValue, providerEventReference(rawEventId), "server_error", "rate_limit_exceeded", "attempt=1")
                .doesNotContain(rawEventId, PRIVATE_PAYLOAD, CALL_ID)
            assertThat(appender.list.single().throwableProxy).isNull()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `default diagnostics logger excludes raw call payload close reason and exception`() {
        val logger = LoggerFactory.getLogger(
            "com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorSidebandLifecycle",
        ) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val diagnostics = VoiceTutorSidebandDiagnostics(CALL_ID)
            populateSensitiveEvents(diagnostics)
            val original = IllegalStateException(PRIVATE_ERROR_MESSAGE)

            StepVerifier.create(
                webRtcSidebandLifecycle(
                    receive = Mono.never(),
                    send = Mono.never(),
                    ready = Mono.error(original),
                    diagnostics = diagnostics,
                    closeStatus = Mono.just(CloseStatus(1011, PRIVATE_CLOSE_REASON)),
                ),
            ).expectErrorSatisfies { assertThat(it).isSameAs(original) }.verify(TIMEOUT)

            assertThat(appender.list).hasSize(1)
            val event = appender.list.single()
            val loggedValues = listOf(
                event.formattedMessage,
                event.argumentArray?.joinToString().orEmpty(),
                event.keyValuePairs?.joinToString().orEmpty(),
            ).joinToString(" ")
            assertThat(loggedValues).contains(voiceTutorCallReference(CALL_ID))
                .doesNotContain(CALL_ID, PRIVATE_PAYLOAD, PRIVATE_EVENT_TYPE, PRIVATE_CLOSE_REASON, PRIVATE_ERROR_MESSAGE)
            assertThat(event.throwableProxy).isNull()
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    private fun populateSensitiveEvents(diagnostics: VoiceTutorSidebandDiagnostics) {
        repeat(2) {
            diagnostics.observeProviderEvent("""{"type":"session.created","transcript":"$PRIVATE_PAYLOAD"}""")
        }
        diagnostics.observeProviderEvent("""{"type":"response.output_audio.delta","delta":"$PRIVATE_PAYLOAD"}""")
        diagnostics.observeClientEvent("""{"type":"buddystudy.voice.playout.drained","responseId":"$PRIVATE_PAYLOAD"}""")
        repeat(UNKNOWN_EVENT_COUNT) { index ->
            diagnostics.observeProviderEvent("""{"type":"$PRIVATE_EVENT_TYPE-$index","text":"$PRIVATE_PAYLOAD"}""")
            diagnostics.observeClientEvent("""{"type":"$PRIVATE_EVENT_TYPE-$index","text":"$PRIVATE_PAYLOAD"}""")
        }
    }

    private fun diagnostics(records: MutableList<VoiceTutorSidebandTermination>) =
        VoiceTutorSidebandDiagnostics(CALL_ID, onTermination = { records += it })

    private fun onlyRecord(records: List<VoiceTutorSidebandTermination>): VoiceTutorSidebandTermination {
        assertThat(records).hasSize(1)
        return records.single().also {
            assertThat(it.callRef).matches("[0-9a-f]{16}")
            assertThat(it.elapsedMs).isGreaterThanOrEqualTo(0L)
        }
    }

    private fun client() = ReactorNettyWebSocketClient(HttpClient.newConnection().responseTimeout(TIMEOUT))

    private fun withLoopback(action: PeerAction, assertions: (URI, () -> Unit) -> Unit) {
        val closePeer = Sinks.one<Void>()
        val connections = CopyOnWriteArrayList<Connection>()
        val server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .doOnConnection { connections += it }
            .route { routes ->
                routes.ws("/sideband") { inbound, outbound ->
                    when (action) {
                        PeerAction.CLOSE_NORMAL, PeerAction.CLOSE_ERROR -> closePeer.asMono()
                            .then(Mono.defer { outbound.sendClose(requireNotNull(action.closeCode), PRIVATE_CLOSE_REASON) })
                        PeerAction.TCP_EOF -> closePeer.asMono().then(Mono.fromRunnable<Void> {
                            // Connection.dispose() first discards WebSocket input,
                            // which sends an empty Close frame. Close the raw TCP
                            // channel instead, without completing the handler.
                            inbound.withConnection { it.channel().close() }
                        }).then(Mono.never<Void>())
                        PeerAction.KEEP_OPEN -> Mono.never<Void>()
                    }
                }
            }
            .bindNow(TIMEOUT)
        try {
            assertions(URI.create("ws://127.0.0.1:${server.port()}/sideband")) {
                assertThat(closePeer.tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK)
            }
        } finally {
            connections.forEach(Connection::dispose)
            server.disposeNow(TIMEOUT)
        }
    }

    private enum class PeerAction(val closeCode: Int?) {
        CLOSE_NORMAL(1000), CLOSE_ERROR(1011), TCP_EOF(null), KEEP_OPEN(null),
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
        const val CALL_ID = "rtc_private-call-reference-fixture"
        const val PRIVATE_PAYLOAD = "private-audio-transcript-payload-fixture"
        const val PRIVATE_EVENT_TYPE = "private-unrecognized-event-name-fixture"
        const val PRIVATE_CLOSE_REASON = "private-provider-close-reason-fixture"
        const val PRIVATE_ERROR_MESSAGE = "private-provider-error-message-fixture"
        const val UNKNOWN_EVENT_COUNT = 128
    }
}
