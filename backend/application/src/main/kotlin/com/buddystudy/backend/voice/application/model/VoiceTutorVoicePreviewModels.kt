package com.buddystudy.backend.voice.application.model

/** Voices shared with the Voice Tutor Realtime session contract. */
enum class VoiceTutorRealtimeVoice(val apiValue: String) {
    ALLOY("alloy"),
    ASH("ash"),
    BALLAD("ballad"),
    CORAL("coral"),
    ECHO("echo"),
    SAGE("sage"),
    SHIMMER("shimmer"),
    VERSE("verse"),
    MARIN("marin"),
    CEDAR("cedar"),
    ;

    companion object {
        fun fromApiValue(value: String): VoiceTutorRealtimeVoice? =
            entries.firstOrNull { it.apiValue == value }
    }
}

/**
 * Preview copy is selected entirely by the server. The HTTP contract has no
 * caller-controlled text field, and the provider port receives this enum
 * rather than an arbitrary prompt.
 */
enum class VoiceTutorVoicePreviewLanguage(
    val code: String,
    val samplePhrase: String,
) {
    KOREAN("ko", "안녕하세요. 함께 공부해 볼까요?"),
    ENGLISH("en", "Hello. Shall we study together?"),
    JAPANESE("ja", "こんにちは。一緒に勉強しましょう。"),
    ;

    companion object {
        fun fromCode(value: String): VoiceTutorVoicePreviewLanguage? =
            entries.firstOrNull { it.code == value }
    }
}

data class VoiceTutorVoicePreviewRequest(
    val voice: VoiceTutorRealtimeVoice,
    val language: VoiceTutorVoicePreviewLanguage,
)

class VoiceTutorVoicePreviewAudio(
    val bytes: ByteArray,
    val contentType: String = CONTENT_TYPE,
) {
    init {
        require(bytes.isNotEmpty()) { "Voice preview audio must not be empty." }
        require(bytes.size <= MAX_AUDIO_BYTES) { "Voice preview audio exceeds its bounded contract." }
        require(contentType == CONTENT_TYPE) { "Voice preview audio content type is unsupported." }
    }

    companion object {
        const val CONTENT_TYPE = "audio/mpeg"
        const val MAX_AUDIO_BYTES = 512 * 1024
    }
}
