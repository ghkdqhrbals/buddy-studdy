package com.buddystudy.backend.voice.application.model

/** Spoken output policy; input transcription language is configured separately. */
object VoiceTutorLanguagePolicy {
    fun instructions(language: String): String {
        val name = when (language) {
            "ko" -> "Korean"
            "en" -> "English"
            "ja" -> "Japanese"
            else -> throw IllegalArgumentException("Unsupported Voice Tutor conversation language.")
        }
        return """
            # Language
            - The app-selected conversation language is $name ($language). Keep all ordinary spoken replies, explanations, confirmations and error notices in $name throughout this call.
            - Do not automatically switch languages because of a short utterance, a name, a foreign term, unclear audio, background speech, a transcript or a tool result. If an earlier reply drifted into another language, return to $name on this reply.
            - The conversation language stays fixed to the app's selection for this call. Foreign-language input alone does not change that selection.
            - Preserve foreign technical terms, proper names, code and passages that must be quoted or pronounced in their original form. Use $name for the surrounding explanation; quoting or translating a passage does not switch the conversation language.
        """.trimIndent()
    }

    fun openingQuestion(language: String): String = when (language) {
        "ko" -> "어떤 주제로 이야기해 볼까요?"
        "en" -> "What topic would you like to talk about?"
        "ja" -> "どんなテーマについて話しましょうか？"
        else -> throw IllegalArgumentException("Unsupported Voice Tutor conversation language.")
    }

    /** Response instructions replace the provider's session instructions instead of appending to them. */
    fun responseInstructions(language: String, responseInstruction: String): String =
        instructions(language) + "\n\n# Current response\n" + responseInstruction
}
