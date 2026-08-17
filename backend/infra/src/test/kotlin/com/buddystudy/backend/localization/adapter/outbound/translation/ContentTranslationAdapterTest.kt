package com.buddystudy.backend.localization.adapter.outbound.translation

import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections

class ContentTranslationAdapterTest {
    @Test
    fun `uses sentence validation only for the question field`() = runBlocking {
        val translations = RecordingQuestionTranslationPort()
        val adapter = ContentTranslationAdapter(translations)

        adapter.translate(
            fields = linkedMapOf(
                "question" to "인덱스를 설명하세요.",
                "answer" to "확인",
                "feedback" to "좋아요",
                "body" to "동의",
            ),
            sourceLanguages = mapOf(
                "question" to "ko",
                "answer" to "ko",
                "feedback" to "ko",
                "body" to "ko",
            ),
            targetLanguage = "en",
        )

        assertThat(translations.validationModes).containsExactlyInAnyOrder(
            "인덱스를 설명하세요." to TranslationValidationMode.QUESTION,
            "확인" to TranslationValidationMode.SHORT_TEXT,
            "좋아요" to TranslationValidationMode.SHORT_TEXT,
            "동의" to TranslationValidationMode.SHORT_TEXT,
        )
        assertThat(translations.topics).containsOnly("CONTENT")
    }

    private class RecordingQuestionTranslationPort : QuestionTranslationPort {
        val topics = Collections.synchronizedList(mutableListOf<String>())
        val validationModes = Collections.synchronizedList(
            mutableListOf<Pair<String, TranslationValidationMode>>(),
        )

        override suspend fun translate(
            topic: String,
            question: String,
            hint: String?,
            sourceLanguage: String,
            targetLanguage: String,
            validationMode: TranslationValidationMode,
        ): TranslatedQuestionContent {
            topics += topic
            validationModes += question to validationMode
            return TranslatedQuestionContent(
                topic = "Content",
                question = "Translated: $question",
                hint = null,
            )
        }
    }
}
