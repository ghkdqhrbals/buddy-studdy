package com.buddystudy.backend.localization

import com.buddystudy.backend.localization.adapter.outbound.language.LinguaContentLanguageDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LinguaContentLanguageDetectorTest {
    private val detector = LinguaContentLanguageDetector()

    @Test
    fun `detects Korean English and Japanese user content`() {
        assertThat(detector.detect("이 답변은 한국어로 작성되었습니다.", "en")).isEqualTo("ko")
        assertThat(detector.detect("This answer explains the consumer group behavior.", "ko")).isEqualTo("en")
        assertThat(detector.detect("この回答は日本語で書かれています。", "ko")).isEqualTo("ja")
    }

    @Test
    fun `uses the selected app language for short ambiguous content`() {
        assertThat(detector.detect("OK", "ja")).isEqualTo("ja")
    }
}
