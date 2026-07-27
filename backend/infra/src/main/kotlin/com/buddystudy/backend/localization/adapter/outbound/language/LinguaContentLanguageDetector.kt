package com.buddystudy.backend.localization.adapter.outbound.language

import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.study.domain.QuestionLanguage
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import org.springframework.stereotype.Component

@Component
class LinguaContentLanguageDetector : ContentLanguageDetectionPort {
    private val detector = LanguageDetectorBuilder
        .fromLanguages(Language.KOREAN, Language.ENGLISH, Language.JAPANESE)
        .build()

    override fun detect(text: String, fallbackLanguage: String): String {
        val fallback = QuestionLanguage.normalize(fallbackLanguage)
        val candidate = text.trim()
        if (candidate.length < MINIMUM_DETECTION_LENGTH) return fallback
        return when (detector.detectLanguageOf(candidate)) {
            Language.KOREAN -> QuestionLanguage.KOREAN
            Language.ENGLISH -> QuestionLanguage.ENGLISH
            Language.JAPANESE -> QuestionLanguage.JAPANESE
            else -> fallback
        }
    }

    private companion object {
        const val MINIMUM_DETECTION_LENGTH = 4
    }
}
