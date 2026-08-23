package com.buddystudy.common.domain

enum class SupportedLanguage(
    val databaseValue: String,
) {
    KOREAN("ko"),
    ENGLISH("en"),
    JAPANESE("ja"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): SupportedLanguage =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported language database value: $value")

        fun fromLocale(value: String): SupportedLanguage =
            when (value.trim().lowercase().substringBefore('-').substringBefore('_')) {
                "en" -> ENGLISH
                "ja" -> JAPANESE
                else -> KOREAN
            }
    }
}
