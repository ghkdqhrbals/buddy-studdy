package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.time.Instant

class VoiceTutorCreateSessionWebTest {
    private val principal = Principal(7, "device-7", 70, anonymous = false)
    private val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, emptyList())
    private val now = Instant.parse("2026-09-01T00:00:00Z")
    private val response = VoiceTutorCreateSessionResponse(
        sessionId = "00000000-0000-4000-8000-000000000007",
        state = VoiceTutorSessionStatus.READY,
        websocketUrl = "/api/v1/voice-tutor/sessions/session/ws",
        sdpUrl = "/api/v1/voice-tutor/sessions/session/webrtc",
        controlWebsocketUrl = "/api/v1/voice-tutor/sessions/session/control",
        createdAt = now,
        hardEndsAt = now.plusSeconds(3_600),
        recording = VoiceTutorRecordingResponse(
            enabled = false, available = false, status = null, retentionDays = 30,
            expiresAt = null, recordingId = null, contentType = null, contentLength = null, durationSeconds = null,
        ),
        quota = VoiceTutorQuotaResponse(
            tierCode = "TIER2", periodStartedAt = now, resetAt = now.plusSeconds(86_400),
            limitSeconds = 3_600, usedSeconds = 0, reservedSeconds = 3_600, remainingSeconds = 0,
        ),
    )

    @Test
    fun `omitted or null study ID reaches authenticated discovery without replacing language or voice`(): Unit = runBlocking {
        for (body in listOf(
            """{"language":"ja","voice":"ash"}""",
            """{"studyId":null,"language":"ja","voice":"ash"}""",
        )) {
            val voiceTutor = mock(VoiceTutorUseCase::class.java)
            `when`(voiceTutor.createSession(principal, null, "ja", "ash", "discovery", false, null)).thenReturn(response)

            client(voiceTutor).post().uri("/api/v1/voice-tutor/sessions")
                .header("Idempotency-Key", "discovery")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isCreated
                .expectBody().jsonPath("$.sessionId").isEqualTo(response.sessionId)
                .jsonPath("$.realtimeTransport").isEqualTo("WEBRTC")

            verify(voiceTutor).createSession(principal, null, "ja", "ash", "discovery", false, null)
        }
    }

    @Test
    fun `legacy explicit topic request and consent fields retain their existing contract`(): Unit = runBlocking {
        val voiceTutor = mock(VoiceTutorUseCase::class.java)
        `when`(voiceTutor.createSession(principal, 42L, "en", "marin", "legacy", true, "consent-v1"))
            .thenReturn(response)

        client(voiceTutor).post().uri("/api/v1/voice-tutor/sessions")
            .header("Idempotency-Key", "legacy")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"studyId":42,"language":"en","voice":"marin","recordingConsent":true,"recordingConsentVersion":"consent-v1"}""")
            .exchange().expectStatus().isCreated

        verify(voiceTutor).createSession(principal, 42L, "en", "marin", "legacy", true, "consent-v1")
    }

    @Test
    fun `an empty request keeps Korean and consent defaults without inventing a study ID`(): Unit = runBlocking {
        val voiceTutor = mock(VoiceTutorUseCase::class.java)
        `when`(voiceTutor.createSession(principal, null, "ko", null, "default-discovery", false, null))
            .thenReturn(response)

        client(voiceTutor).post().uri("/api/v1/voice-tutor/sessions")
            .header("Idempotency-Key", "default-discovery")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
            .expectStatus().isCreated

        verify(voiceTutor).createSession(principal, null, "ko", null, "default-discovery", false, null)
    }

    @Test
    fun `nonpositive supplied study IDs and blank language fail request validation before reserving quota`(): Unit = runBlocking {
        for (body in listOf(
            """{"studyId":0,"language":"ko"}""",
            """{"studyId":-1,"language":"ko"}""",
            """{"studyId":null,"language":" "}""",
        )) {
            val voiceTutor = mock(VoiceTutorUseCase::class.java)

            client(voiceTutor).post().uri("/api/v1/voice-tutor/sessions")
                .header("Idempotency-Key", "invalid")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isBadRequest

            verifyNoInteractions(voiceTutor)
        }
    }

    private fun client(voiceTutor: VoiceTutorUseCase): WebTestClient = WebTestClient.bindToController(
        VoiceTutorController(VoiceTutorWebAdapter(voiceTutor, mock(VoiceTutorRecordingUseCase::class.java))),
    ).webFilter<WebTestClient.ControllerSpec>(
        WebFilter { exchange, chain ->
            chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
        },
    ).build()
}
