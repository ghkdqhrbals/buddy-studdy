package com.buddystudy.study.domain

object QuestionLanguage {
    const val KOREAN = "ko"
    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    val supported = setOf(KOREAN, ENGLISH, JAPANESE)

    fun normalize(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return when {
            normalized.startsWith(JAPANESE) -> JAPANESE
            normalized.startsWith(ENGLISH) -> ENGLISH
            else -> KOREAN
        }
    }

    fun matches(text: String, language: String): Boolean {
        return when (normalize(language)) {
            ENGLISH -> {
                val latinLetters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
                val hangulOrJapanese = text.count { it.isHangul() || it.isJapanese() }
                latinLetters >= 10 && hangulOrJapanese * 2 <= latinLetters
            }
            JAPANESE -> text.count { it.isJapanese() } >= 2
            else -> text.count { it.isHangul() } >= 2
        }
    }

    fun matchesShortLabel(text: String, language: String): Boolean {
        if (text.isBlank()) return false
        return when (normalize(language)) {
            ENGLISH -> text.none { it.isHangul() || it.isJapanese() }
            JAPANESE -> text.any { it.isJapanese() }
            else -> text.any { it.isHangul() }
        }
    }

    fun matchesTranslation(
        source: String,
        translated: String,
        targetLanguage: String,
        shortLabel: Boolean = false,
    ): Boolean {
        val targetMatches = if (shortLabel) {
            matchesShortLabel(translated, targetLanguage)
        } else {
            matches(translated, targetLanguage)
        }
        if (targetMatches) return true
        return source.trim() == translated.trim() && isTranslationInvariant(source)
    }

    private fun isTranslationInvariant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.any { it.isHangul() || it.isJapanese() }) return false
        if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length > 6) return true

        val tokens = trimmed.split(Regex("\\s+")).filter(String::isNotBlank)
        return tokens.isNotEmpty() && tokens.all(::isTranslationInvariantToken)
    }

    private fun isTranslationInvariantToken(token: String): Boolean {
        val normalized = token.trim('(', ')', ',', ';', '!', '?', '.', ':')
        val asciiLetters = normalized.filter { it in 'A'..'Z' || it in 'a'..'z' }
        if (asciiLetters.isEmpty()) return true
        if (asciiLetters.length >= 2 && asciiLetters.all(Char::isUpperCase)) return true
        if (normalized.any { it in TECHNICAL_SEPARATORS }) return true
        return CAMEL_OR_PASCAL_IDENTIFIER.matches(normalized)
    }

    private fun Char.isHangul(): Boolean =
        code in 0xAC00..0xD7A3 || code in 0x3131..0x318E

    private fun Char.isJapanese(): Boolean =
        code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0x4E00..0x9FFF

    private val TECHNICAL_SEPARATORS = setOf(
        '`', '/', '\\', '_', '.', ':', '#', '=', '&', '%', '+', '@', '<', '>', '{', '}', '[', ']',
    )
    private val CAMEL_OR_PASCAL_IDENTIFIER = Regex("(?:[a-z]+[A-Z]|[A-Z][a-z]+[A-Z])[A-Za-z0-9]*")
}
