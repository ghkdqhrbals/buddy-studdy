package com.buddystudy.backend.voice.adapter.outbound.openai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VoiceTutorInputBudgetTest {
    @Test
    fun `standalone override removes inherited tools and inputs without changing exact source or history destination`() {
        val source = "Read this exact saved question: Why is Redis fast?"
        val options = linkedMapOf<String, Any>("instructions" to source, "metadata" to mapOf("id" to "r1"), "output_modalities" to listOf("audio"))
        VoiceTutorInputBudget.standaloneSpeech(options)
        assertThat(options["tools"]).isEqualTo(emptyList<Any>())
        assertThat(options["input"]).isEqualTo(emptyList<Any>())
        assertThat(options["tool_choice"]).isEqualTo("none")
        assertThat(options["instructions"]).isEqualTo(source)
        assertThat(options["metadata"]).isEqualTo(mapOf("id" to "r1"))
        assertThat(options).doesNotContainKey("conversation")
    }

    @Test
    fun `context cannot be removed without self-contained instructions`() {
        for (options in listOf(mutableMapOf<String, Any>(), mutableMapOf("instructions" to " " as Any))) {
            assertThatThrownBy { VoiceTutorInputBudget.standaloneSpeech(options) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(options).doesNotContainKey("input")
        }
    }

    @Test
    fun `budget retains headroom rather than invalidating cache with every turn`() {
        val budget = VoiceTutorInputBudget.truncation()
        assertThat(budget["type"]).isEqualTo("retention_ratio")
        assertThat(budget["retention_ratio"]).isEqualTo(0.8)
        assertThat(budget["token_limits"]).isEqualTo(mapOf("post_instructions" to 8_000))
    }
}
