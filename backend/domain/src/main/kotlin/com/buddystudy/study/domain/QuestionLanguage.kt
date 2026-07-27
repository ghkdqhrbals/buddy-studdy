package com.buddystudy.study.domain

import com.buddystudy.study.domain.entity.QuestionEntity

object QuestionLanguage {
    const val KOREAN = "ko"
    const val ENGLISH = "en"

    fun normalize(value: String?): String =
        if (value?.trim()?.lowercase()?.startsWith(ENGLISH) == true) ENGLISH else KOREAN

    fun matches(text: String, language: String): Boolean {
        if (normalize(language) != ENGLISH) return true

        val latinLetters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val hangulLetters = text.count {
            it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x318E
        }
        return latinLetters >= 10 && hangulLetters * 2 <= latinLetters
    }

    fun matchesShortLabel(text: String, language: String): Boolean {
        if (normalize(language) != ENGLISH) return true
        if (text.isBlank()) return false
        return text.none {
            it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x318E
        }
    }
}

fun QuestionEntity.localizedFor(language: String): QuestionEntity {
    if (QuestionLanguage.normalize(language) != QuestionLanguage.ENGLISH) return this
    question = questionEn?.takeIf(String::isNotBlank) ?: question
    hint = hintEn?.takeIf(String::isNotBlank) ?: hint
    topic = topicEn?.takeIf(String::isNotBlank) ?: topic
    return this
}
