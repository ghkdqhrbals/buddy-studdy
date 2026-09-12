package com.buddystudy.backend.voice.adapter.outbound.openai

/** Provider context budget only; never edits persisted transcripts, drafts or lesson state. */
internal object VoiceTutorInputBudget {
    // Keep enough recent conversation for a lesson, with headroom between truncations
    // so the provider does not invalidate the cached prefix on every turn.
    fun truncation(): Map<String, Any> = mapOf(
        "type" to "retention_ratio",
        "retention_ratio" to 0.8,
        "token_limits" to mapOf("post_instructions" to 8_000),
    )

    /** The instructions already contain the exact saved source for these utterances. */
    fun standaloneSpeech(options: MutableMap<String, Any>) {
        require(options["instructions"] is String && (options["instructions"] as String).isNotBlank())
        options["tools"] = emptyList<Any>()
        options["tool_choice"] = "none"
        options["input"] = emptyList<Any>()
        // Keep the generated utterance in the normal conversation for the next reply.
        // Do not send conversation=none or delete any earlier conversation item.
    }
}
