package com.buddystudy.backend.community.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommunityTargetLanguageTest {
    @Test
    fun `tl takes precedence over the legacy language parameter`() {
        assertThat(targetLanguage(" en ", "ko")).isEqualTo("en")
    }

    @Test
    fun `legacy language remains supported`() {
        assertThat(targetLanguage(null, " en ")).isEqualTo("en")
    }

    @Test
    fun `Japanese target language is preserved`() {
        assertThat(targetLanguage("ja", "ko")).isEqualTo("ja")
    }

    @Test
    fun `missing and blank values default to Korean`() {
        assertThat(targetLanguage(" ", null)).isEqualTo("ko")
    }
}
