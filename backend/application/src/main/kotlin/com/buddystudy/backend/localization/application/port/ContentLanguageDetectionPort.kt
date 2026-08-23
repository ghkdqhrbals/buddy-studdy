package com.buddystudy.backend.localization.application.port

interface ContentLanguageDetectionPort {
    /**
     * Detects one of the product-supported content languages.
     * Implementations must return [fallbackLanguage] for short or ambiguous text.
     */
    fun detect(text: String, fallbackLanguage: String): String
}
