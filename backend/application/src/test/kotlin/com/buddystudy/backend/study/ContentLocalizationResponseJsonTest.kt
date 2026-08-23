package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.model.originalLocalization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContentLocalizationResponseJsonTest {
    @Test
    fun `localization metadata keeps the isTranslated API field name`() {
        val response = originalLocalization(
            sourceLanguage = "ko",
            requestedLanguage = "ko",
            viewMode = TranslationViewMode.LOCALIZED,
        )

        val json = JsonMapperProvider.mapper.readTree(
            JsonMapperProvider.mapper.writeValueAsString(response),
        )

        assertThat(json.path("isTranslated").asBoolean()).isFalse()
        assertThat(json.has("translated")).isFalse()
    }
}
