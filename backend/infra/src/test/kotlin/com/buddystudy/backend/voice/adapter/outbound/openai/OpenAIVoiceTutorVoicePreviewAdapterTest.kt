package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorRealtimeVoice
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewLanguage
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewRequest
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

class OpenAIVoiceTutorVoicePreviewAdapterTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `speech request uses official endpoint model mp3 and user content key`() = runBlocking<Unit> {
        var sent: ClientRequest? = null
        var body = ""
        val adapter = adapter(ExchangeFunction { request ->
            sent = request
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map { serialized ->
                    body = serialized
                    audioResponse(byteArrayOf(0x49, 0x44, 0x33))
                }
            })
        })

        val audio = adapter.synthesize(request(VoiceTutorRealtimeVoice.CEDAR, VoiceTutorVoicePreviewLanguage.KOREAN))

        assertThat(sent!!.method()).isEqualTo(HttpMethod.POST)
        assertThat(sent!!.url().toString()).isEqualTo("https://api.openai.com/v1/audio/speech")
        assertThat(sent!!.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer private-preview-key")
        assertThat(sent!!.headers().contentType).isEqualTo(MediaType.APPLICATION_JSON)
        assertThat(sent!!.headers().accept).containsExactly(MediaType.parseMediaType("audio/mpeg"))
        assertThat(mapper.readTree(body)).isEqualTo(mapper.valueToTree<JsonNode>(
            mapOf(
                "model" to "gpt-4o-mini-tts",
                "voice" to "cedar",
                "input" to "안녕하세요. 함께 공부해 볼까요?",
                "response_format" to "mp3",
            ),
        ))
        assertThat(body).doesNotContain("private-preview-key", "private-system-key", "userId", "instructions")
        assertThat(audio.contentType).isEqualTo("audio/mpeg")
        assertThat(audio.bytes).containsExactly(*byteArrayOf(0x49, 0x44, 0x33))
    }

    @Test
    fun `request contract can only select fixed localized phrases`() {
        val phrases = VoiceTutorVoicePreviewLanguage.entries.associate { language ->
            language.code to VoiceTutorVoicePreviewSpeechContract.requestBody(
                request(VoiceTutorRealtimeVoice.MARIN, language),
            ).getValue("input")
        }

        assertThat(phrases).containsExactlyEntriesOf(
            linkedMapOf(
                "ko" to "안녕하세요. 함께 공부해 볼까요?",
                "en" to "Hello. Shall we study together?",
                "ja" to "こんにちは。一緒に勉強しましょう。",
            ),
        )
        assertThat(VoiceTutorVoicePreviewSpeechContract.requestBody(request()).keys)
            .containsExactly("model", "voice", "input", "response_format")
    }

    @Test
    fun `non success response is released and never reflects provider body`() = runBlocking<Unit> {
        val privateProviderBody = "private provider diagnostic with echoed input"
        val adapter = adapter(ExchangeFunction {
            Mono.just(
                ClientResponse.create(HttpStatus.BAD_REQUEST)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(privateProviderBody)
                    .build(),
            )
        })

        val error = assertThrows<ApiException> { adapter.synthesize(request()) }

        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(error.toString()).doesNotContain(privateProviderBody, "echoed input")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `missing or incompatible provider content type fails closed`() = runBlocking<Unit> {
        for (contentType in listOf<String?>(null, "application/octet-stream", "audio/wav", "text/plain")) {
            val adapter = adapter(ExchangeFunction {
                Mono.just(audioResponse(byteArrayOf(1, 2, 3), contentType = contentType))
            })

            val error = assertThrows<ApiException> { adapter.synthesize(request()) }
            assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        }
    }

    @Test
    fun `empty provider audio fails closed`() = runBlocking<Unit> {
        val adapter = adapter(ExchangeFunction {
            Mono.just(audioResponse(byteArrayOf()))
        })

        val error = assertThrows<ApiException> { adapter.synthesize(request()) }

        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `declared response larger than hard bound is rejected before decoding`() = runBlocking<Unit> {
        val adapter = adapter(ExchangeFunction {
            Mono.just(
                audioResponse(
                    byteArrayOf(1),
                    declaredLength = VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES.toLong() + 1,
                ),
            )
        })

        val error = assertThrows<ApiException> { adapter.synthesize(request()) }

        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `streamed response larger than decoder bound is rejected without retaining bytes`() = runBlocking<Unit> {
        val oversized = ByteArray(VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES + 1) { 7 }
        val adapter = adapter(ExchangeFunction {
            Mono.just(audioResponse(oversized, declaredLength = null))
        })

        val error = assertThrows<ApiException> { adapter.synthesize(request()) }

        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(error.toString()).doesNotContain(oversized.take(16).joinToString())
    }

    @Test
    fun `caller cancellation cancels the provider subscription`() = runBlocking<Unit> {
        val cancelled = AtomicBoolean(false)
        val adapter = adapter(ExchangeFunction {
            Mono.never<ClientResponse>().doOnCancel { cancelled.set(true) }
        })

        val task = async(start = CoroutineStart.UNDISPATCHED) { adapter.synthesize(request()) }
        task.cancelAndJoin()

        assertThat(task.isCancelled).isTrue()
        assertThat(cancelled).isTrue()
    }

    private fun adapter(exchange: ExchangeFunction): OpenAIVoiceTutorVoicePreviewAdapter {
        val properties = BuddyStudyProperties().apply {
            openai.userContentApiKey = "private-preview-key"
            openai.systemApiKey = "private-system-key"
            openai.requestTimeoutSeconds = 10
        }
        return OpenAIVoiceTutorVoicePreviewAdapter(
            UserContentOpenAIKeyProvider(properties),
            properties,
            exchange,
        )
    }

    private fun request(
        voice: VoiceTutorRealtimeVoice = VoiceTutorRealtimeVoice.MARIN,
        language: VoiceTutorVoicePreviewLanguage = VoiceTutorVoicePreviewLanguage.ENGLISH,
    ) = VoiceTutorVoicePreviewRequest(voice, language)

    private fun audioResponse(
        bytes: ByteArray,
        contentType: String? = "audio/mpeg",
        declaredLength: Long? = bytes.size.toLong(),
    ): ClientResponse {
        val builder = ClientResponse.create(HttpStatus.OK)
        if (contentType != null) builder.header(HttpHeaders.CONTENT_TYPE, contentType)
        if (declaredLength != null) builder.header(HttpHeaders.CONTENT_LENGTH, declaredLength.toString())
        return builder.body(
            if (bytes.isEmpty()) Flux.empty()
            else Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)),
        ).build()
    }
}
