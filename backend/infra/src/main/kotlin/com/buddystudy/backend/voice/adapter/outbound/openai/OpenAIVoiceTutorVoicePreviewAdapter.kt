package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorVoicePreviewPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class OpenAIVoiceTutorVoicePreviewAdapter private constructor(
    private val keys: UserContentOpenAIKeyProvider,
    private val properties: BuddyStudyProperties,
    private val client: WebClient,
) : VoiceTutorVoicePreviewPort {
    @Autowired
    constructor(keys: UserContentOpenAIKeyProvider, properties: BuddyStudyProperties) :
        this(keys, properties, client())

    internal constructor(
        keys: UserContentOpenAIKeyProvider,
        properties: BuddyStudyProperties,
        exchange: ExchangeFunction,
    ) : this(keys, properties, client(exchange))

    override suspend fun synthesize(request: VoiceTutorVoicePreviewRequest): VoiceTutorVoicePreviewAudio {
        try {
            val key = keys.requireApiKey()
            return client.post()
                .uri("/v1/audio/speech")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $key")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(PREVIEW_MEDIA_TYPE)
                .bodyValue(VoiceTutorVoicePreviewSpeechContract.requestBody(request))
                .exchangeToMono { response ->
                    val contentLength = response.headers().contentLength().orElse(-1L)
                    val contentType = response.headers().contentType().orElse(null)
                    when {
                        !response.statusCode().is2xxSuccessful ->
                            response.releaseBody().then(Mono.error(unavailable()))
                        contentLength > VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES ->
                            response.releaseBody().then(Mono.error(unavailable()))
                        contentType == null || !PREVIEW_MEDIA_TYPE.isCompatibleWith(contentType) ->
                            response.releaseBody().then(Mono.error(unavailable()))
                        else -> response.bodyToMono(ByteArray::class.java)
                            .switchIfEmpty(Mono.error(unavailable()))
                            .map { bytes ->
                                if (bytes.isEmpty() || bytes.size > VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES) {
                                    throw unavailable()
                                }
                                VoiceTutorVoicePreviewAudio(bytes)
                            }
                    }
                }
                .timeout(Duration.ofSeconds(properties.openai.requestTimeoutSeconds.coerceIn(2, 30)))
                .awaitSingle()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            throw error
        } catch (_: Exception) {
            // Provider bodies and decoder details may contain sensitive data.
            // They are deliberately neither retained nor reflected to callers.
            throw unavailable()
        }
    }

    private companion object {
        val PREVIEW_MEDIA_TYPE: MediaType = MediaType.parseMediaType(VoiceTutorVoicePreviewAudio.CONTENT_TYPE)

        fun client(exchange: ExchangeFunction? = null): WebClient = WebClient.builder()
            .baseUrl("https://api.openai.com")
            .codecs { codecs ->
                codecs.defaultCodecs().maxInMemorySize(VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES)
            }
            .also { builder -> if (exchange != null) builder.exchangeFunction(exchange) }
            .build()

        fun unavailable() = ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE,
            "Voice Tutor voice preview is temporarily unavailable.",
        )
    }
}

internal object VoiceTutorVoicePreviewSpeechContract {
    const val MODEL = "gpt-4o-mini-tts"

    fun requestBody(request: VoiceTutorVoicePreviewRequest): Map<String, String> = linkedMapOf(
        "model" to MODEL,
        "voice" to request.voice.apiValue,
        "input" to request.language.samplePhrase,
        "response_format" to "mp3",
    )
}
