package com.buddystudy.backend.study.adapter.outbound.translation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LibreTranslateResponseDecoderTest {
    @Test
    fun `preserves unicode translation and tolerates provider metadata`() {
        assertThat(decodeLibreTranslateResponse("""{"translatedText":"  こんにちは  ","detectedLanguage":{"language":"ko"}}"""))
            .isEqualTo("こんにちは")
    }

    @Test
    fun `rejects empty missing mistyped and malformed translations`() {
        listOf(null, "", "{}", "null", "[]", "not-json", """{"translatedText":" "}""",
            """{"translatedText":null}""", """{"translatedText":12}""").forEach { body ->
            assertThatThrownBy { decodeLibreTranslateResponse(body) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
