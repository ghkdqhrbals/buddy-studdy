package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
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
