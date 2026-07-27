package com.buddystudy.study.domain

import com.buddystudy.study.domain.entity.QuestionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionLanguageTest {
    @Test
    fun `normalizes supported language variants`() {
        assertEquals(QuestionLanguage.ENGLISH, QuestionLanguage.normalize("en-US"))
        assertEquals(QuestionLanguage.KOREAN, QuestionLanguage.normalize("ko-KR"))
        assertEquals(QuestionLanguage.KOREAN, QuestionLanguage.normalize(null))
    }

    @Test
    fun `rejects untranslated Korean content for English delivery`() {
        assertFalse(QuestionLanguage.matches("Redis Stream의 소비자 그룹을 설명하세요.", "en"))
        assertTrue(QuestionLanguage.matches("Explain how Redis Stream consumer groups distribute messages.", "en"))
    }

    @Test
    fun `uses persisted English translation without changing Korean content`() {
        val question = QuestionEntity(
            question = "Redis Stream의 소비자 그룹을 설명하세요.",
            hint = "pending entry를 포함하세요.",
            questionEn = "Explain Redis Stream consumer groups.",
            hintEn = "Include pending entries.",
        )

        val english = question.localizedFor("en-US")

        assertEquals("Explain Redis Stream consumer groups.", english.question)
        assertEquals("Include pending entries.", english.hint)
    }
}
