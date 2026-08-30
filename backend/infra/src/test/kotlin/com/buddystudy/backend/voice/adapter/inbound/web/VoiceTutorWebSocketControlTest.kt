package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.adapter.inbound.web.ErrorHandler
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticMessageSource
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration

class VoiceTutorWebSocketControlTest {
    @Test
    fun `websocket requires the negotiated Voice Tutor protocol`() {
        assertThat(
            voiceTutorSubProtocolNegotiated(VoiceTutorCreateSessionResponse.WEBSOCKET_PROTOCOL),
        ).isTrue()
        assertThat(voiceTutorSubProtocolNegotiated(null)).isFalse()
        assertThat(voiceTutorSubProtocolNegotiated("unexpected.protocol")).isFalse()
    }

    @Test
    fun `websocket transport caps frames at 64 KiB`() {
        val adapter = VoiceTutorWebSocketConfig().voiceTutorWebSocketHandlerAdapter()
        val service = adapter.webSocketService as HandshakeWebSocketService
        val strategy = service.upgradeStrategy as ReactorNettyRequestUpgradeStrategy

        assertThat(strategy.websocketServerSpec.maxFramePayloadLength())
            .isEqualTo(VOICE_TUTOR_MAX_WEBSOCKET_FRAME_BYTES)
    }

    @Test
    fun `control handshake rejects proxy-stripped upgrade headers before the relay`() {
        val adapter = VoiceTutorWebSocketConfig().voiceTutorWebSocketHandlerAdapter()
        val errorHandler = ErrorHandler(
            ApiErrorResponseFactory(StaticMessageSource()),
            ApiLoggingPolicy("compact"),
        )
        val handler = WebSocketHandler { error("Invalid handshake must not open the relay.") }

        for (missingHeader in listOf(HttpHeaders.UPGRADE, HttpHeaders.CONNECTION)) {
            val request = MockServerHttpRequest.get(
                "/api/v1/voice-tutor/sessions/00000000-0000-0000-0000-000000000001/control",
            )
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Sec-WebSocket-Protocol", VoiceTutorControlWebSocketHandler.CONTROL_PROTOCOL)
            if (missingHeader == HttpHeaders.CONNECTION) {
                request.header(HttpHeaders.UPGRADE, "websocket")
            }
            val exchange = MockServerWebExchange.from(request.build())

            StepVerifier.create(adapter.handle(exchange, handler))
                .expectErrorSatisfies { error ->
                    assertThat(error).isInstanceOf(ServerWebInputException::class.java)
                    assertThat(error).hasMessageContaining("Invalid '$missingHeader' header")
                    val response = errorHandler.invalidInput(error as ServerWebInputException, exchange)
                    assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                    assertThat(response.body?.error?.errorCode).isEqualTo(ApiErrorCode.VALIDATION_ERROR.name)
                }
                .verify(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `valid control handshake reaches upgrade with the negotiated protocol`() {
        var upgradeReached = false
        val protocol = VoiceTutorControlWebSocketHandler.CONTROL_PROTOCOL
        val service = HandshakeWebSocketService { _, _, selectedProtocol, handshake ->
            upgradeReached = true
            assertThat(selectedProtocol).isEqualTo(protocol)
            assertThat(handshake.get().subProtocol).isEqualTo(protocol)
            Mono.empty()
        }
        val handler = object : WebSocketHandler {
            override fun getSubProtocols() = listOf(protocol)
            override fun handle(session: org.springframework.web.reactive.socket.WebSocketSession): Mono<Void> =
                error("The upgrade strategy owns the session.")
        }
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(
                "/api/v1/voice-tutor/sessions/00000000-0000-0000-0000-000000000001/control",
            )
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "upgrade")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Sec-WebSocket-Protocol", protocol)
                .build(),
        )

        service.handleRequest(exchange, handler).block(Duration.ofSeconds(1))

        assertThat(upgradeReached).isTrue()
    }

    @Test
    fun `client relay rejects non text websocket frames`() {
        val buffer = DefaultDataBufferFactory.sharedInstance.wrap(byteArrayOf(1, 2, 3))
        val binary = WebSocketMessage(WebSocketMessage.Type.BINARY, buffer)

        assertThatThrownBy { voiceTutorClientTextPayload(binary) }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)
            .hasMessage("Voice Tutor only accepts text WebSocket frames.")
    }

    @Test
    fun `server control heartbeat remains authoritative without client heartbeats`() {
        runBlocking {
            var authorizationChecks = 0
            var serverHeartbeats = 0

            repeat(3) {
                val snapshot = pollVoiceTutorRelayControl(
                    authorized = {
                        authorizationChecks += 1
                        true
                    },
                    heartbeat = {
                        serverHeartbeats += 1
                        VoiceTutorSessionStatus.ACTIVE
                    },
                )
                assertThat(snapshot).isEqualTo(
                    VoiceTutorRelayControlSnapshot(true, VoiceTutorSessionStatus.ACTIVE),
                )
            }

            assertThat(authorizationChecks).isEqualTo(3)
            assertThat(serverHeartbeats).isEqualTo(3)
        }
    }

    @Test
    fun `revoked relay is stopped without refreshing its liveness lease`() {
        runBlocking {
            var serverHeartbeats = 0

            val snapshot = pollVoiceTutorRelayControl(
                authorized = { false },
                heartbeat = {
                    serverHeartbeats += 1
                    VoiceTutorSessionStatus.ACTIVE
                },
            )

            assertThat(snapshot).isEqualTo(VoiceTutorRelayControlSnapshot(false, null))
            assertThat(serverHeartbeats).isZero()
        }
    }

    @Test
    fun `accepted audio is independently flushed once per second`() {
        val recorded = mutableListOf<Long>()
        val stop = Sinks.one<Boolean>()

        StepVerifier.withVirtualTime {
            val accounting = VoiceTutorAcceptedAudioAccounting { recorded += it }
            accounting.accept(24_000)
            accounting.periodicUntil(stop.asMono())
        }
            .thenAwait(Duration.ofSeconds(1))
            .then { stop.tryEmitValue(true) }
            .verifyComplete()

        assertThat(recorded).containsExactly(24_000)
    }

    @Test
    fun `final audio flush is bounded into durable batches`() {
        val recorded = mutableListOf<Long>()
        val accounting = VoiceTutorAcceptedAudioAccounting { recorded += it }
        accounting.accept(500_000)

        accounting.flush().block(Duration.ofSeconds(1))

        assertThat(recorded).containsExactly(480_000, 20_000)
        assertThat(accounting.pendingBytes()).isZero()
    }

    @Test
    fun `failed durable audio flush restores bytes and fails closed`() {
        var shouldFail = true
        val recorded = mutableListOf<Long>()
        val accounting = VoiceTutorAcceptedAudioAccounting {
            if (shouldFail) error("database unavailable")
            recorded += it
        }
        accounting.accept(12_000)

        assertThatThrownBy { accounting.flush().block(Duration.ofSeconds(1)) }
            .hasMessageContaining("database unavailable")
        assertThat(accounting.pendingBytes()).isEqualTo(12_000)

        shouldFail = false
        accounting.flush().block(Duration.ofSeconds(1))
        assertThat(recorded).containsExactly(12_000)
        assertThat(accounting.pendingBytes()).isZero()
    }
}
