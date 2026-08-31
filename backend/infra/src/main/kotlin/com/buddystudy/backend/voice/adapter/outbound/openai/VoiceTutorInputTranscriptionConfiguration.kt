package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.study.domain.QuestionLanguage

/**
 * Speech-to-text is independent of the realtime model's conversation instructions.
 * Use the accepted call language at every provider setup boundary, rather than
 * relying on automatic language detection for short utterances. This is an ASR
 * hint, not a transcript filter: mixed-language terms and original text survive.
 */
internal fun voiceTutorInputTranscription(language: String): Map<String, String> {
    require(language in QuestionLanguage.supported) {
        "Voice Tutor transcription requires a supported session language."
    }
    return linkedMapOf(
        "model" to "gpt-4o-mini-transcribe",
        "language" to language,
    )
}
