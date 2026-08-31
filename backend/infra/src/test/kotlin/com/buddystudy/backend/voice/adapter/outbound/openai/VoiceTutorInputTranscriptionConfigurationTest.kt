package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.test.StepVerifier
import java.time.Duration

/** Wire-configuration regression only; never claims recognition accuracy from synthetic JSON. */
class VoiceTutorInputTranscriptionConfigurationTest {
    private val mapper = JsonMapperProvider.mapper
    private val unavailableAssessment = object : VoiceTutorInputAssessmentUseCase {
        override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
            error("A transcription configuration test must never invoke a provider.")
    }
    private val pcm = OpenAIVoiceTutorRealtimeAdapter(BuddyStudyProperties(), unavailableAssessment)
    private val webRtc = OpenAIVoiceTutorWebRtcAdapter(BuddyStudyProperties(), unavailableAssessment)

    @TestFactory
    fun `all provider setup paths use the accepted call language independently of tutor instructions`() =
        listOf("ko", "en", "ja").map { language ->
            dynamicTest(language) {
                val request = request(language)
                val pcmInput = mapper.readTree(pcm.sessionUpdate(request)).path("session").path("audio").path("input")
                val webRtcInput = mapper.readTree(webRtc.webRtcSessionConfiguration(request)).path("audio").path("input")
                val snapshots = mutableListOf<VoiceTutorWebRtcConfigurationSnapshot>()
                val handshake = VoiceTutorWebRtcSessionHandshake(
                    "rtc_language-fixture", Duration.ofSeconds(5), { snapshots += it },
                    transcriptionLanguage = language,
                )
                val dispatched = handshake.initialProviderEvents().blockFirst(Duration.ofSeconds(1))!!
                val sidebandInput = mapper.readTree(dispatched).path("session").path("audio").path("input")
                val expected = mapper.readTree("""{"model":"gpt-4o-mini-transcribe","language":"$language"}""")

                for (input in listOf(pcmInput, webRtcInput, sidebandInput)) {
                    assertThat(input.path("transcription")).isEqualTo(expected)
                }
                assertThat(pcmInput.path("turn_detection").path("create_response").asBoolean()).isFalse()
                assertThat(pcmInput.path("turn_detection").path("interrupt_response").asBoolean()).isFalse()
                assertThat(webRtcInput.path("turn_detection").isNull).isTrue()
                assertThat(sidebandInput.path("turn_detection").isNull).isTrue()

                val acknowledgement = """{
                    "type":"session.updated",
                    "session":{"type":"realtime","audio":{"input":{
                        "turn_detection":null,
                        "transcription":{"model":"gpt-4o-mini-transcribe","language":"$language"}
                    }}}
                }""".trimIndent()
                handshake.observeProviderEvent(acknowledgement)
                StepVerifier.create(handshake.awaitConfirmation()).verifyComplete()
                assertThat(snapshots.single().expectedTranscriptionLanguage).isEqualTo(language)
                assertThat(snapshots.single().effectiveTranscriptionLanguage).isEqualTo(language)
                assertThat(snapshots.single().transcriptionLanguageVerified).isTrue()
                assertThat(snapshots.single().verified).isTrue()
            }
        }

    @Test
    fun `missing or unsupported internal session language never silently enables auto detection`() {
        for (language in listOf("", " ", "auto", "pt", "ko-KR", "PRIVATE_UNTRUSTED_LANGUAGE")) {
            for (configuration in listOf<() -> Any>(
                { pcm.sessionUpdate(request(language)) },
                { webRtc.webRtcSessionConfiguration(request(language)) },
                {
                    VoiceTutorWebRtcSessionHandshake(
                        "rtc_language-fixture", Duration.ofSeconds(5), transcriptionLanguage = language,
                    )
                },
            )) {
                assertThatThrownBy { configuration() }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Voice Tutor transcription requires a supported session language.")
            }
        }
    }

    private fun request(language: String) = VoiceTutorRealtimeRequest(
        userId = 42,
        model = "gpt-realtime-test",
        voice = "marin",
        // Deliberately invariant: a system prompt's writing language is not ASR configuration.
        instructions = "Tutor in the language accepted for this call; preserve English technical terms.",
        language = language,
    )
}
