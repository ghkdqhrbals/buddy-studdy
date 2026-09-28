package com.buddystudy.backend.common.application.model

import com.buddystudy.common.domain.SupportedLanguage
import java.time.Duration
import java.time.Instant

/** Response-only presentation metadata. Never persist relative text with a record. */
data class RecordTimeDisplay(
    val timestamp: Instant,
    val relativeText: String,
    val language: String,
    val generatedAt: Instant,
)

/** One context per HTTP response keeps all rows on the same time boundary. */
class RecordTimeDisplayPolicy(language: String, private val now: Instant = Instant.now()) {
    private val language = SupportedLanguage.fromLocale(language)

    fun display(answeredAt: Instant?, createdAt: Instant): RecordTimeDisplay {
        val timestamp = answeredAt ?: createdAt
        val seconds = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
        val relativeText = when {
            seconds < 60 -> when (language) {
                SupportedLanguage.KOREAN -> "방금 전"
                SupportedLanguage.ENGLISH -> "just now"
                SupportedLanguage.JAPANESE -> "たった今"
            }
            seconds < 3_600 -> amount(seconds / 60, "분", "minute", "分")
            seconds < 86_400 -> amount(seconds / 3_600, "시간", "hour", "時間")
            seconds < 604_800 -> amount(seconds / 86_400, "일", "day", "日")
            seconds < 2_592_000 -> amount(seconds / 604_800, "주", "week", "週間")
            seconds < 31_536_000 -> amount(seconds / 2_592_000, "개월", "month", "か月")
            else -> amount(seconds / 31_536_000, "년", "year", "年")
        }
        return RecordTimeDisplay(timestamp, relativeText, language.databaseValue, now)
    }

    private fun amount(count: Long, korean: String, english: String, japanese: String): String = when (language) {
        SupportedLanguage.KOREAN -> "${count}${korean} 전"
        SupportedLanguage.ENGLISH -> "$count $english${if (count == 1L) "" else "s"} ago"
        SupportedLanguage.JAPANESE -> "${count}${japanese}前"
    }
}
