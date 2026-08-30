package com.buddystudy.backend.voice.adapter.inbound.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec

@Configuration
class VoiceTutorWebSocketConfig {
    @Bean
    fun voiceTutorWebSocketMapping(
        handler: VoiceTutorWebSocketHandler,
        controlHandler: VoiceTutorControlWebSocketHandler,
    ): HandlerMapping =
        SimpleUrlHandlerMapping(
            mapOf(
                "/api/v1/voice-tutor/sessions/*/stream" to handler,
                "/api/v1/voice-tutor/sessions/*/control" to controlHandler,
            ),
            Ordered.HIGHEST_PRECEDENCE,
        )

    @Bean
    fun voiceTutorWebSocketHandlerAdapter(): WebSocketHandlerAdapter {
        val upgradeStrategy = ReactorNettyRequestUpgradeStrategy {
            WebsocketServerSpec.builder()
                .maxFramePayloadLength(VOICE_TUTOR_MAX_WEBSOCKET_FRAME_BYTES)
        }
        return WebSocketHandlerAdapter(HandshakeWebSocketService(upgradeStrategy))
    }
}

internal const val VOICE_TUTOR_MAX_WEBSOCKET_FRAME_BYTES = 65_536
