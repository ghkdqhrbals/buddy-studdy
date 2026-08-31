package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorVoicePreviewUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

class VoiceTutorVoicePreviewControllerTest {
    private val principal = Principal(7, "device-7", 70, anonymous = false)
    private val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, emptyList())

    @Test
    fun `authenticated preview returns bounded mp3 with explicit noncacheable headers`() {
        val useCase = CapturingPreviewUseCase()

        client(useCase).get()
            .uri("/api/v1/voice-tutor/voices/cedar/preview?language=ja")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType("audio/mpeg")
            .expectHeader().contentLength(3)
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectBody(ByteArray::class.java).isEqualTo(byteArrayOf(0x49, 0x44, 0x33))

        assertThat(useCase.calls).containsExactly(PreviewCall(principal, "cedar", "ja"))
    }

    @Test
    fun `omitted language defaults to Korean and arbitrary text query is never part of the use case`() {
        val useCase = CapturingPreviewUseCase()

        client(useCase).get()
            .uri { builder ->
                builder.path("/api/v1/voice-tutor/voices/default/preview")
                    .queryParam("text", "caller controlled text must be ignored")
                    .build()
            }
            .exchange()
            .expectStatus().isOk

        assertThat(useCase.calls).containsExactly(PreviewCall(principal, "default", "ko"))
    }

    @Test
    fun `non GET method cannot invoke preview generation`() {
        val useCase = CapturingPreviewUseCase()

        client(useCase).post()
            .uri("/api/v1/voice-tutor/voices/marin/preview?language=en")
            .exchange()
            .expectStatus().isEqualTo(405)

        assertThat(useCase.calls).isEmpty()
    }

    private fun client(useCase: VoiceTutorVoicePreviewUseCase): WebTestClient =
        WebTestClient.bindToController(
            VoiceTutorVoicePreviewController(VoiceTutorVoicePreviewWebAdapter(useCase)),
        ).webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
            },
        ).build()

    private class CapturingPreviewUseCase : VoiceTutorVoicePreviewUseCase {
        val calls = mutableListOf<PreviewCall>()

        override suspend fun preview(
            principal: Principal,
            voice: String,
            language: String,
        ): VoiceTutorVoicePreviewAudio {
            calls += PreviewCall(principal, voice, language)
            return VoiceTutorVoicePreviewAudio(byteArrayOf(0x49, 0x44, 0x33))
        }
    }

    private data class PreviewCall(
        val principal: Principal,
        val voice: String,
        val language: String,
    )
}
