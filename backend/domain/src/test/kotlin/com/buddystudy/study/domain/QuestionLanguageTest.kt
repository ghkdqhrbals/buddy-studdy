package com.buddystudy.study.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionLanguageTest {
    @Test
    fun `normalizes supported language variants`() {
        assertEquals(QuestionLanguage.ENGLISH, QuestionLanguage.normalize("en-US"))
        assertEquals(QuestionLanguage.KOREAN, QuestionLanguage.normalize("ko-KR"))
        assertEquals(QuestionLanguage.JAPANESE, QuestionLanguage.normalize("ja-JP"))
        assertEquals(QuestionLanguage.KOREAN, QuestionLanguage.normalize(null))
    }

    @Test
    fun `recognizes Japanese questions and labels`() {
        assertTrue(QuestionLanguage.matches("Redis Streamのコンシューマーグループを説明してください。", "ja"))
        assertTrue(QuestionLanguage.matchesShortLabel("分散システム", "ja"))
        assertFalse(QuestionLanguage.matches("Redis Stream의 소비자 그룹을 설명하세요.", "ja"))
    }

    @Test
    fun `rejects untranslated Korean content for English delivery`() {
        assertFalse(QuestionLanguage.matches("Redis Stream의 소비자 그룹을 설명하세요.", "en"))
        assertTrue(QuestionLanguage.matches("Explain how Redis Stream consumer groups distribute messages.", "en"))
        assertFalse(QuestionLanguage.matchesShortLabel("프록시 생성 방식", "en"))
        assertTrue(QuestionLanguage.matchesShortLabel("Spring 4.x", "en"))
    }
}
